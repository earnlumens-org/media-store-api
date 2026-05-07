package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.request.UpdateEntryMetadataRequest;
import org.earnlumens.mediastore.domain.media.dto.request.UpdateEntryStatusRequest;
import org.earnlumens.mediastore.domain.media.dto.response.MediaEntitlementResponse;
import org.earnlumens.mediastore.domain.media.model.Asset;
import org.earnlumens.mediastore.domain.media.model.AssetStatus;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.MediaKind;
import org.earnlumens.mediastore.domain.media.repository.AssetRepository;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.infrastructure.r2.R2PresignedUrlService;
import org.earnlumens.mediastore.infrastructure.config.PlatformConfig;
import org.earnlumens.mediastore.domain.media.repository.OrderRepository;
import org.earnlumens.mediastore.domain.user.repository.UserRepository;
import org.earnlumens.mediastore.application.user.UserBadgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Cross-tenant isolation tests at the application service layer.
 * <p>
 * Models a 2-tenant universe (tenant A and tenant B) where each tenant
 * owns one entry. The mocked repositories enforce the same
 * {@code findByTenantIdAndId} semantics as the real Mongo-backed repos:
 * a lookup with mismatched tenant returns {@link Optional#empty()}.
 * <p>
 * The tests then assert that:
 * <ul>
 *   <li>A request authenticated for tenant A but referencing tenant B's
 *       entry id is treated identically to "entry not found": no
 *       mutation, no information leak (services return {@code false} or
 *       empty Optional).</li>
 *   <li>The same operations succeed when the request and entry belong
 *       to the same tenant (sanity baseline).</li>
 * </ul>
 * <p>
 * This complements the static {@code TenantIsolationArchTest} (which
 * proves every repository method requires a {@code tenantId} parameter)
 * with a dynamic check at the service boundary.
 */
class CrossTenantIsolationTest {

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final String USER_A   = "user-a";
    private static final String USER_B   = "user-b";
    private static final String ENTRY_A  = "entry-a-id";
    private static final String ENTRY_B  = "entry-b-id";

    private EntryRepository entryRepository;
    private AssetRepository assetRepository;
    private EntitlementRepository entitlementRepository;
    private CollectionRepository collectionRepository;

    private MediaEntitlementService entitlementService;
    private EntryUploadService uploadService;

    /** Composite key (tenantId|entryId) → Entry. Simulates a Mongo collection sharded by tenant. */
    private final Map<String, Entry> world = new HashMap<>();

    @BeforeEach
    void setUp() {
        entryRepository       = mock(EntryRepository.class);
        assetRepository       = mock(AssetRepository.class);
        entitlementRepository = mock(EntitlementRepository.class);
        collectionRepository  = mock(CollectionRepository.class);

        // Seed two tenants, one entry each. Each entry is FREE so the
        // entitlement service exercises the (tenant, owner) lookup path
        // without needing entitlement mocks.
        Entry entryA = makeEntry(TENANT_A, ENTRY_A, USER_A);
        Entry entryB = makeEntry(TENANT_B, ENTRY_B, USER_B);
        world.put(key(TENANT_A, ENTRY_A), entryA);
        world.put(key(TENANT_B, ENTRY_B), entryB);

        // Repository contract: findByTenantIdAndId(t, id) returns the entry
        // ONLY when the (tenant, id) tuple exists in the world. This mirrors
        // the real Mongo query where tenantId is part of the index.
        when(entryRepository.findByTenantIdAndId(anyString(), anyString()))
                .thenAnswer(inv -> {
                    String t = inv.getArgument(0);
                    String id = inv.getArgument(1);
                    return Optional.ofNullable(world.get(key(t, id)));
                });

        // Asset lookup for FREE entries — return a READY full asset for each
        // tenant's own entry, nothing across tenants.
        when(assetRepository.findByTenantIdAndEntryIdAndKindAndStatus(
                anyString(), anyString(), eq(MediaKind.FULL), eq(AssetStatus.READY)))
                .thenAnswer(inv -> {
                    String t = inv.getArgument(0);
                    String id = inv.getArgument(1);
                    Entry e = world.get(key(t, id));
                    if (e == null) return Optional.empty();
                    Asset a = new Asset();
                    a.setTenantId(t);
                    a.setEntryId(id);
                    a.setR2Key("private/media/" + t + "/" + id + "/full/file.mp4");
                    a.setContentType("video/mp4");
                    a.setFileName("file.mp4");
                    a.setKind(MediaKind.FULL);
                    a.setStatus(AssetStatus.READY);
                    return Optional.of(a);
                });

        entitlementService = new MediaEntitlementService(
                entryRepository, entitlementRepository, assetRepository, collectionRepository);

        uploadService = new EntryUploadService(
                entryRepository,
                assetRepository,
                mock(UserRepository.class),
                mock(OrderRepository.class),
                mock(R2PresignedUrlService.class),
                mock(PlatformConfig.class),
                mock(TranscodingJobService.class),
                mock(ModerationJobService.class),
                mock(UserBadgeService.class),
                mock(org.earnlumens.mediastore.application.space.SpaceValidationService.class),
                /* dailyEntryLimit  */ 20,
                /* maxConcurrentReview */ 10
        );
    }

    // ───────────────────────── baselines ──────────────────────

    @Test
    void sameTenant_owner_canAccessOwnFreeEntry() {
        Optional<MediaEntitlementResponse> res =
                entitlementService.checkEntitlement(TENANT_A, USER_A, ENTRY_A);
        assertTrue(res.isPresent(), "owner of free entry in same tenant must be granted");
    }

    // ───────────────────────── cross-tenant reads ─────────────

    @Test
    void crossTenant_read_returnsEmpty_noLeakage() {
        // Tenant A user references tenant B's entry id. Even though entry B
        // is FREE (would be readable to anyone WITHIN tenant B), the request
        // is scoped to tenant A and must therefore be rejected.
        Optional<MediaEntitlementResponse> res =
                entitlementService.checkEntitlement(TENANT_A, USER_A, ENTRY_B);

        assertTrue(res.isEmpty(),
                "cross-tenant entitlement check must not leak entry B to a tenant-A request");

        // The service must scope the lookup by tenantId: the call MUST be
        // (tenant-a, entry-b-id), never (anything, entry-b-id).
        verify(entryRepository).findByTenantIdAndId(TENANT_A, ENTRY_B);
        verify(entryRepository, never()).findByTenantIdAndId(eq(TENANT_B), anyString());
    }

    @Test
    void crossTenant_anonymous_read_returnsEmpty() {
        // Even an anonymous (CDN worker) request that resolves to tenant A
        // cannot reach tenant B's content by guessing an entry id.
        Optional<MediaEntitlementResponse> res =
                entitlementService.checkEntitlement(TENANT_A, /* userId */ null, ENTRY_B);
        assertTrue(res.isEmpty());
        verify(entryRepository).findByTenantIdAndId(TENANT_A, ENTRY_B);
    }

    // ───────────────────────── cross-tenant mutations ─────────

    @Test
    void crossTenant_updateStatus_returnsFalse_andDoesNotMutate() {
        UpdateEntryStatusRequest req = new UpdateEntryStatusRequest("IN_REVIEW");

        boolean updated = uploadService.updateEntryStatus(TENANT_A, USER_A, ENTRY_B, req);

        assertFalse(updated, "tenant-A user must not be able to mutate tenant-B's entry");
        verify(entryRepository).findByTenantIdAndId(TENANT_A, ENTRY_B);
        // No save / delete / cross-tenant lookup should ever be triggered.
        verify(entryRepository, never()).save(any(Entry.class));
        verify(entryRepository, never()).deleteByTenantIdAndId(anyString(), anyString());
        verify(entryRepository, never()).findByTenantIdAndId(eq(TENANT_B), anyString());
    }

    @Test
    void crossTenant_updateMetadata_returnsFalse_andDoesNotMutate() {
        UpdateEntryMetadataRequest req = new UpdateEntryMetadataRequest(
                "evil-title", null, null, null, null, null, null, null, null);

        boolean updated = uploadService.updateEntryMetadata(TENANT_A, USER_A, ENTRY_B, req);

        assertFalse(updated, "tenant-A user must not be able to retitle tenant-B's entry");
        verify(entryRepository).findByTenantIdAndId(TENANT_A, ENTRY_B);
        verify(entryRepository, never()).save(any(Entry.class));
    }

    @Test
    void crossTenant_unarchive_returnsFalse_andDoesNotMutate() {
        boolean unarchived = uploadService.unarchiveEntry(TENANT_A, USER_A, ENTRY_B);

        assertFalse(unarchived, "tenant-A user must not be able to unarchive tenant-B's entry");
        verify(entryRepository).findByTenantIdAndId(TENANT_A, ENTRY_B);
        verify(entryRepository, never()).save(any(Entry.class));
    }

    @Test
    void crossTenant_sameUserId_isStillRejected() {
        // Defence-in-depth: even if userId happened to collide across
        // tenants (e.g. a moderator account or future shared identity
        // store), the tenant scope alone must block the operation.
        world.put(key(TENANT_B, "entry-b-owned-by-A"),
                makeEntry(TENANT_B, "entry-b-owned-by-A", USER_A));

        boolean updated = uploadService.updateEntryStatus(
                TENANT_A, USER_A, "entry-b-owned-by-A",
                new UpdateEntryStatusRequest("IN_REVIEW"));

        assertFalse(updated,
                "ownership match alone must not bypass tenant scoping");
        verify(entryRepository).findByTenantIdAndId(TENANT_A, "entry-b-owned-by-A");
    }

    // ───────────────────────── helpers ────────────────────────

    private static String key(String tenantId, String entryId) {
        return tenantId + "|" + entryId;
    }

    private static Entry makeEntry(String tenantId, String entryId, String userId) {
        Entry e = new Entry();
        e.setId(entryId);
        e.setTenantId(tenantId);
        e.setUserId(userId);
        e.setAuthorUsername("creator-" + userId);
        e.setTitle("Entry " + entryId);
        e.setType(EntryType.VIDEO);
        e.setStatus(EntryStatus.PUBLISHED);
        e.setPaid(false);
        return e;
    }
}
