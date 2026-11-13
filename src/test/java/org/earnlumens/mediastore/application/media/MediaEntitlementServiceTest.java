package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.response.MediaEntitlementResponse;
import org.earnlumens.mediastore.domain.media.model.*;
import org.earnlumens.mediastore.domain.media.repository.AssetRepository;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.media.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
    private CollectionRepository collectionRepository;
    private OrderRepository orderRepository;
    private MediaEntitlementService service;

    @BeforeEach
    void setUp() {
        entryRepository = mock(EntryRepository.class);
        entitlementRepository = mock(EntitlementRepository.class);
        assetRepository = mock(AssetRepository.class);
        collectionRepository = mock(CollectionRepository.class);
        orderRepository = mock(OrderRepository.class);
        // Default: no parent collections for entry (collection-level access fallback returns empty)
        when(collectionRepository.findByTenantIdAndStatusAndItemsEntryId(any(), any(), any()))
                .thenReturn(List.of());
        // Default: no entitlements (overridden per test)
        when(entitlementRepository.findByTenantIdAndUserIdAndEntryIdAndStatus(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(entitlementRepository.findByTenantIdAndUserIdAndCollectionIdsAndStatus(any(), any(), any(), any()))
                .thenReturn(List.of());
        service = new MediaEntitlementService(
                entryRepository, assetRepository,
                new ContentAccessPolicy(entitlementRepository, collectionRepository, orderRepository));
    }

    /** Stubs an ACTIVE PURCHASE entitlement backed by a confirmed COMPLETED order. */
    private void stubActivePurchaseEntitlement(String userId) {
        String orderId = "order-001";
        Entitlement ent = new Entitlement();
        ent.setId("ent-001");
        ent.setTenantId(TENANT);
        ent.setUserId(userId);
        ent.setEntryId(ENTRY_ID);
        ent.setTargetType(TargetType.ENTRY);
        ent.setGrantType(GrantType.PURCHASE);
        ent.setOrderId(orderId);
        ent.setStatus(EntitlementStatus.ACTIVE);

        Order order = new Order();
        order.setId(orderId);
        order.setTenantId(TENANT);
        order.setUserId(userId);
        order.setStatus(OrderStatus.COMPLETED);
        order.setStellarTxHash("abc123txhash");

        when(entitlementRepository.findByTenantIdAndUserIdAndEntryIdAndStatus(
                TENANT, userId, ENTRY_ID, EntitlementStatus.ACTIVE))
                .thenReturn(Optional.of(ent));
        when(orderRepository.findByTenantIdAndId(TENANT, orderId))
                .thenReturn(Optional.of(order));
    }

    private Entry paidEntry() {
        Entry e = new Entry();
        e.setId(ENTRY_ID);
        e.setTenantId(TENANT);
        e.setUserId(OWNER_ID);
        e.setTitle("Test Video");
        e.setType(EntryType.VIDEO);
        e.setStatus(EntryStatus.PUBLISHED);
        e.setVisibility(MediaVisibility.PRIVATE);
        e.setPaid(true);
        return e;
    }

    private Entry freeEntry() {
        Entry e = paidEntry();
        e.setPaid(false);
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
                .thenReturn(Optional.of(paidEntry()));
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
                .thenReturn(Optional.of(paidEntry()));
        stubActivePurchaseEntitlement(BUYER_ID);
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
                .thenReturn(Optional.of(paidEntry()));

        Optional<MediaEntitlementResponse> result =
                service.checkEntitlement(TENANT, BUYER_ID, ENTRY_ID);

        assertTrue(result.isEmpty());
        verify(entitlementRepository, times(1))
                .findByTenantIdAndUserIdAndEntryIdAndStatus(
                        TENANT, BUYER_ID, ENTRY_ID, EntitlementStatus.ACTIVE);
    }

    // ─── PUBLIC entry via /media is still protected ───────────

    @Test
    void paidPublicEntry_requestedViaMedia_isDenied_forNonOwnerWithoutEntitlement() {
        Entry publicEntry = paidEntry();
        publicEntry.setVisibility(MediaVisibility.PUBLIC);

        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(publicEntry));

        Optional<MediaEntitlementResponse> result =
                service.checkEntitlement(TENANT, STRANGER_ID, ENTRY_ID);

        assertTrue(result.isEmpty());
    }

    // ─── Stranger without entitlement → denied ────────────────

    @Test
    void stranger_withoutEntitlement_isDenied() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(paidEntry()));

        Optional<MediaEntitlementResponse> result =
                service.checkEntitlement(TENANT, STRANGER_ID, ENTRY_ID);

        assertTrue(result.isEmpty());
        verify(entitlementRepository, times(1))
                .findByTenantIdAndUserIdAndEntryIdAndStatus(
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
                .thenReturn(Optional.of(paidEntry()));
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
                .thenReturn(Optional.of(paidEntry()));
        when(assetRepository.findByTenantIdAndEntryIdAndKindAndStatus(
                TENANT, ENTRY_ID, MediaKind.FULL, AssetStatus.READY))
                .thenReturn(Optional.of(zipAsset));

        MediaEntitlementResponse resp = service.checkEntitlement(TENANT, OWNER_ID, ENTRY_ID).orElseThrow();

        assertTrue(resp.contentDisposition().startsWith("attachment"));
        assertTrue(resp.contentDisposition().contains("archive.zip"));
    }

    // ─── Owner allowed but no READY FULL asset (non-RESOURCE) → empty ──

    @Test
    void owner_allowed_butNoReadyAsset_returnsEmpty() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(paidEntry()));   // VIDEO type
        when(assetRepository.findByTenantIdAndEntryIdAndKindAndStatus(
                TENANT, ENTRY_ID, MediaKind.FULL, AssetStatus.READY))
                .thenReturn(Optional.empty());

        Optional<MediaEntitlementResponse> result =
                service.checkEntitlement(TENANT, OWNER_ID, ENTRY_ID);

        assertTrue(result.isEmpty());
    }

    // ─── Text-only RESOURCE entry without FULL asset → allowed ──

    @Test
    void textOnlyResource_owner_isAllowed_withoutFullAsset() {
        Entry resource = paidEntry();
        resource.setType(EntryType.RESOURCE);

        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(resource));
        when(assetRepository.findByTenantIdAndEntryIdAndKindAndStatus(
                TENANT, ENTRY_ID, MediaKind.FULL, AssetStatus.READY))
                .thenReturn(Optional.empty());

        Optional<MediaEntitlementResponse> result =
                service.checkEntitlement(TENANT, OWNER_ID, ENTRY_ID);

        assertTrue(result.isPresent());
        assertTrue(result.get().allowed());
        assertNull(result.get().r2Key());
        verifyNoInteractions(entitlementRepository);
    }

    @Test
    void textOnlyResource_buyer_isAllowed_withActiveEntitlement() {
        Entry resource = paidEntry();
        resource.setType(EntryType.RESOURCE);

        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(resource));
        stubActivePurchaseEntitlement(BUYER_ID);
        when(assetRepository.findByTenantIdAndEntryIdAndKindAndStatus(
                TENANT, ENTRY_ID, MediaKind.FULL, AssetStatus.READY))
                .thenReturn(Optional.empty());

        Optional<MediaEntitlementResponse> result =
                service.checkEntitlement(TENANT, BUYER_ID, ENTRY_ID);

        assertTrue(result.isPresent());
        assertTrue(result.get().allowed());
        assertNull(result.get().r2Key());
    }

    @Test
    void textOnlyResource_stranger_isDenied_withoutEntitlement() {
        Entry resource = paidEntry();
        resource.setType(EntryType.RESOURCE);

        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(resource));

        Optional<MediaEntitlementResponse> result =
                service.checkEntitlement(TENANT, STRANGER_ID, ENTRY_ID);

        assertTrue(result.isEmpty());
    }

    // ─── Free content (isPaid=false) accessible to anyone ─────

    @Test
    void freeContent_isAccessibleToStranger_withoutEntitlement() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(freeEntry()));
        configureFullAsset();

        Optional<MediaEntitlementResponse> result =
                service.checkEntitlement(TENANT, STRANGER_ID, ENTRY_ID);

        assertTrue(result.isPresent());
        assertTrue(result.get().allowed());
        // No entitlement lookup necessary for free content
        verifyNoInteractions(entitlementRepository);
    }

    @Test
    void freeContent_isAccessibleToOwner() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(freeEntry()));
        configureFullAsset();

        Optional<MediaEntitlementResponse> result =
                service.checkEntitlement(TENANT, OWNER_ID, ENTRY_ID);

        assertTrue(result.isPresent());
        assertTrue(result.get().allowed());
        verifyNoInteractions(entitlementRepository);
    }

    // ─── Unauthenticated (null userId) ────────────────────────

    @Test
    void unauthenticated_canAccessFreeContent() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(freeEntry()));
        configureFullAsset();

        Optional<MediaEntitlementResponse> result =
                service.checkEntitlement(TENANT, null, ENTRY_ID);

        assertTrue(result.isPresent());
        assertTrue(result.get().allowed());
        verifyNoInteractions(entitlementRepository);
    }

    @Test
    void unauthenticated_isDenied_forPaidContent() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(paidEntry()));

        Optional<MediaEntitlementResponse> result =
                service.checkEntitlement(TENANT, null, ENTRY_ID);

        assertTrue(result.isEmpty());
        verifyNoInteractions(entitlementRepository);
    }

    // ─── N8-lite: the buyer is served the version they acquired ────

    private static final java.time.LocalDateTime T0 = java.time.LocalDateTime.of(2026, 1, 1, 0, 0);

    private Entry versionedVideo() {
        Entry e = paidEntry();
        e.setHlsReady(true);
        e.setHlsR2Prefix("public/media/entry-abc/hls-v2");
        EntryVersion v1 = new EntryVersion();
        v1.setVersion(1); v1.setApprovedAt(T0); v1.setAssetId("asset-v1"); v1.setHlsR2Prefix("public/media/entry-abc/hls-v1");
        EntryVersion v2 = new EntryVersion();
        v2.setVersion(2); v2.setApprovedAt(T0.plusDays(10)); v2.setAssetId("asset-v2"); v2.setHlsR2Prefix("public/media/entry-abc/hls-v2");
        e.setVersions(new java.util.ArrayList<>(List.of(v1, v2)));
        e.setCurrentVersion(2);
        return e;
    }

    private Asset full(String id, String r2Key, java.time.LocalDateTime createdAt) {
        Asset a = fullAsset();
        a.setId(id);
        a.setR2Key(r2Key);
        a.setCreatedAt(createdAt);
        return a;
    }

    private void stubVersionedAssets() {
        Asset v1 = full("asset-v1", "private/media/entry-abc/full/v1.mp4", T0.minusDays(1));
        Asset v2 = full("asset-v2", "private/media/entry-abc/full/v2.mp4", T0.plusDays(9));
        when(assetRepository.findByTenantIdAndEntryId(TENANT, ENTRY_ID)).thenReturn(List.of(v1, v2));
        when(assetRepository.findByTenantIdAndEntryIdAndKindAndStatus(TENANT, ENTRY_ID, MediaKind.FULL, AssetStatus.READY))
                .thenReturn(Optional.of(v2)); // "latest READY FULL" (H8)
    }

    @Test
    void n8_buyerOfV1_getsV1AssetAndHls_whileOwnerGetsV2() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID)).thenReturn(Optional.of(versionedVideo()));
        stubActivePurchaseEntitlement(BUYER_ID);
        entitlementRepository.findByTenantIdAndUserIdAndEntryIdAndStatus(TENANT, BUYER_ID, ENTRY_ID, EntitlementStatus.ACTIVE)
                .get().setGrantedAt(T0.plusDays(5));
        stubVersionedAssets();

        MediaEntitlementResponse buyer = service.checkEntitlement(TENANT, BUYER_ID, ENTRY_ID).orElseThrow();
        assertEquals("private/media/entry-abc/full/v1.mp4", buyer.r2Key());
        assertEquals("public/media/entry-abc/hls-v1", buyer.hlsR2Prefix());

        MediaEntitlementResponse owner = service.checkEntitlement(TENANT, OWNER_ID, ENTRY_ID).orElseThrow();
        assertEquals("private/media/entry-abc/full/v2.mp4", owner.r2Key());
        assertEquals("public/media/entry-abc/hls-v2", owner.hlsR2Prefix());
    }

    @Test
    void n8_buyerAfterV2_getsLiveV2() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID)).thenReturn(Optional.of(versionedVideo()));
        stubActivePurchaseEntitlement(BUYER_ID);
        entitlementRepository.findByTenantIdAndUserIdAndEntryIdAndStatus(TENANT, BUYER_ID, ENTRY_ID, EntitlementStatus.ACTIVE)
                .get().setGrantedAt(T0.plusDays(15));
        stubVersionedAssets();

        MediaEntitlementResponse buyer = service.checkEntitlement(TENANT, BUYER_ID, ENTRY_ID).orElseThrow();
        assertEquals("private/media/entry-abc/full/v2.mp4", buyer.r2Key());
    }

    @Test
    void n8_versionPointerWithoutReadyAsset_fallsBackToLatestReadyFull() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID)).thenReturn(Optional.of(versionedVideo()));
        stubActivePurchaseEntitlement(BUYER_ID);
        entitlementRepository.findByTenantIdAndUserIdAndEntryIdAndStatus(TENANT, BUYER_ID, ENTRY_ID, EntitlementStatus.ACTIVE)
                .get().setGrantedAt(T0.plusDays(5));
        Asset v2 = full("asset-v2", "private/media/entry-abc/full/v2.mp4", T0.plusDays(9));
        when(assetRepository.findByTenantIdAndEntryId(TENANT, ENTRY_ID)).thenReturn(List.of(v2)); // v1 asset gone
        when(assetRepository.findByTenantIdAndEntryIdAndKindAndStatus(TENANT, ENTRY_ID, MediaKind.FULL, AssetStatus.READY))
                .thenReturn(Optional.of(v2));

        MediaEntitlementResponse buyer = service.checkEntitlement(TENANT, BUYER_ID, ENTRY_ID).orElseThrow();
        assertEquals("private/media/entry-abc/full/v2.mp4", buyer.r2Key());
        assertEquals("public/media/entry-abc/hls-v1", buyer.hlsR2Prefix()); // version's own prefix still wins
    }

    @Test
    void n8_unversionedEntry_neverListsAssets_s01() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID)).thenReturn(Optional.of(paidEntry()));
        stubActivePurchaseEntitlement(BUYER_ID);
        configureFullAsset();

        assertTrue(service.checkEntitlement(TENANT, BUYER_ID, ENTRY_ID).isPresent());
        verify(assetRepository, never()).findByTenantIdAndEntryId(any(), any());
    }
}
