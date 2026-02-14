package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.response.MediaEntitlementResponse;
import org.earnlumens.mediastore.domain.media.model.*;
import org.earnlumens.mediastore.domain.media.repository.AssetRepository;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MediaEntitlementService}.
 *
 * Verifies the authorization rule:
 *   allowed = (entry.tenantId == resolvedTenantId)
 *             AND (entry.userId == requestUserId OR Entitlement(t,u,e) == ACTIVE)
 *
 * After Step 2: r2Key is resolved from the assets collection (kind=FULL, status=READY).
 */
class MediaEntitlementServiceTest {

    private static final String TENANT = "earnlumens";
    private static final String OWNER_ID = "owner-user-001";
    private static final String BUYER_ID = "buyer-user-002";
    private static final String STRANGER_ID = "stranger-003";
    private static final String ENTRY_ID = "entry-abc";

    private EntryRepository entryRepository;
    private EntitlementRepository entitlementRepository;
    private AssetRepository assetRepository;
    private MediaEntitlementService service;

    @BeforeEach
    void setUp() {
        entryRepository = mock(EntryRepository.class);
        entitlementRepository = mock(EntitlementRepository.class);
        assetRepository = mock(AssetRepository.class);
        service = new MediaEntitlementService(entryRepository, entitlementRepository, assetRepository);
    }

    private Entry privateEntry() {
        Entry e = new Entry();
        e.setId(ENTRY_ID);
        e.setTenantId(TENANT);
        e.setUserId(OWNER_ID);
        e.setTitle("Test Video");
        e.setType(EntryType.VIDEO);
        e.setStatus(EntryStatus.PUBLISHED);
        e.setVisibility(MediaVisibility.PRIVATE);
        return e;
    }

    private Asset fullAsset() {
        Asset a = new Asset();
        a.setId("asset-001");
        a.setTenantId(TENANT);
        a.setEntryId(ENTRY_ID);
        a.setR2Key("private/media/entry-abc/video.mp4");
        a.setContentType("video/mp4");
        a.setFileName("video.mp4");
        a.setKind(MediaKind.FULL);
        a.setStatus(AssetStatus.READY);
        return a;
    }

    private void configureFullAsset() {
        when(assetRepository.findByTenantIdAndEntryIdAndKindAndStatus(
                TENANT, ENTRY_ID, MediaKind.FULL, AssetStatus.READY))
                .thenReturn(Optional.of(fullAsset()));
    }

    // ─── Owner always has access ──────────────────────────────

    @Test
    void owner_isAlwaysAllowed_withoutEntitlement() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(privateEntry()));
        configureFullAsset();

        Optional<MediaEntitlementResponse> result =
                service.checkEntitlement(TENANT, OWNER_ID, ENTRY_ID);

        assertTrue(result.isPresent());
        assertTrue(result.get().allowed());
        assertEquals("private/media/entry-abc/video.mp4", result.get().r2Key());

        // Must NOT check entitlement for owner
        verifyNoInteractions(entitlementRepository);
    }

    // ─── Buyer with ACTIVE entitlement ────────────────────────

    @Test
    void buyer_withActiveEntitlement_isAllowed() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(privateEntry()));
        when(entitlementRepository.existsByTenantIdAndUserIdAndEntryIdAndStatus(
                TENANT, BUYER_ID, ENTRY_ID, EntitlementStatus.ACTIVE))
                .thenReturn(true);
        configureFullAsset();

        Optional<MediaEntitlementResponse> result =
                service.checkEntitlement(TENANT, BUYER_ID, ENTRY_ID);

        assertTrue(result.isPresent());
        MediaEntitlementResponse resp = result.get();
        assertTrue(resp.allowed());
        assertNotNull(resp.r2Key());
        assertNotNull(resp.contentType());
        assertNotNull(resp.fileName());
    }

    // ─── Buyer with non-ACTIVE entitlement → denied ───────────

    @Test
    void buyer_withNonActiveEntitlement_isDenied() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(privateEntry()));
        when(entitlementRepository.existsByTenantIdAndUserIdAndEntryIdAndStatus(
                TENANT, BUYER_ID, ENTRY_ID, EntitlementStatus.ACTIVE))
                .thenReturn(false);

        Optional<MediaEntitlementResponse> result =
                service.checkEntitlement(TENANT, BUYER_ID, ENTRY_ID);

        assertTrue(result.isEmpty());
        verify(entitlementRepository, times(1))
                .existsByTenantIdAndUserIdAndEntryIdAndStatus(
                        TENANT, BUYER_ID, ENTRY_ID, EntitlementStatus.ACTIVE);
    }

    // ─── PUBLIC entry via /media is still protected ───────────

    @Test
    void publicEntry_requestedViaMedia_isDenied_forNonOwnerWithoutEntitlement() {
        Entry publicEntry = privateEntry();
        publicEntry.setVisibility(MediaVisibility.PUBLIC);

        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(publicEntry));
        when(entitlementRepository.existsByTenantIdAndUserIdAndEntryIdAndStatus(
                TENANT, STRANGER_ID, ENTRY_ID, EntitlementStatus.ACTIVE))
                .thenReturn(false);

        Optional<MediaEntitlementResponse> result =
                service.checkEntitlement(TENANT, STRANGER_ID, ENTRY_ID);

        assertTrue(result.isEmpty());
    }

    // ─── Stranger without entitlement → denied ────────────────

    @Test
    void stranger_withoutEntitlement_isDenied() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(privateEntry()));
        when(entitlementRepository.existsByTenantIdAndUserIdAndEntryIdAndStatus(
                TENANT, STRANGER_ID, ENTRY_ID, EntitlementStatus.ACTIVE))
                .thenReturn(false);

        Optional<MediaEntitlementResponse> result =
                service.checkEntitlement(TENANT, STRANGER_ID, ENTRY_ID);

        assertTrue(result.isEmpty());
        verify(entitlementRepository, times(1))
                .existsByTenantIdAndUserIdAndEntryIdAndStatus(
                        TENANT, STRANGER_ID, ENTRY_ID, EntitlementStatus.ACTIVE);
    }

    // ─── Tenant mismatch → entry not found → denied ──────────

    @Test
    void tenantMismatch_entryNotFound_isDenied() {
        String otherTenant = "other-tenant";
        when(entryRepository.findByTenantIdAndId(otherTenant, ENTRY_ID))
                .thenReturn(Optional.empty());

        Optional<MediaEntitlementResponse> result =
                service.checkEntitlement(otherTenant, OWNER_ID, ENTRY_ID);

        assertTrue(result.isEmpty());
        verifyNoInteractions(entitlementRepository);
    }

    // ─── Non-existent entry → denied ──────────────────────────

    @Test
    void nonExistentEntry_isDenied() {
        when(entryRepository.findByTenantIdAndId(TENANT, "no-such-entry"))
                .thenReturn(Optional.empty());

        Optional<MediaEntitlementResponse> result =
                service.checkEntitlement(TENANT, OWNER_ID, "no-such-entry");

        assertTrue(result.isEmpty());
        verifyNoInteractions(entitlementRepository);
    }

    // ─── Content-Disposition logic (now via Asset) ─────────────

    @Test
    void videoAsset_hasInlineDisposition() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(privateEntry()));
        configureFullAsset();

        MediaEntitlementResponse resp = service.checkEntitlement(TENANT, OWNER_ID, ENTRY_ID).orElseThrow();

        assertTrue(resp.contentDisposition().startsWith("inline"));
        assertTrue(resp.contentDisposition().contains("video.mp4"));
    }

    @Test
    void zipAsset_hasAttachmentDisposition() {
        Asset zipAsset = fullAsset();
        zipAsset.setContentType("application/zip");
        zipAsset.setFileName("archive.zip");

        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(privateEntry()));
        when(assetRepository.findByTenantIdAndEntryIdAndKindAndStatus(
                TENANT, ENTRY_ID, MediaKind.FULL, AssetStatus.READY))
                .thenReturn(Optional.of(zipAsset));

        MediaEntitlementResponse resp = service.checkEntitlement(TENANT, OWNER_ID, ENTRY_ID).orElseThrow();

        assertTrue(resp.contentDisposition().startsWith("attachment"));
        assertTrue(resp.contentDisposition().contains("archive.zip"));
    }

    // ─── Owner allowed but no READY FULL asset → empty ────────

    @Test
    void owner_allowed_butNoReadyAsset_returnsEmpty() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(privateEntry()));
        when(assetRepository.findByTenantIdAndEntryIdAndKindAndStatus(
                TENANT, ENTRY_ID, MediaKind.FULL, AssetStatus.READY))
                .thenReturn(Optional.empty());

        Optional<MediaEntitlementResponse> result =
                service.checkEntitlement(TENANT, OWNER_ID, ENTRY_ID);

        assertTrue(result.isEmpty());
    }
}
