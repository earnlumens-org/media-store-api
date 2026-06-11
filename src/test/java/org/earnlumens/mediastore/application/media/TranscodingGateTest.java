package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.application.user.UserBadgeService;
import org.earnlumens.mediastore.domain.media.dto.request.UpdateEntryMetadataRequest;
import org.earnlumens.mediastore.domain.media.dto.request.UpdateEntryStatusRequest;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.TranscodingJob;
import org.earnlumens.mediastore.domain.media.model.TranscodingJobStatus;
import org.earnlumens.mediastore.domain.media.repository.AssetRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.media.repository.OrderRepository;
import org.earnlumens.mediastore.domain.user.repository.UserRepository;
import org.earnlumens.mediastore.infrastructure.config.PlatformConfig;
import org.earnlumens.mediastore.infrastructure.r2.R2PresignedUrlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies the "no edits / no publish while transcoding" gate.
 * <p>
 * A creator must not be able to mutate the entry's metadata or move it
 * forward in the workflow (DRAFT → IN_REVIEW, APPROVED → PUBLISHED) while
 * the transcoder worker is still writing HLS segments under
 * {@code private/media/{tenantId}/{entryId}/hls}. Allowing mutations
 * mid-encode would race with the callback validation in
 * {@code TranscodingJobService.completeJob} and could surface a partly
 * written stream to viewers.
 * <p>
 * Archive / unarchive / metadata-after-encode are exercised by other
 * tests; this class focuses on the gate itself.
 */
class TranscodingGateTest {

    private static final String TENANT  = "tenant-a";
    private static final String USER    = "user-a";
    private static final String ENTRY   = "entry-a-id";

    private EntryRepository entryRepository;
    private TranscodingJobService transcodingJobService;
    private EntryUploadService uploadService;

    @BeforeEach
    void setUp() {
        entryRepository       = mock(EntryRepository.class);
        transcodingJobService = mock(TranscodingJobService.class);

        uploadService = new EntryUploadService(
                entryRepository,
                mock(AssetRepository.class),
                mock(UserRepository.class),
                mock(OrderRepository.class),
                mock(org.earnlumens.mediastore.domain.media.repository.CollectionRepository.class),
                mock(R2PresignedUrlService.class),
                mock(org.earnlumens.mediastore.infrastructure.r2.R2StorageService.class),
                mock(org.earnlumens.mediastore.domain.media.repository.UploadSessionRepository.class),
                mock(PlatformConfig.class),
                transcodingJobService,
                mock(ModerationJobService.class),
                mock(UserBadgeService.class),
                mock(org.earnlumens.mediastore.application.space.SpaceValidationService.class),
                stellarTransactionServiceMock(),
                /* dailyEntryLimit  */ 20,
                /* maxConcurrentReview */ 10
        );
    }

    private static org.earnlumens.mediastore.application.payment.StellarTransactionService stellarTransactionServiceMock() {
        var stellar = mock(org.earnlumens.mediastore.application.payment.StellarTransactionService.class);
        org.mockito.Mockito.when(stellar.isAccountActive(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        return stellar;
    }

    // ───────────────────────── updateEntryMetadata ────────────

    @Test
    void editMetadata_isBlocked_whileTranscodingProcessing() {
        seedEntry(EntryStatus.APPROVED);
        seedJob(TranscodingJobStatus.PROCESSING);

        UpdateEntryMetadataRequest req = new UpdateEntryMetadataRequest(
                "new-title", null, null, null, null, null, null, null, null);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> uploadService.updateEntryMetadata(TENANT, USER, ENTRY, req));

        assertEquals("TRANSCODING_IN_PROGRESS", ex.getMessage());
        verify(entryRepository, never()).save(any(Entry.class));
    }

    @Test
    void editMetadata_isBlocked_whileTranscodingPending() {
        seedEntry(EntryStatus.DRAFT);
        seedJob(TranscodingJobStatus.PENDING);

        UpdateEntryMetadataRequest req = new UpdateEntryMetadataRequest(
                "new-title", null, null, null, null, null, null, null, null);

        assertThrows(IllegalArgumentException.class,
                () -> uploadService.updateEntryMetadata(TENANT, USER, ENTRY, req));
        verify(entryRepository, never()).save(any(Entry.class));
    }

    @Test
    void editMetadata_isAllowed_whenTranscodingCompleted() {
        seedEntry(EntryStatus.DRAFT);
        seedJob(TranscodingJobStatus.COMPLETED);

        UpdateEntryMetadataRequest req = new UpdateEntryMetadataRequest(
                "new-title", null, null, null, null, null, null, null, null);

        boolean ok = uploadService.updateEntryMetadata(TENANT, USER, ENTRY, req);
        assertTrue(ok);
        verify(entryRepository).save(any(Entry.class));
    }

    @Test
    void editMetadata_isAllowed_forNonVideoEntries() {
        Entry e = makeEntry(EntryStatus.DRAFT);
        e.setType(EntryType.RESOURCE);
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY)).thenReturn(Optional.of(e));
        // No job stub: the gate must short-circuit on entry.type != VIDEO.

        UpdateEntryMetadataRequest req = new UpdateEntryMetadataRequest(
                "new-title", null, null, null, null, null, null, null, null);

        assertTrue(uploadService.updateEntryMetadata(TENANT, USER, ENTRY, req));
        verifyNoInteractions(transcodingJobService);
    }

    // ───────────────────────── updateEntryStatus ──────────────

    @Test
    void submitForReview_isBlocked_whileTranscoding() {
        seedEntry(EntryStatus.DRAFT);
        seedJob(TranscodingJobStatus.PROCESSING);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> uploadService.updateEntryStatus(TENANT, USER, ENTRY,
                        new UpdateEntryStatusRequest("IN_REVIEW")));

        assertEquals("TRANSCODING_IN_PROGRESS", ex.getMessage());
        verify(entryRepository, never()).save(any(Entry.class));
    }

    @Test
    void publish_isBlocked_whileTranscoding() {
        seedEntry(EntryStatus.APPROVED);
        seedJob(TranscodingJobStatus.DISPATCHED);

        assertThrows(IllegalArgumentException.class,
                () -> uploadService.updateEntryStatus(TENANT, USER, ENTRY,
                        new UpdateEntryStatusRequest("PUBLISHED")));
        verify(entryRepository, never()).save(any(Entry.class));
    }

    @Test
    void archive_isAllowed_whileTranscoding() {
        // The creator must always be able to abandon a half-encoded entry.
        seedEntry(EntryStatus.DRAFT);
        seedJob(TranscodingJobStatus.PROCESSING);

        boolean ok = uploadService.updateEntryStatus(TENANT, USER, ENTRY,
                new UpdateEntryStatusRequest("ARCHIVED"));

        assertTrue(ok);
        verify(entryRepository).save(any(Entry.class));
    }

    // ───────────────────────── helpers ────────────────────────

    private void seedEntry(EntryStatus status) {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY))
                .thenReturn(Optional.of(makeEntry(status)));
    }

    private void seedJob(TranscodingJobStatus jobStatus) {
        TranscodingJob job = new TranscodingJob();
        job.setTenantId(TENANT);
        job.setEntryId(ENTRY);
        job.setStatus(jobStatus);
        when(transcodingJobService.findLatestByTenantIdAndEntryId(eq(TENANT), eq(ENTRY)))
                .thenReturn(Optional.of(job));
    }

    private static Entry makeEntry(EntryStatus status) {
        Entry e = new Entry();
        e.setId(ENTRY);
        e.setTenantId(TENANT);
        e.setUserId(USER);
        e.setAuthorUsername("creator-" + USER);
        e.setTitle("Entry " + ENTRY);
        e.setType(EntryType.VIDEO);
        e.setStatus(status);
        e.setPaid(false);
        return e;
    }
}
