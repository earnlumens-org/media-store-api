package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.model.TranscodingJob;
import org.earnlumens.mediastore.domain.media.model.TranscodingJobStatus;
import org.earnlumens.mediastore.domain.media.repository.TranscodingJobRepository;
import org.earnlumens.mediastore.infrastructure.config.TranscodingConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TranscodingJobService}.
 */
class TranscodingJobServiceTest {

    private TranscodingJobRepository jobRepository;
    private TranscodingConfig config;
    private TranscodingJobService service;

    @BeforeEach
    void setUp() {
        jobRepository = mock(TranscodingJobRepository.class);
        config = new TranscodingConfig();
        config.setMaxRetries(3);
        config.setHeartbeatTimeoutSeconds(120);
        config.setStaleBatchSize(50);
        service = new TranscodingJobService(jobRepository, config);

        // Default: save returns the same job
        when(jobRepository.save(any(TranscodingJob.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private TranscodingJob staleJob(TranscodingJobStatus status, int retryCount, int maxRetries) {
        TranscodingJob job = new TranscodingJob();
        job.setId("job-1");
        job.setTenantId("earnlumens");
        job.setEntryId("entry-1");
        job.setAssetId("asset-1");
        job.setSourceR2Key("private/media/entry-1/full/video.mp4");
        job.setStatus(status);
        job.setRetryCount(retryCount);
        job.setMaxRetries(maxRetries);
        job.setLastHeartbeat(LocalDateTime.now().minusMinutes(10));
        job.setDispatchedAt(LocalDateTime.now().minusMinutes(15));
        return job;
    }

    // ─── recoverStaleJobs ──────────────────────────────────────

    @Nested
    class RecoverStaleJobs {

        @Test
        void noStaleJobs_returnsZero() {
            when(jobRepository.findStaleJobs(any(), anyInt()))
                    .thenReturn(Collections.emptyList());

            int recovered = service.recoverStaleJobs();

            assertEquals(0, recovered);
            verify(jobRepository, never()).save(any());
        }

        @Test
        void staleDispatchedJob_withinRetryLimit_isRetried() {
            TranscodingJob job = staleJob(TranscodingJobStatus.DISPATCHED, 0, 3);
            when(jobRepository.findStaleJobs(any(), anyInt()))
                    .thenReturn(List.of(job));

            int recovered = service.recoverStaleJobs();

            assertEquals(1, recovered);
            assertEquals(TranscodingJobStatus.PENDING, job.getStatus());
            assertEquals(1, job.getRetryCount());
            assertNull(job.getLastHeartbeat());
            assertNull(job.getDispatchedAt());
            assertNull(job.getProcessingStartedAt());
            assertNotNull(job.getErrorMessage());
            verify(jobRepository).save(job);
        }

        @Test
        void staleProcessingJob_withinRetryLimit_isRetried() {
            TranscodingJob job = staleJob(TranscodingJobStatus.PROCESSING, 1, 3);
            job.setProcessingStartedAt(LocalDateTime.now().minusMinutes(8));
            when(jobRepository.findStaleJobs(any(), anyInt()))
                    .thenReturn(List.of(job));

            int recovered = service.recoverStaleJobs();

            assertEquals(1, recovered);
            assertEquals(TranscodingJobStatus.PENDING, job.getStatus());
            assertEquals(2, job.getRetryCount());
        }

        @Test
        void staleJob_retriesExhausted_isMarkedDead() {
            TranscodingJob job = staleJob(TranscodingJobStatus.PROCESSING, 3, 3);
            when(jobRepository.findStaleJobs(any(), anyInt()))
                    .thenReturn(List.of(job));

            int recovered = service.recoverStaleJobs();

            assertEquals(1, recovered);
            assertEquals(TranscodingJobStatus.DEAD, job.getStatus());
            assertNotNull(job.getCompletedAt());
            assertTrue(job.getErrorMessage().contains("Max retries exhausted"));
            verify(jobRepository).save(job);
        }

        @Test
        void multipleStaleJobs_allProcessed() {
            TranscodingJob retryable = staleJob(TranscodingJobStatus.DISPATCHED, 0, 3);
            retryable.setId("job-retry");

            TranscodingJob dead = staleJob(TranscodingJobStatus.PROCESSING, 3, 3);
            dead.setId("job-dead");

            when(jobRepository.findStaleJobs(any(), anyInt()))
                    .thenReturn(List.of(retryable, dead));

            int recovered = service.recoverStaleJobs();

            assertEquals(2, recovered);
            assertEquals(TranscodingJobStatus.PENDING, retryable.getStatus());
            assertEquals(TranscodingJobStatus.DEAD, dead.getStatus());
            verify(jobRepository, times(2)).save(any());
        }

        @Test
        void exceptionOnOneJob_doesNotStopOthers() {
            TranscodingJob failing = staleJob(TranscodingJobStatus.DISPATCHED, 0, 3);
            failing.setId("job-fail");

            TranscodingJob healthy = staleJob(TranscodingJobStatus.DISPATCHED, 1, 3);
            healthy.setId("job-ok");

            when(jobRepository.findStaleJobs(any(), anyInt()))
                    .thenReturn(List.of(failing, healthy));

            // First save throws, second succeeds
            when(jobRepository.save(any()))
                    .thenThrow(new RuntimeException("DB timeout"))
                    .thenAnswer(inv -> inv.getArgument(0));

            int recovered = service.recoverStaleJobs();

            // Only the second one recovered successfully
            assertEquals(1, recovered);
        }
    }

    // ─── handleStaleJob ─────────────────────────────────────────

    @Nested
    class HandleStaleJob {

        @Test
        void retryCountBelowMax_retries() {
            TranscodingJob job = staleJob(TranscodingJobStatus.DISPATCHED, 2, 3);

            service.handleStaleJob(job);

            assertEquals(TranscodingJobStatus.PENDING, job.getStatus());
            assertEquals(3, job.getRetryCount());
        }

        @Test
        void retryCountEqualsMax_kills() {
            TranscodingJob job = staleJob(TranscodingJobStatus.PROCESSING, 3, 3);

            service.handleStaleJob(job);

            assertEquals(TranscodingJobStatus.DEAD, job.getStatus());
        }

        @Test
        void retryCountAboveMax_kills() {
            TranscodingJob job = staleJob(TranscodingJobStatus.PROCESSING, 5, 3);

            service.handleStaleJob(job);

            assertEquals(TranscodingJobStatus.DEAD, job.getStatus());
        }
    }

    // ─── retryJob ───────────────────────────────────────────────

    @Nested
    class RetryJob {

        @Test
        void resetsStatusToPendingAndIncrementsRetry() {
            TranscodingJob job = staleJob(TranscodingJobStatus.DISPATCHED, 1, 3);

            service.retryJob(job, "test reason");

            assertEquals(TranscodingJobStatus.PENDING, job.getStatus());
            assertEquals(2, job.getRetryCount());
            assertEquals("test reason", job.getErrorMessage());
            assertNull(job.getLastHeartbeat());
            assertNull(job.getDispatchedAt());
            assertNull(job.getProcessingStartedAt());
            verify(jobRepository).save(job);
        }

        @Test
        void preservesIdentifyingFields() {
            TranscodingJob job = staleJob(TranscodingJobStatus.PROCESSING, 0, 3);

            service.retryJob(job, "crashed");

            assertEquals("job-1", job.getId());
            assertEquals("earnlumens", job.getTenantId());
            assertEquals("entry-1", job.getEntryId());
            assertEquals("asset-1", job.getAssetId());
            assertEquals("private/media/entry-1/full/video.mp4", job.getSourceR2Key());
        }
    }

    // ─── killJob ────────────────────────────────────────────────

    @Nested
    class KillJob {

        @Test
        void setsStatusToDeadWithReason() {
            TranscodingJob job = staleJob(TranscodingJobStatus.PROCESSING, 3, 3);

            service.killJob(job, "too many failures");

            assertEquals(TranscodingJobStatus.DEAD, job.getStatus());
            assertEquals("too many failures", job.getErrorMessage());
            assertNotNull(job.getCompletedAt());
            verify(jobRepository).save(job);
        }

        @Test
        void doesNotResetRetryCount() {
            TranscodingJob job = staleJob(TranscodingJobStatus.DISPATCHED, 3, 3);

            service.killJob(job, "done");

            assertEquals(3, job.getRetryCount());
        }
    }

    // ─── Config accessor ────────────────────────────────────────

    @Test
    void getMaxRetries_returnsConfigValue() {
        assertEquals(3, service.getMaxRetries());

        config.setMaxRetries(5);
        assertEquals(5, service.getMaxRetries());
    }
}
