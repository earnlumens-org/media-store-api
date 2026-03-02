package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.response.FavoritePageResponse;
import org.earnlumens.mediastore.domain.media.model.*;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.media.repository.FavoriteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FavoriteService}.
 *
 * Verifies:
 *   - toggle adds when not present, removes when present
 *   - isFavorite delegates correctly
 *   - listFavorites hydrates entries and collections, cleans orphans
 *   - all operations are tenant-isolated
 */
class FavoriteServiceTest {

    private static final String TENANT_A = "earnlumens";
    private static final String TENANT_B = "other-tenant";
    private static final String USER_ID = "user-001";
    private static final String OTHER_USER = "user-002";
    private static final String ENTRY_ID = "entry-abc";
    private static final String COLLECTION_ID = "coll-xyz";

    private FavoriteRepository favoriteRepository;
    private EntryRepository entryRepository;
    private CollectionRepository collectionRepository;
    private EntitlementRepository entitlementRepository;
    private FavoriteService service;

    @BeforeEach
    void setUp() {
        favoriteRepository = mock(FavoriteRepository.class);
        entryRepository = mock(EntryRepository.class);
        collectionRepository = mock(CollectionRepository.class);
        entitlementRepository = mock(EntitlementRepository.class);
        service = new FavoriteService(favoriteRepository, entryRepository, collectionRepository, entitlementRepository);
    }

    // ── Helpers ───────────────────────────────────────────────

    private Favorite existingFavorite(String tenantId, String userId, String itemId, FavoriteItemType type) {
        Favorite f = new Favorite();
        f.setId("fav-" + itemId);
        f.setTenantId(tenantId);
        f.setUserId(userId);
        f.setItemId(itemId);
        f.setItemType(type);
        f.setAddedAt(LocalDateTime.of(2026, 1, 15, 10, 30));
        return f;
    }

    private Entry sampleEntry() {
        Entry e = new Entry();
        e.setId(ENTRY_ID);
        e.setTenantId(TENANT_A);
        e.setUserId("creator-001");
        e.setAuthorUsername("creator");
        e.setTitle("Test Video");
        e.setType(EntryType.VIDEO);
        e.setStatus(EntryStatus.PUBLISHED);
        e.setThumbnailR2Key("public/thumb/entry-abc.jpg");
        e.setDurationSec(120);
        e.setPaid(true);
        e.setPublishedAt(LocalDateTime.of(2026, 1, 1, 12, 0));
        return e;
    }

    private Collection sampleCollection() {
        Collection c = new Collection();
        c.setId(COLLECTION_ID);
        c.setTenantId(TENANT_A);
        c.setUserId("creator-001");
        c.setTitle("My Playlist");
        c.setCollectionType(CollectionType.LIST);
        c.setCoverR2Key("public/cover/coll-xyz.jpg");
        c.setItems(List.of());
        c.setPublishedAt(LocalDateTime.of(2026, 2, 1, 8, 0));
        return c;
    }

    // ── toggleFavorite ────────────────────────────────────────

    @Test
    void toggle_addsWhenNotPresent() {
        when(favoriteRepository.findByTenantIdAndUserIdAndItemId(TENANT_A, USER_ID, ENTRY_ID))
                .thenReturn(Optional.empty());
        when(favoriteRepository.save(any(Favorite.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean result = service.toggleFavorite(TENANT_A, USER_ID, ENTRY_ID, FavoriteItemType.ENTRY);

        assertTrue(result, "Should return true when adding");
        verify(favoriteRepository).save(argThat(fav ->
                TENANT_A.equals(fav.getTenantId())
                        && USER_ID.equals(fav.getUserId())
                        && ENTRY_ID.equals(fav.getItemId())
                        && fav.getItemType() == FavoriteItemType.ENTRY
        ));
        verify(favoriteRepository, never()).deleteById(any());
    }

    @Test
    void toggle_removesWhenAlreadyPresent() {
        Favorite existing = existingFavorite(TENANT_A, USER_ID, ENTRY_ID, FavoriteItemType.ENTRY);
        when(favoriteRepository.findByTenantIdAndUserIdAndItemId(TENANT_A, USER_ID, ENTRY_ID))
                .thenReturn(Optional.of(existing));

        boolean result = service.toggleFavorite(TENANT_A, USER_ID, ENTRY_ID, FavoriteItemType.ENTRY);

        assertFalse(result, "Should return false when removing");
        verify(favoriteRepository).deleteById("fav-" + ENTRY_ID);
        verify(favoriteRepository, never()).save(any());
    }

    // ── Tenant isolation on toggle ────────────────────────────

    @Test
    void toggle_tenantIsolated_differentTenantDoesNotSeeOthersFavorite() {
        // Favorite exists in TENANT_A
        Favorite existing = existingFavorite(TENANT_A, USER_ID, ENTRY_ID, FavoriteItemType.ENTRY);
        when(favoriteRepository.findByTenantIdAndUserIdAndItemId(TENANT_A, USER_ID, ENTRY_ID))
                .thenReturn(Optional.of(existing));

        // But query for TENANT_B returns nothing
        when(favoriteRepository.findByTenantIdAndUserIdAndItemId(TENANT_B, USER_ID, ENTRY_ID))
                .thenReturn(Optional.empty());
        when(favoriteRepository.save(any(Favorite.class))).thenAnswer(inv -> inv.getArgument(0));

        // Toggle on TENANT_B should ADD (not remove the one from TENANT_A)
        boolean result = service.toggleFavorite(TENANT_B, USER_ID, ENTRY_ID, FavoriteItemType.ENTRY);

        assertTrue(result, "Should add in TENANT_B since it doesn't exist there");
        verify(favoriteRepository).save(argThat(fav -> TENANT_B.equals(fav.getTenantId())));
        verify(favoriteRepository, never()).deleteById(any());
    }

    // ── isFavorite ────────────────────────────────────────────

    @Test
    void isFavorite_returnsTrue_whenExists() {
        when(favoriteRepository.existsByTenantIdAndUserIdAndItemId(TENANT_A, USER_ID, ENTRY_ID))
                .thenReturn(true);

        assertTrue(service.isFavorite(TENANT_A, USER_ID, ENTRY_ID));
    }

    @Test
    void isFavorite_returnsFalse_whenNotExists() {
        when(favoriteRepository.existsByTenantIdAndUserIdAndItemId(TENANT_A, USER_ID, ENTRY_ID))
                .thenReturn(false);

        assertFalse(service.isFavorite(TENANT_A, USER_ID, ENTRY_ID));
    }

    @Test
    void isFavorite_tenantIsolated() {
        when(favoriteRepository.existsByTenantIdAndUserIdAndItemId(TENANT_A, USER_ID, ENTRY_ID))
                .thenReturn(true);
        when(favoriteRepository.existsByTenantIdAndUserIdAndItemId(TENANT_B, USER_ID, ENTRY_ID))
                .thenReturn(false);

        assertTrue(service.isFavorite(TENANT_A, USER_ID, ENTRY_ID));
        assertFalse(service.isFavorite(TENANT_B, USER_ID, ENTRY_ID));
    }

    // ── listFavorites — empty ─────────────────────────────────

    @Test
    void list_returnsEmptyPage_whenNoFavorites() {
        Page<Favorite> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 24), 0);
        when(favoriteRepository.findByTenantIdAndUserId(eq(TENANT_A), eq(USER_ID), any()))
                .thenReturn(emptyPage);

        FavoritePageResponse response = service.listFavorites(TENANT_A, USER_ID, 0, 24);

        assertTrue(response.content().isEmpty());
        assertEquals(0, response.totalElements());
        verifyNoInteractions(entryRepository);
        verifyNoInteractions(collectionRepository);
    }

    // ── listFavorites — hydrates entries and collections ──────

    @Test
    void list_hydratesEntryAndCollectionFavorites() {
        Favorite favEntry = existingFavorite(TENANT_A, USER_ID, ENTRY_ID, FavoriteItemType.ENTRY);
        Favorite favColl = existingFavorite(TENANT_A, USER_ID, COLLECTION_ID, FavoriteItemType.COLLECTION);

        Page<Favorite> page = new PageImpl<>(List.of(favEntry, favColl), PageRequest.of(0, 24), 2);
        when(favoriteRepository.findByTenantIdAndUserId(eq(TENANT_A), eq(USER_ID), any()))
                .thenReturn(page);

        when(entryRepository.findByTenantIdAndIdIn(TENANT_A, List.of(ENTRY_ID)))
                .thenReturn(List.of(sampleEntry()));
        when(collectionRepository.findByTenantIdAndIdIn(TENANT_A, List.of(COLLECTION_ID)))
                .thenReturn(List.of(sampleCollection()));
        when(entitlementRepository.findEntitledEntryIds(eq(TENANT_A), eq(USER_ID), eq(List.of(ENTRY_ID)), any()))
                .thenReturn(Set.of());

        FavoritePageResponse response = service.listFavorites(TENANT_A, USER_ID, 0, 24);

        assertEquals(2, response.content().size());
        assertEquals("entry", response.content().get(0).itemType());
        assertEquals("Test Video", response.content().get(0).title());
        assertEquals("collection", response.content().get(1).itemType());
        assertEquals("My Playlist", response.content().get(1).title());
    }

    // ── listFavorites — orphan cleanup ────────────────────────

    @Test
    void list_removesOrphansWhenEntryNoLongerExists() {
        Favorite favEntry = existingFavorite(TENANT_A, USER_ID, ENTRY_ID, FavoriteItemType.ENTRY);

        Page<Favorite> page = new PageImpl<>(List.of(favEntry), PageRequest.of(0, 24), 1);
        when(favoriteRepository.findByTenantIdAndUserId(eq(TENANT_A), eq(USER_ID), any()))
                .thenReturn(page);

        // Entry no longer exists — batch-load returns empty
        when(entryRepository.findByTenantIdAndIdIn(TENANT_A, List.of(ENTRY_ID)))
                .thenReturn(List.of());

        FavoritePageResponse response = service.listFavorites(TENANT_A, USER_ID, 0, 24);

        assertTrue(response.content().isEmpty());
        // The orphan should be deleted
        verify(favoriteRepository).deleteById("fav-" + ENTRY_ID);
    }

    @Test
    void list_removesOrphansWhenCollectionNoLongerExists() {
        Favorite favColl = existingFavorite(TENANT_A, USER_ID, COLLECTION_ID, FavoriteItemType.COLLECTION);

        Page<Favorite> page = new PageImpl<>(List.of(favColl), PageRequest.of(0, 24), 1);
        when(favoriteRepository.findByTenantIdAndUserId(eq(TENANT_A), eq(USER_ID), any()))
                .thenReturn(page);

        when(collectionRepository.findByTenantIdAndIdIn(TENANT_A, List.of(COLLECTION_ID)))
                .thenReturn(List.of());

        FavoritePageResponse response = service.listFavorites(TENANT_A, USER_ID, 0, 24);

        assertTrue(response.content().isEmpty());
        verify(favoriteRepository).deleteById("fav-" + COLLECTION_ID);
    }

    // ── listFavorites — tenant-isolated batch loads ───────────

    @Test
    void list_batchLoadUsesCorrectTenant() {
        Favorite fav = existingFavorite(TENANT_A, USER_ID, ENTRY_ID, FavoriteItemType.ENTRY);
        Page<Favorite> page = new PageImpl<>(List.of(fav), PageRequest.of(0, 24), 1);

        when(favoriteRepository.findByTenantIdAndUserId(eq(TENANT_A), eq(USER_ID), any()))
                .thenReturn(page);
        when(entryRepository.findByTenantIdAndIdIn(TENANT_A, List.of(ENTRY_ID)))
                .thenReturn(List.of(sampleEntry()));
        when(entitlementRepository.findEntitledEntryIds(eq(TENANT_A), eq(USER_ID), eq(List.of(ENTRY_ID)), any()))
                .thenReturn(Set.of());

        service.listFavorites(TENANT_A, USER_ID, 0, 24);

        // Verify the batch-load used TENANT_A, never TENANT_B
        verify(entryRepository).findByTenantIdAndIdIn(eq(TENANT_A), eq(List.of(ENTRY_ID)));
        verify(entryRepository, never()).findByTenantIdAndIdIn(eq(TENANT_B), any());
    }

    // ── User isolation — different users, same tenant ─────────

    @Test
    void list_userIsolated_differentUsersGetDifferentResults() {
        Favorite favUser1 = existingFavorite(TENANT_A, USER_ID, ENTRY_ID, FavoriteItemType.ENTRY);
        Page<Favorite> pageUser1 = new PageImpl<>(List.of(favUser1), PageRequest.of(0, 24), 1);

        when(favoriteRepository.findByTenantIdAndUserId(eq(TENANT_A), eq(USER_ID), any()))
                .thenReturn(pageUser1);

        Page<Favorite> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 24), 0);
        when(favoriteRepository.findByTenantIdAndUserId(eq(TENANT_A), eq(OTHER_USER), any()))
                .thenReturn(emptyPage);

        when(entryRepository.findByTenantIdAndIdIn(TENANT_A, List.of(ENTRY_ID)))
                .thenReturn(List.of(sampleEntry()));
        when(entitlementRepository.findEntitledEntryIds(eq(TENANT_A), eq(USER_ID), eq(List.of(ENTRY_ID)), any()))
                .thenReturn(Set.of());

        FavoritePageResponse user1Response = service.listFavorites(TENANT_A, USER_ID, 0, 24);
        FavoritePageResponse user2Response = service.listFavorites(TENANT_A, OTHER_USER, 0, 24);

        assertEquals(1, user1Response.content().size());
        assertEquals(0, user2Response.content().size());
    }

    // ── Response mapping correctness ──────────────────────────

    @Test
    void list_entryResponseFields_areMappedCorrectly() {
        Favorite fav = existingFavorite(TENANT_A, USER_ID, ENTRY_ID, FavoriteItemType.ENTRY);
        Page<Favorite> page = new PageImpl<>(List.of(fav), PageRequest.of(0, 24), 1);

        when(favoriteRepository.findByTenantIdAndUserId(eq(TENANT_A), eq(USER_ID), any()))
                .thenReturn(page);
        when(entryRepository.findByTenantIdAndIdIn(TENANT_A, List.of(ENTRY_ID)))
                .thenReturn(List.of(sampleEntry()));
        // No entitlement → paid entry should be locked
        when(entitlementRepository.findEntitledEntryIds(eq(TENANT_A), eq(USER_ID), eq(List.of(ENTRY_ID)), any()))
                .thenReturn(Set.of());

        FavoritePageResponse response = service.listFavorites(TENANT_A, USER_ID, 0, 24);
        var item = response.content().get(0);

        assertEquals("fav-" + ENTRY_ID, item.id());
        assertEquals(ENTRY_ID, item.itemId());
        assertEquals("entry", item.itemType());
        assertEquals("Test Video", item.title());
        assertEquals("creator", item.authorName());
        assertEquals("public/thumb/entry-abc.jpg", item.thumbnailUrl());
        assertEquals(120, item.durationSec());
        assertTrue(item.locked()); // isPaid && no entitlement → locked
        assertFalse(item.unlocked()); // locked → not unlocked
        assertNull(item.coverUrl());
        assertNull(item.collectionType());
        assertNull(item.itemsCount());
    }

    @Test
    void list_collectionResponseFields_areMappedCorrectly() {
        Favorite fav = existingFavorite(TENANT_A, USER_ID, COLLECTION_ID, FavoriteItemType.COLLECTION);
        Page<Favorite> page = new PageImpl<>(List.of(fav), PageRequest.of(0, 24), 1);

        when(favoriteRepository.findByTenantIdAndUserId(eq(TENANT_A), eq(USER_ID), any()))
                .thenReturn(page);
        when(collectionRepository.findByTenantIdAndIdIn(TENANT_A, List.of(COLLECTION_ID)))
                .thenReturn(List.of(sampleCollection()));

        FavoritePageResponse response = service.listFavorites(TENANT_A, USER_ID, 0, 24);
        var item = response.content().get(0);

        assertEquals("fav-" + COLLECTION_ID, item.id());
        assertEquals(COLLECTION_ID, item.itemId());
        assertEquals("collection", item.itemType());
        assertEquals("My Playlist", item.title());
        assertEquals("public/cover/coll-xyz.jpg", item.coverUrl());
        assertEquals("list", item.collectionType());
        assertEquals(0, item.itemsCount());
        assertFalse(item.locked());
        assertFalse(item.unlocked()); // collections are never paid → never unlocked
        assertNull(item.thumbnailUrl());
        assertNull(item.durationSec());
    }

    // ── Entitlement-based locked field ─────────────────────────

    @Test
    void list_paidEntryWithEntitlement_lockedIsFalse() {
        Favorite fav = existingFavorite(TENANT_A, USER_ID, ENTRY_ID, FavoriteItemType.ENTRY);
        Page<Favorite> page = new PageImpl<>(List.of(fav), PageRequest.of(0, 24), 1);

        when(favoriteRepository.findByTenantIdAndUserId(eq(TENANT_A), eq(USER_ID), any()))
                .thenReturn(page);
        when(entryRepository.findByTenantIdAndIdIn(TENANT_A, List.of(ENTRY_ID)))
                .thenReturn(List.of(sampleEntry())); // isPaid = true
        // User HAS an active entitlement → should be unlocked
        when(entitlementRepository.findEntitledEntryIds(eq(TENANT_A), eq(USER_ID), eq(List.of(ENTRY_ID)), any()))
                .thenReturn(Set.of(ENTRY_ID));

        FavoritePageResponse response = service.listFavorites(TENANT_A, USER_ID, 0, 24);
        var item = response.content().get(0);

        assertFalse(item.locked(), "Paid entry WITH entitlement should NOT be locked");
        assertTrue(item.unlocked(), "Paid entry WITH entitlement should be unlocked");
    }

    @Test
    void list_paidEntryWithoutEntitlement_lockedIsTrue() {
        Favorite fav = existingFavorite(TENANT_A, USER_ID, ENTRY_ID, FavoriteItemType.ENTRY);
        Page<Favorite> page = new PageImpl<>(List.of(fav), PageRequest.of(0, 24), 1);

        when(favoriteRepository.findByTenantIdAndUserId(eq(TENANT_A), eq(USER_ID), any()))
                .thenReturn(page);
        when(entryRepository.findByTenantIdAndIdIn(TENANT_A, List.of(ENTRY_ID)))
                .thenReturn(List.of(sampleEntry())); // isPaid = true
        // User does NOT have an entitlement → should be locked
        when(entitlementRepository.findEntitledEntryIds(eq(TENANT_A), eq(USER_ID), eq(List.of(ENTRY_ID)), any()))
                .thenReturn(Set.of());

        FavoritePageResponse response = service.listFavorites(TENANT_A, USER_ID, 0, 24);
        var item = response.content().get(0);

        assertTrue(item.locked(), "Paid entry WITHOUT entitlement should be locked");
        assertFalse(item.unlocked(), "Paid entry WITHOUT entitlement should NOT be unlocked");
    }

    @Test
    void list_freeEntry_lockedIsFalse() {
        Entry freeEntry = sampleEntry();
        freeEntry.setPaid(false);

        Favorite fav = existingFavorite(TENANT_A, USER_ID, ENTRY_ID, FavoriteItemType.ENTRY);
        Page<Favorite> page = new PageImpl<>(List.of(fav), PageRequest.of(0, 24), 1);

        when(favoriteRepository.findByTenantIdAndUserId(eq(TENANT_A), eq(USER_ID), any()))
                .thenReturn(page);
        when(entryRepository.findByTenantIdAndIdIn(TENANT_A, List.of(ENTRY_ID)))
                .thenReturn(List.of(freeEntry));
        // Free entry → no paid entry IDs → entitlement check returns empty
        when(entitlementRepository.findEntitledEntryIds(eq(TENANT_A), eq(USER_ID), eq(List.of()), any()))
                .thenReturn(Set.of());

        FavoritePageResponse response = service.listFavorites(TENANT_A, USER_ID, 0, 24);
        var item = response.content().get(0);

        assertFalse(item.locked(), "Free entry should never be locked");
        assertFalse(item.unlocked(), "Free entry should never be unlocked");
    }

    @Test
    void list_collectionsAreNeverLocked() {
        Favorite fav = existingFavorite(TENANT_A, USER_ID, COLLECTION_ID, FavoriteItemType.COLLECTION);
        Page<Favorite> page = new PageImpl<>(List.of(fav), PageRequest.of(0, 24), 1);

        when(favoriteRepository.findByTenantIdAndUserId(eq(TENANT_A), eq(USER_ID), any()))
                .thenReturn(page);
        when(collectionRepository.findByTenantIdAndIdIn(TENANT_A, List.of(COLLECTION_ID)))
                .thenReturn(List.of(sampleCollection()));

        FavoritePageResponse response = service.listFavorites(TENANT_A, USER_ID, 0, 24);
        var item = response.content().get(0);

        assertFalse(item.locked(), "Collections should never be locked");
        assertFalse(item.unlocked(), "Collections should never be unlocked");
    }

    // ── Owner content is never locked ─────────────────────────

    @Test
    void list_ownPaidEntry_neverLocked() {
        // User is the owner of this paid entry (userId matches entry.userId)
        String ownerUserId = "creator-001"; // matches sampleEntry().getUserId()
        Favorite fav = existingFavorite(TENANT_A, ownerUserId, ENTRY_ID, FavoriteItemType.ENTRY);
        Page<Favorite> page = new PageImpl<>(List.of(fav), PageRequest.of(0, 24), 1);

        when(favoriteRepository.findByTenantIdAndUserId(eq(TENANT_A), eq(ownerUserId), any()))
                .thenReturn(page);
        when(entryRepository.findByTenantIdAndIdIn(TENANT_A, List.of(ENTRY_ID)))
                .thenReturn(List.of(sampleEntry())); // isPaid=true, userId="creator-001"
        // Owner's entries are excluded from entitlement check, so empty list is sent
        when(entitlementRepository.findEntitledEntryIds(eq(TENANT_A), eq(ownerUserId), eq(List.of()), any()))
                .thenReturn(Set.of());

        FavoritePageResponse response = service.listFavorites(TENANT_A, ownerUserId, 0, 24);
        var item = response.content().get(0);

        assertFalse(item.locked(), "Owner's own paid content should never be locked");
        assertTrue(item.unlocked(), "Owner's own paid content should show as unlocked");
    }

    // ── Pagination metadata ───────────────────────────────────

    @Test
    void list_returnsPaginationMetadata() {
        Page<Favorite> page = new PageImpl<>(List.of(), PageRequest.of(2, 10), 25);
        when(favoriteRepository.findByTenantIdAndUserId(eq(TENANT_A), eq(USER_ID), any()))
                .thenReturn(page);

        FavoritePageResponse response = service.listFavorites(TENANT_A, USER_ID, 2, 10);

        assertEquals(2, response.page());
        assertEquals(10, response.size());
        assertEquals(25, response.totalElements());
        assertEquals(3, response.totalPages());
    }
}
