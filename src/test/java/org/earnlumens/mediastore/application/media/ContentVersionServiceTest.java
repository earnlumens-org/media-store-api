package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.model.Asset;
import org.earnlumens.mediastore.domain.media.model.AssetStatus;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.EntryVersion;
import org.earnlumens.mediastore.domain.media.model.MediaKind;
import org.earnlumens.mediastore.domain.media.model.PendingVersion;
import org.earnlumens.mediastore.domain.media.repository.AssetRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * N9 acceptance for the pending-version slot: staging never touches the live
 * fields; approval projects and records versions; rejection changes nothing.
 */
class ContentVersionServiceTest {

    private static final String TENANT = "t1";

    private EntryRepository entryRepository;
    private AssetRepository assetRepository;
    private ThumbnailJobService thumbnailJobService;
    private ContentVersionService service;

    @BeforeEach
    void setUp() {
        entryRepository = mock(EntryRepository.class);
        assetRepository = mock(AssetRepository.class);
        thumbnailJobService = mock(ThumbnailJobService.class);
        when(entryRepository.save(any(Entry.class))).thenAnswer(inv -> inv.getArgument(0));
        Asset full = new Asset();
        full.setId("asset-full-1");
        full.setKind(MediaKind.FULL);
        full.setStatus(AssetStatus.READY);
        full.setCreatedAt(LocalDateTime.now().minusDays(1));
        when(assetRepository.findByTenantIdAndEntryId(any(), any())).thenReturn(List.of(full));
        service = new ContentVersionService(entryRepository, assetRepository, thumbnailJobService);
    }

    private Entry published() {
        Entry e = new Entry();
        e.setId("e1");
        e.setTenantId(TENANT);
        e.setUserId("creator");
        e.setType(EntryType.IMAGE);
        e.setStatus(EntryStatus.PUBLISHED);
        e.setTitle("Approved title");
        e.setDescription("Approved description");
        e.setTags(List.of("a"));
        e.setThumbnailR2Key("public/media/e1/thumbnail/old.jpg");
        e.setThumbnailVariantsPrefix("public/media/e1/thumbnail/variants");
        e.setHlsR2Prefix(null);
        e.setPublishedAt(LocalDateTime.now().minusDays(10));
        e.setCreatedAt(LocalDateTime.now().minusDays(11));
        return e;
    }

    @Test
    void stagedStatusesAreExactlyPublishedUnlistedArchived() {
        for (EntryStatus s : EntryStatus.values()) {
            Entry e = published();
            e.setStatus(s);
            boolean expected = s == EntryStatus.PUBLISHED || s == EntryStatus.UNLISTED || s == EntryStatus.ARCHIVED;
            assertThat(ContentVersionService.usesPendingVersion(e)).as(s.name()).isEqualTo(expected);
        }
    }

    @Test
    void stagingKeepsLiveFieldsAndComputesChangeSummary() {
        Entry e = published();
        PendingVersion slot = service.slotFor(e);
        slot.setTitle("New title");
        slot.setThumbnailR2Key("public/media/e1/thumbnail/new.jpg");

        assertThat(service.finalizeStaging(e, slot)).isTrue();

        assertThat(e.getTitle()).isEqualTo("Approved title");
        assertThat(e.getThumbnailR2Key()).isEqualTo("public/media/e1/thumbnail/old.jpg");
        assertThat(e.getStatus()).isEqualTo(EntryStatus.PUBLISHED);
        assertThat(e.getPendingVersion()).isNotNull();
        assertThat(e.getPendingVersion().getChangeSummary())
                .containsExactly(ContentVersionService.PART_TITLE, ContentVersionService.PART_THUMBNAIL);
        assertThat(e.getPendingVersion().getSubmittedAt()).isNotNull();
        assertThat(ContentVersionService.effectiveTitle(e)).isEqualTo("New title");
        assertThat(ContentVersionService.effectiveDescription(e)).isEqualTo("Approved description");
    }

    @Test
    void secondEditBuildsOnTheSameSingleSlot() {
        Entry e = published();
        PendingVersion first = service.slotFor(e);
        first.setTitle("New title");
        service.finalizeStaging(e, first);

        PendingVersion second = service.slotFor(e);
        assertThat(second).isSameAs(e.getPendingVersion());
        second.setDescription("New description");
        service.finalizeStaging(e, second);

        assertThat(e.getPendingVersion().getTitle()).isEqualTo("New title");
        assertThat(e.getPendingVersion().getDescription()).isEqualTo("New description");
        assertThat(e.getPendingVersion().getChangeSummary())
                .containsExactly(ContentVersionService.PART_TITLE, ContentVersionService.PART_DESCRIPTION);
    }

    @Test
    void editThatMatchesLiveContentDropsTheSlot() {
        Entry e = published();
        PendingVersion slot = service.slotFor(e);
        slot.setTitle("Approved title");
        assertThat(service.finalizeStaging(e, slot)).isFalse();
        assertThat(e.getPendingVersion()).isNull();
    }

    @Test
    void approvalProjectsSlotRecordsV1AndV2AndClearsSlot() {
        Entry e = published();
        PendingVersion slot = service.slotFor(e);
        slot.setTitle("New title");
        slot.setThumbnailR2Key("public/media/e1/thumbnail/new.jpg");
        slot.setCreatorNote("Better cover");
        service.finalizeStaging(e, slot);

        Entry saved = service.applyPendingVersion(TENANT, e);

        assertThat(saved.getTitle()).isEqualTo("New title");
        assertThat(saved.getThumbnailR2Key()).isEqualTo("public/media/e1/thumbnail/new.jpg");
        assertThat(saved.getThumbnailVariantsPrefix()).isNull();
        assertThat(saved.getDescription()).isEqualTo("Approved description");
        assertThat(saved.getStatus()).isEqualTo(EntryStatus.PUBLISHED);
        assertThat(saved.getPendingVersion()).isNull();
        assertThat(saved.currentVersionOrOne()).isEqualTo(2);
        assertThat(saved.getVersions()).hasSize(2);

        EntryVersion v1 = saved.getVersions().get(0);
        assertThat(v1.getVersion()).isEqualTo(1);
        assertThat(v1.getTitle()).isEqualTo("Approved title");
        assertThat(v1.getThumbnailR2Key()).isEqualTo("public/media/e1/thumbnail/old.jpg");
        assertThat(v1.getApprovedAt()).isEqualTo(e.getPublishedAt());
        assertThat(v1.getAssetId()).isEqualTo("asset-full-1");

        EntryVersion v2 = saved.getVersions().get(1);
        assertThat(v2.getVersion()).isEqualTo(2);
        assertThat(v2.getTitle()).isEqualTo("New title");
        assertThat(v2.getChangeSummary()).containsExactly("TITLE", "THUMBNAIL");
        assertThat(v2.getCreatorNote()).isEqualTo("Better cover");

        verify(thumbnailJobService).enqueueForEntry(TENANT, saved);
    }

    @Test
    void approvalOfTextOnlyEditDoesNotRegenerateThumbnails() {
        Entry e = published();
        PendingVersion slot = service.slotFor(e);
        slot.setDescription("New description");
        service.finalizeStaging(e, slot);

        service.applyPendingVersion(TENANT, e);

        verify(thumbnailJobService, never()).enqueueForEntry(any(), any());
        assertThat(e.getThumbnailVariantsPrefix()).isEqualTo("public/media/e1/thumbnail/variants");
    }

    @Test
    void thirdApprovalAppendsV3WithoutRebuildingV1() {
        Entry e = published();
        PendingVersion s1 = service.slotFor(e);
        s1.setTitle("v2 title");
        service.finalizeStaging(e, s1);
        service.applyPendingVersion(TENANT, e);

        PendingVersion s2 = service.slotFor(e);
        s2.setTitle("v3 title");
        service.finalizeStaging(e, s2);
        service.applyPendingVersion(TENANT, e);

        assertThat(e.currentVersionOrOne()).isEqualTo(3);
        assertThat(e.getVersions()).extracting(EntryVersion::getVersion).containsExactly(1, 2, 3);
        assertThat(e.getVersions()).extracting(EntryVersion::getTitle)
                .containsExactly("Approved title", "v2 title", "v3 title");
    }

    @Test
    void rejectionDiscardsSlotLeavesEverythingVisibleIdentical() {
        Entry e = published();
        PendingVersion slot = service.slotFor(e);
        slot.setTitle("Offensive title");
        slot.setThumbnailR2Key("public/media/e1/thumbnail/bad.jpg");
        service.finalizeStaging(e, slot);

        Entry saved = service.rejectPendingVersion(TENANT, e, "Not allowed");

        assertThat(saved.getPendingVersion()).isNull();
        assertThat(saved.getModerationFeedback()).isEqualTo("Not allowed");
        assertThat(saved.getStatus()).isEqualTo(EntryStatus.PUBLISHED);
        assertThat(saved.getTitle()).isEqualTo("Approved title");
        assertThat(saved.getThumbnailR2Key()).isEqualTo("public/media/e1/thumbnail/old.jpg");
        assertThat(saved.getVersions()).isEmpty();
        assertThat(saved.getCurrentVersion()).isNull();
        verify(thumbnailJobService, never()).enqueueForEntry(any(), any());
    }

    @Test
    void applyAndRejectAreNoOpsWithoutSlot() {
        Entry e = published();
        assertThat(service.applyPendingVersion(TENANT, e)).isSameAs(e);
        assertThat(service.rejectPendingVersion(TENANT, e, "x")).isSameAs(e);
        verify(entryRepository, never()).save(any());
    }

    // ─── N8-lite: purchasedVersion(entry, rightStart) (S-07 / S-01) ─────

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 0, 0);

    private Entry withVersions(int current, LocalDateTime... approvedAt) {
        Entry e = published();
        List<EntryVersion> versions = new java.util.ArrayList<>();
        for (int i = 0; i < approvedAt.length; i++) {
            EntryVersion v = new EntryVersion();
            v.setVersion(i + 1);
            v.setApprovedAt(approvedAt[i]);
            v.setTitle("title v" + (i + 1));
            versions.add(v);
        }
        e.setVersions(versions);
        e.setCurrentVersion(current);
        return e;
    }

    @Test
    void purchasedVersion_entryWithoutVersions_isLiveWithoutAnyComputation() {
        Entry e = published();
        assertThat(ContentVersionService.purchasedVersion(e, T0)).isNull();
        assertThat(ContentVersionService.purchasedVersion(e, null)).isNull();
    }

    @Test
    void purchasedVersion_rightBeforeSecondApproval_isV1() {
        Entry e = withVersions(2, T0, T0.plusDays(10));
        EntryVersion v = ContentVersionService.purchasedVersion(e, T0.plusDays(5));
        assertThat(v).isNotNull();
        assertThat(v.getVersion()).isEqualTo(1);
        assertThat(v.getTitle()).isEqualTo("title v1");
    }

    @Test
    void purchasedVersion_rightAfterLatestApproval_isLive() {
        Entry e = withVersions(2, T0, T0.plusDays(10));
        assertThat(ContentVersionService.purchasedVersion(e, T0.plusDays(11))).isNull();
    }

    @Test
    void purchasedVersion_rightExactlyAtApproval_takesThatVersion() {
        Entry e = withVersions(3, T0, T0.plusDays(10), T0.plusDays(20));
        assertThat(ContentVersionService.purchasedVersion(e, T0.plusDays(10)).getVersion()).isEqualTo(2);
    }

    @Test
    void purchasedVersion_tenLaterVersions_buyerKeepsTheirs() {
        LocalDateTime[] dates = new LocalDateTime[11];
        for (int i = 0; i < 11; i++) dates[i] = T0.plusDays(i * 10L);
        Entry e = withVersions(11, dates);
        assertThat(ContentVersionService.purchasedVersion(e, T0.plusDays(1)).getVersion()).isEqualTo(1);
        assertThat(ContentVersionService.purchasedVersion(e, T0.plusDays(45)).getVersion()).isEqualTo(5);
    }

    @Test
    void purchasedVersion_unknownRightStartOrRightBeforeV1_fallsBackToV1() {
        Entry e = withVersions(2, T0, T0.plusDays(10));
        assertThat(ContentVersionService.purchasedVersion(e, null).getVersion()).isEqualTo(1);
        assertThat(ContentVersionService.purchasedVersion(e, T0.minusDays(1)).getVersion()).isEqualTo(1);
    }
}
