package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.response.MediaEntitlementResponse;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;
import org.earnlumens.mediastore.domain.media.model.MediaKind;
import org.earnlumens.mediastore.domain.media.model.MediaVisibility;
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
 */
class MediaEntitlementServiceTest {

    private static final String TENANT = "earnlumens";
    private static final String OWNER_ID = "owner-user-001";
    private static final String BUYER_ID = "buyer-user-002";
    private static final String STRANGER_ID = "stranger-003";
    private static final String ENTRY_ID = "entry-abc";

    private EntryRepository entryRepository;
    private EntitlementRepository entitlementRepository;
    private MediaEntitlementService service;

    @BeforeEach
    void setUp() {
        entryRepository = mock(EntryRepository.class);
        entitlementRepository = mock(EntitlementRepository.class);
        service = new MediaEntitlementService(entryRepository, entitlementRepository);
    }

    private Entry privateEntry() {
        Entry e = new Entry();
        e.setId(ENTRY_ID);
        e.setTenantId(TENANT);
        e.setUserId(OWNER_ID);
        e.setR2Key("private/media/entry-abc/video.mp4");
        e.setContentType("video/mp4");
        e.setFileName("video.mp4");
        e.setKind(MediaKind.FULL);
        e.setVisibility(MediaVisibility.PRIVATE);
        return e;
    }

    // ─── Owner always has access ──────────────────────────────

    @Test
    void owner_isAlwaysAllowed_withoutEntitlement() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(privateEntry()));

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
        // Repository only checks ACTIVE status; mock returns false
        // (represents REVOKED, EXPIRED, or no record at all)
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

        // /media/<entryId> is always protected regardless of visibility;
        // public assets must be served via /public/<r2Key> only.
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

    // ─── Content-Disposition logic ────────────────────────────

    @Test
    void videoEntry_hasInlineDisposition() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(privateEntry()));

        MediaEntitlementResponse resp = service.checkEntitlement(TENANT, OWNER_ID, ENTRY_ID).orElseThrow();

        assertTrue(resp.contentDisposition().startsWith("inline"));
        assertTrue(resp.contentDisposition().contains("video.mp4"));
    }

    @Test
    void zipEntry_hasAttachmentDisposition() {
        Entry entry = privateEntry();
        entry.setContentType("application/zip");
        entry.setFileName("archive.zip");
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(entry));

        MediaEntitlementResponse resp = service.checkEntitlement(TENANT, OWNER_ID, ENTRY_ID).orElseThrow();

        assertTrue(resp.contentDisposition().startsWith("attachment"));
        assertTrue(resp.contentDisposition().contains("archive.zip"));
    }
}
