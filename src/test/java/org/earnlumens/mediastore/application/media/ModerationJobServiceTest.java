package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.ModerationDecision;
import org.earnlumens.mediastore.domain.media.model.ModerationJob;
import org.earnlumens.mediastore.domain.media.model.ModerationJobStatus;
import org.earnlumens.mediastore.domain.media.port.ModerationDispatchPort;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.media.repository.ModerationJobRepository;
import org.earnlumens.mediastore.infrastructure.config.ModerationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ModerationJobService}.
 */
class ModerationJobServiceTest {

    private static final String TENANT = "earnlumens";

    private ModerationJobRepository jobRepository;
    private EntryRepository entryRepository;
    private ModerationConfig config;
    private ModerationDispatchPort dispatchPort;
    private TranscodingJobService transcodingJobService;
    private ModerationJobService service;

    @BeforeEach
    void setUp() {
        jobRepository = mock(ModerationJobRepository.class);
        entryRepository = mock(EntryRepository.class);
        dispatchPort = mock(ModerationDispatchPort.class);
        transcodingJobService = mock(TranscodingJobService.class);
        config = new ModerationConfig();
        config.setMaxRetries(2);
        config.setHeartbeatTimeoutSeconds(120);
        config.setStaleBatchSize(10);
        config.setDispatchBatchSize(5);
        service = new ModerationJobService(jobRepository, entryRepository, config, dispatchPort, transcodingJobService);

        when(jobRepository.save(any(ModerationJob.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private ModerationJob pendingJob() {
        ModerationJob job = new ModerationJob();
        job.setId("mod-1");
        job.setTenantId(TENANT);
        job.setEntryId("entry-1");
        job.setSourceR2Key("private/media/entry-1/full/video.mp4");
        job.setEntryType(EntryType.VIDEO);
        job.setEntryTitle("Finance 101");
        job.setStatus(ModerationJobStatus.PENDING);
        job.setRetryCount(0);
        job.setMaxRetries(2);
        return job;
    }

    private ModerationJob staleJob(ModerationJobStatus status, int retryCount, int maxRetries) {
        ModerationJob job = pendingJob();
        job.setStatus(status);
        job.setRetryCount(retryCount);
        job.setMaxRetries(maxRetries);
        job.setLastHeartbeat(LocalDateTime.now().minusMinutes(10));
        job.setDispatchedAt(LocalDateTime.now().minusMinutes(15));
        return job;
    }

    private Entry testEntry(EntryType type) {
        Entry entry = new Entry();
        entry.setId("entry-1");
        entry.setTenantId(TENANT);
        entry.setUserId("user-1");
        entry.setTitle("Finance 101");
        entry.setType(type);
        entry.setStatus(EntryStatus.IN_REVIEW);
        return entry;
    }

    // ─── createJob ──────────────────────────────────────────────

    @Nested
    class CreateJob {

        @Test
        void savesAndReturnsJob() {
            ModerationJob job = pendingJob();
            ModerationJob saved = service.createJob(job);

            assertNotNull(saved);
            assertEquals("mod-1", saved.getId());
            verify(jobRepository).save(job);
        }
    }

    // ─── dispatchPendingJobs ────────────────────────────────────

    @Nested
    class DispatchPendingJobs {

        @Test
        void noPendingJobs_returnsZero() {
            when(jobRepository.findAllByStatus(ModerationJobStatus.PENDING, 5))
                    .thenReturn(Collections.emptyList());

            assertEquals(0, service.dispatchPendingJobs());
            verify(dispatchPort, never()).dispatch(any());
        }

        @Test
        void oneJob_dispatchesAndUpdatesStatus() {
            ModerationJob job = pendingJob();
            when(jobRepository.findAllByStatus(ModerationJobStatus.PENDING, 5))
                    .thenReturn(List.of(job));

            int dispatched = service.dispatchPendingJobs();

            assertEquals(1, dispatched);
            assertEquals(ModerationJobStatus.DISPATCHED, job.getStatus());
            assertNotNull(job.getDispatchedAt());
            assertNotNull(job.getLastHeartbeat());
            verify(dispatchPort).dispatch(job);
            verify(jobRepository).save(job); // dispatch update
        }

        @Test
        void dispatchFailure_doesNotStopOtherJobs() {
            ModerationJob failJob = pendingJob();
            failJob.setId("mod-fail");
            ModerationJob okJob = pendingJob();
            okJob.setId("mod-ok");

            when(jobRepository.findAllByStatus(ModerationJobStatus.PENDING, 5))
                    .thenReturn(List.of(failJob, okJob));
            doThrow(new RuntimeException("Cloud Run error")).when(dispatchPort).dispatch(failJob);

            int dispatched = service.dispatchPendingJobs();

            assertEquals(1, dispatched);
        }
    }

    // ─── heartbeat ──────────────────────────────────────────────

    @Nested
    class Heartbeat {

        @Test
        void firstHeartbeat_transitionsDispatchedToProcessing() {
            ModerationJob job = staleJob(ModerationJobStatus.DISPATCHED, 0, 2);
            when(jobRepository.findByTenantIdAndId(TENANT, "mod-1"))
                    .thenReturn(Optional.of(job));

            service.heartbeat("mod-1", TENANT);

            assertEquals(ModerationJobStatus.PROCESSING, job.getStatus());
            assertNotNull(job.getProcessingStartedAt());
            verify(jobRepository).save(job);
        }

        @Test
        void subsequentHeartbeat_updatesTimestamp() {
            ModerationJob job = staleJob(ModerationJobStatus.PROCESSING, 0, 2);
            LocalDateTime oldHeartbeat = job.getLastHeartbeat();
            when(jobRepository.findByTenantIdAndId(TENANT, "mod-1"))
                    .thenReturn(Optional.of(job));

            service.heartbeat("mod-1", TENANT);

            assertEquals(ModerationJobStatus.PROCESSING, job.getStatus());
            assertNotNull(job.getLastHeartbeat());
        }

        @Test
        void jobNotFound_noException() {
            when(jobRepository.findByTenantIdAndId(TENANT, "no-id"))
                    .thenReturn(Optional.empty());

            assertDoesNotThrow(() -> service.heartbeat("no-id", TENANT));
        }
    }

    // ─── recoverStaleJobs ──────────────────────────────────────

    @Nested
    class RecoverStaleJobs {

        @Test
        void noStaleJobs_returnsZero() {
            when(jobRepository.findAllStaleJobs(any(), anyInt()))
                    .thenReturn(Collections.emptyList());

            assertEquals(0, service.recoverStaleJobs());
            verify(jobRepository, never()).save(any());
        }

        @Test
        void staleJob_withinRetryLimit_isRetried() {
            ModerationJob job = staleJob(ModerationJobStatus.DISPATCHED, 0, 2);
            when(jobRepository.findAllStaleJobs(any(), anyInt()))
                    .thenReturn(List.of(job));

            int recovered = service.recoverStaleJobs();

            assertEquals(1, recovered);
            assertEquals(ModerationJobStatus.PENDING, job.getStatus());
            assertEquals(1, job.getRetryCount());
            assertNull(job.getLastHeartbeat());
            assertNull(job.getDispatchedAt());
            assertNull(job.getProcessingStartedAt());
        }

        @Test
        void staleJob_retriesExhausted_isMarkedDead() {
            ModerationJob job = staleJob(ModerationJobStatus.PROCESSING, 2, 2);
            when(jobRepository.findAllStaleJobs(any(), anyInt()))
                    .thenReturn(List.of(job));

            int recovered = service.recoverStaleJobs();

            assertEquals(1, recovered);
            assertEquals(ModerationJobStatus.DEAD, job.getStatus());
            assertNotNull(job.getCompletedAt());
            assertTrue(job.getErrorMessage().contains("Max retries exhausted"));
        }

        @Test
        void multipleStaleJobs_allProcessed() {
            ModerationJob retryable = staleJob(ModerationJobStatus.DISPATCHED, 0, 2);
            retryable.setId("mod-retry");
            ModerationJob dead = staleJob(ModerationJobStatus.PROCESSING, 2, 2);
            dead.setId("mod-dead");

            when(jobRepository.findAllStaleJobs(any(), anyInt()))
                    .thenReturn(List.of(retryable, dead));

            int recovered = service.recoverStaleJobs();

            assertEquals(2, recovered);
            assertEquals(ModerationJobStatus.PENDING, retryable.getStatus());
            assertEquals(ModerationJobStatus.DEAD, dead.getStatus());
        }
    }

    // ─── completeJob (APPROVE) ─────────────────────────────────

    @Nested
    class CompleteJobApprove {

        @Test
        void approve_completesJobAndApprovesEntry() {
            ModerationJob job = staleJob(ModerationJobStatus.PROCESSING, 0, 2);
            Entry entry = testEntry(EntryType.IMAGE);
            when(jobRepository.findByTenantIdAndId(TENANT, "mod-1")).thenReturn(Optional.of(job));
            when(entryRepository.findByTenantIdAndId(TENANT, "entry-1")).thenReturn(Optional.of(entry));
            when(entryRepository.save(any(Entry.class))).thenAnswer(inv -> inv.getArgument(0));

            Optional<ModerationJob> result = service.completeJob(
                    TENANT, "mod-1", "APPROVE", 0.95,
                    List.of("NONE"), "Content approved", "GEMINI");

            assertTrue(result.isPresent());
            assertEquals(ModerationJobStatus.COMPLETED, result.get().getStatus());
            assertEquals(ModerationDecision.APPROVE, result.get().getDecision());
            assertEquals(0.95, result.get().getConfidence());
            assertEquals("GEMINI", result.get().getDecidingStep());
            assertNotNull(result.get().getCompletedAt());
            assertEquals(EntryStatus.APPROVED, entry.getStatus());
        }

        @Test
        void approve_video_logsTranscodingNote() {
            ModerationJob job = staleJob(ModerationJobStatus.PROCESSING, 0, 2);
            Entry entry = testEntry(EntryType.VIDEO);
            when(jobRepository.findByTenantIdAndId(TENANT, "mod-1")).thenReturn(Optional.of(job));
            when(entryRepository.findByTenantIdAndId(TENANT, "entry-1")).thenReturn(Optional.of(entry));
            when(entryRepository.save(any(Entry.class))).thenAnswer(inv -> inv.getArgument(0));
            when(transcodingJobService.findLatestByTenantIdAndEntryId(anyString(), anyString()))
                    .thenReturn(Optional.empty());

            Optional<ModerationJob> result = service.completeJob(
                    TENANT, "mod-1", "APPROVE", 0.92,
                    List.of("NONE"), "Approved", "GEMINI");

            assertTrue(result.isPresent());
            assertEquals(EntryStatus.APPROVED, entry.getStatus());
        }
    }

    // ─── completeJob (REJECT) ──────────────────────────────────

    @Nested
    class CompleteJobReject {

        @Test
        void reject_completesJobAndRejectsEntry() {
            ModerationJob job = staleJob(ModerationJobStatus.PROCESSING, 0, 2);
            Entry entry = testEntry(EntryType.VIDEO);
            when(jobRepository.findByTenantIdAndId(TENANT, "mod-1")).thenReturn(Optional.of(job));
            when(entryRepository.findByTenantIdAndId(TENANT, "entry-1")).thenReturn(Optional.of(entry));
            when(entryRepository.save(any(Entry.class))).thenAnswer(inv -> inv.getArgument(0));

            Optional<ModerationJob> result = service.completeJob(
                    TENANT, "mod-1", "REJECT", 0.98,
                    List.of("NSFW", "VIOLENCE"), "NSFW content detected", "NUDENET");

            assertTrue(result.isPresent());
            assertEquals(ModerationDecision.REJECT, result.get().getDecision());
            assertEquals(EntryStatus.REJECTED, entry.getStatus());
            assertEquals(List.of("NSFW", "VIOLENCE"), result.get().getCategoriesDetected());
        }
    }

    // ─── completeJob (MANUAL_QUEUE) ────────────────────────────

    @Nested
    class CompleteJobManualQueue {

        @Test
        void manualQueue_doesNotChangeEntryStatus() {
            ModerationJob job = staleJob(ModerationJobStatus.PROCESSING, 0, 2);
            when(jobRepository.findByTenantIdAndId(TENANT, "mod-1")).thenReturn(Optional.of(job));

            Optional<ModerationJob> result = service.completeJob(
                    TENANT, "mod-1", "MANUAL_QUEUE", 0.5,
                    List.of("MISLEADING_FINANCIAL"), "Uncertain", "GEMINI");

            assertTrue(result.isPresent());
            assertEquals(ModerationDecision.MANUAL_QUEUE, result.get().getDecision());
            // Entry should NOT be updated for manual queue
            verify(entryRepository, never()).save(any());
        }
    }

    // ─── completeJob (invalid decision) ────────────────────────

    @Nested
    class CompleteJobInvalidDecision {

        @Test
        void invalidDecision_defaultsToManualQueue() {
            ModerationJob job = staleJob(ModerationJobStatus.PROCESSING, 0, 2);
            when(jobRepository.findByTenantIdAndId(TENANT, "mod-1")).thenReturn(Optional.of(job));

            Optional<ModerationJob> result = service.completeJob(
                    TENANT, "mod-1", "BANANA", 0.5,
                    List.of(), "bug", "GEMINI");

            assertTrue(result.isPresent());
            assertEquals(ModerationDecision.MANUAL_QUEUE, result.get().getDecision());
        }
    }

    // ─── completeJob (not found) ───────────────────────────────

    @Test
    void completeJob_notFound_returnsEmpty() {
        when(jobRepository.findByTenantIdAndId(TENANT, "no-id")).thenReturn(Optional.empty());

        Optional<ModerationJob> result = service.completeJob(
                TENANT, "no-id", "APPROVE", 0.9, List.of(), "ok", "GEMINI");

        assertTrue(result.isEmpty());
    }

    // ─── failJob ────────────────────────────────────────────────

    @Nested
    class FailJob {

        @Test
        void withinRetryLimit_retriesJob() {
            ModerationJob job = staleJob(ModerationJobStatus.PROCESSING, 0, 2);
            when(jobRepository.findByTenantIdAndId(TENANT, "mod-1")).thenReturn(Optional.of(job));

            Optional<ModerationJob> result = service.failJob(TENANT, "mod-1", "NudeNet crashed");

            assertTrue(result.isPresent());
            assertEquals(ModerationJobStatus.PENDING, result.get().getStatus());
            assertEquals(1, result.get().getRetryCount());
        }

        @Test
        void retriesExhausted_killsJob() {
            ModerationJob job = staleJob(ModerationJobStatus.PROCESSING, 2, 2);
            when(jobRepository.findByTenantIdAndId(TENANT, "mod-1")).thenReturn(Optional.of(job));

            Optional<ModerationJob> result = service.failJob(TENANT, "mod-1", "Gemini timeout");

            assertTrue(result.isPresent());
            assertEquals(ModerationJobStatus.DEAD, result.get().getStatus());
        }

        @Test
        void jobNotFound_returnsEmpty() {
            when(jobRepository.findByTenantIdAndId(TENANT, "no-id")).thenReturn(Optional.empty());

            assertTrue(service.failJob(TENANT, "no-id", "err").isEmpty());
        }
    }

    // ─── Config accessor ────────────────────────────────────────

    @Test
    void getMaxRetries_returnsConfigValue() {
        assertEquals(2, service.getMaxRetries());
        config.setMaxRetries(5);
        assertEquals(5, service.getMaxRetries());
    }
}
