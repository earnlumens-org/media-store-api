package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.response.CleanupResult;
import org.earnlumens.mediastore.domain.media.model.Asset;
import org.earnlumens.mediastore.domain.media.model.AssetStatus;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.MediaKind;
import org.earnlumens.mediastore.domain.media.model.MediaVisibility;
import org.earnlumens.mediastore.domain.media.repository.AssetRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DraftCleanupService}.
 */
class DraftCleanupServiceTest {

    private static final String TENANT = "earnlumens";

    private EntryRepository entryRepository;
    private AssetRepository assetRepository;
    private DraftCleanupService service;

    @BeforeEach
    void setUp() {
        entryRepository = mock(EntryRepository.class);
        assetRepository = mock(AssetRepository.class);
        service = new DraftCleanupService(entryRepository, assetRepository);
    }

    private Entry orphanedDraft(String id, EntryType type) {
        Entry e = new Entry();
        e.setId(id);
        e.setTenantId(TENANT);
        e.setUserId("user-1");
        e.setTitle("Orphaned " + id);
        e.setType(type);
        e.setStatus(EntryStatus.DRAFT);
        e.setVisibility(MediaVisibility.PRIVATE);
        e.setCreatedAt(LocalDateTime.now().minusHours(48));
        return e;
    }

    private Asset someAsset(String entryId) {
        Asset a = new Asset();
        a.setId("asset-1");
        a.setTenantId(TENANT);
        a.setEntryId(entryId);
        a.setR2Key("private/media/" + entryId + "/full/file.mp4");
        a.setContentType("video/mp4");
        a.setFileName("file.mp4");
        a.setFileSizeBytes(1024L);
        a.setKind(MediaKind.FULL);
        a.setStatus(AssetStatus.UPLOADED);
        return a;
    }

    // ─── No stale drafts ──────────────────────────────────────

    @Test
    void noStaleDrafts_deletesNothing() {
        when(entryRepository.findByStatusAndCreatedAtBefore(eq(EntryStatus.DRAFT), any()))
                .thenReturn(Collections.emptyList());

        CleanupResult result = service.cleanOrphanedDrafts();

        assertEquals(0, result.deletedCount());
        assertTrue(result.byType().isEmpty());
        verify(entryRepository, never()).deleteById(any());
    }

    // ─── Orphaned drafts with no assets → deleted ─────────────

    @Test
    void orphanedDrafts_noAssets_areDeleted() {
        Entry video = orphanedDraft("entry-1", EntryType.VIDEO);
        Entry image = orphanedDraft("entry-2", EntryType.IMAGE);
        Entry audio = orphanedDraft("entry-3", EntryType.AUDIO);

        when(entryRepository.findByStatusAndCreatedAtBefore(eq(EntryStatus.DRAFT), any()))
                .thenReturn(List.of(video, image, audio));
        when(assetRepository.findByTenantIdAndEntryId(eq(TENANT), any()))
                .thenReturn(Collections.emptyList());

        CleanupResult result = service.cleanOrphanedDrafts();

        assertEquals(3, result.deletedCount());
        assertEquals(1, result.byType().get("VIDEO"));
        assertEquals(1, result.byType().get("IMAGE"));
        assertEquals(1, result.byType().get("AUDIO"));
        verify(entryRepository, times(3)).deleteById(any());
    }

    // ─── Draft with assets → NOT deleted ──────────────────────

    @Test
    void draftWithAssets_isNotDeleted() {
        Entry entry = orphanedDraft("entry-1", EntryType.VIDEO);

        when(entryRepository.findByStatusAndCreatedAtBefore(eq(EntryStatus.DRAFT), any()))
                .thenReturn(List.of(entry));
        when(assetRepository.findByTenantIdAndEntryId(TENANT, "entry-1"))
                .thenReturn(List.of(someAsset("entry-1")));

        CleanupResult result = service.cleanOrphanedDrafts();

        assertEquals(0, result.deletedCount());
        assertTrue(result.byType().isEmpty());
        verify(entryRepository, never()).deleteById(any());
    }

    // ─── Mixed: some with assets, some without ────────────────

    @Test
    void mixedDrafts_onlyOrphansDeleted() {
        Entry orphan = orphanedDraft("entry-1", EntryType.IMAGE);
        Entry withAsset = orphanedDraft("entry-2", EntryType.VIDEO);

        when(entryRepository.findByStatusAndCreatedAtBefore(eq(EntryStatus.DRAFT), any()))
                .thenReturn(List.of(orphan, withAsset));
        when(assetRepository.findByTenantIdAndEntryId(TENANT, "entry-1"))
                .thenReturn(Collections.emptyList());
        when(assetRepository.findByTenantIdAndEntryId(TENANT, "entry-2"))
                .thenReturn(List.of(someAsset("entry-2")));

        CleanupResult result = service.cleanOrphanedDrafts();

        assertEquals(1, result.deletedCount());
        assertEquals(1, result.byType().get("IMAGE"));
        assertNull(result.byType().get("VIDEO"));
        verify(entryRepository).deleteById("entry-1");
        verify(entryRepository, never()).deleteById("entry-2");
    }

    // ─── Multiple orphans of same type → count aggregated ─────

    @Test
    void multipleOrphans_sameType_countsAggregated() {
        Entry img1 = orphanedDraft("entry-1", EntryType.IMAGE);
        Entry img2 = orphanedDraft("entry-2", EntryType.IMAGE);
        Entry img3 = orphanedDraft("entry-3", EntryType.IMAGE);

        when(entryRepository.findByStatusAndCreatedAtBefore(eq(EntryStatus.DRAFT), any()))
                .thenReturn(List.of(img1, img2, img3));
        when(assetRepository.findByTenantIdAndEntryId(eq(TENANT), any()))
                .thenReturn(Collections.emptyList());

        CleanupResult result = service.cleanOrphanedDrafts();

        assertEquals(3, result.deletedCount());
        assertEquals(3, result.byType().get("IMAGE"));
    }

    // ─── Result includes cutoff and duration ──────────────────

    @Test
    void result_includesCutoffAndDuration() {
        when(entryRepository.findByStatusAndCreatedAtBefore(eq(EntryStatus.DRAFT), any()))
                .thenReturn(Collections.emptyList());

        CleanupResult result = service.cleanOrphanedDrafts();

        assertNotNull(result.cutoffTime());
        assertTrue(result.cutoffTime().isBefore(LocalDateTime.now()));
        assertTrue(result.durationMs() >= 0);
    }
}
