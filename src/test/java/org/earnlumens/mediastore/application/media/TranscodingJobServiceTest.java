package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.model.Asset;
import org.earnlumens.mediastore.domain.media.model.AssetStatus;
import org.earnlumens.mediastore.domain.media.model.TranscodingJob;
import org.earnlumens.mediastore.domain.media.model.TranscodingJobStatus;
import org.earnlumens.mediastore.domain.media.port.TranscodingDispatchPort;
import org.earnlumens.mediastore.domain.media.repository.AssetRepository;
import org.earnlumens.mediastore.domain.media.repository.TranscodingJobRepository;
import org.earnlumens.mediastore.infrastructure.config.TranscodingConfig;
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
 * Unit tests for {@link TranscodingJobService}.
 */
class TranscodingJobServiceTest {

    private TranscodingJobRepository jobRepository;
    private AssetRepository assetRepository;
    private TranscodingConfig config;
    private TranscodingDispatchPort dispatchPort;
    private TranscodingJobService service;

    @BeforeEach
    void setUp() {
        jobRepository = mock(TranscodingJobRepository.class);
        assetRepository = mock(AssetRepository.class);
        dispatchPort = mock(TranscodingDispatchPort.class);
        config = new TranscodingConfig();
        config.setMaxRetries(3);
        config.setHeartbeatTimeoutSeconds(120);
        config.setStaleBatchSize(50);
        config.setDispatchBatchSize(10);
        service = new TranscodingJobService(jobRepository, assetRepository, config, dispatchPort);

        // Default: save returns the same job
        when(jobRepository.save(any(TranscodingJob.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(assetRepository.save(any(Asset.class)))
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

    // ─── completeJob ────────────────────────────────────────────

    @Nested
    class CompleteJob {

        @Test
        void completesJobAndTransitionsAssetToReady() {
            TranscodingJob job = staleJob(TranscodingJobStatus.PROCESSING, 0, 3);
            Asset asset = testAsset("asset-1");
            when(jobRepository.findById("job-1")).thenReturn(Optional.of(job));
            when(assetRepository.findByTenantIdAndEntryId("earnlumens", "entry-1"))
                    .thenReturn(List.of(asset));

            Optional<TranscodingJob> result = service.completeJob("job-1", "public/media/entry-1/hls/");

            assertTrue(result.isPresent());
            assertEquals(TranscodingJobStatus.COMPLETED, result.get().getStatus());
            assertEquals("public/media/entry-1/hls/", result.get().getHlsR2Prefix());
            assertNotNull(result.get().getCompletedAt());
            assertEquals(AssetStatus.READY, asset.getStatus());
            verify(assetRepository).save(asset);
        }

        @Test
        void jobNotFound_returnsEmpty() {
            when(jobRepository.findById("no-job")).thenReturn(Optional.empty());

            Optional<TranscodingJob> result = service.completeJob("no-job", "prefix/");

            assertTrue(result.isEmpty());
            verify(assetRepository, never()).save(any());
        }

        @Test
        void assetNotFound_stillCompletesJob() {
            TranscodingJob job = staleJob(TranscodingJobStatus.PROCESSING, 0, 3);
            when(jobRepository.findById("job-1")).thenReturn(Optional.of(job));
            when(assetRepository.findByTenantIdAndEntryId("earnlumens", "entry-1"))
                    .thenReturn(Collections.emptyList());

            Optional<TranscodingJob> result = service.completeJob("job-1", "prefix/");

            assertTrue(result.isPresent());
            assertEquals(TranscodingJobStatus.COMPLETED, result.get().getStatus());
            verify(assetRepository, never()).save(any());
        }
    }

    // ─── failJob ────────────────────────────────────────────────

    @Nested
    class FailJob {

        @Test
        void withinRetryLimit_retriesJob() {
            TranscodingJob job = staleJob(TranscodingJobStatus.PROCESSING, 1, 3);
            when(jobRepository.findById("job-1")).thenReturn(Optional.of(job));

            Optional<TranscodingJob> result = service.failJob("job-1", "FFmpeg segfault");

            assertTrue(result.isPresent());
            assertEquals(TranscodingJobStatus.PENDING, result.get().getStatus());
            assertEquals(2, result.get().getRetryCount());
            assertEquals("FFmpeg segfault", result.get().getErrorMessage());
            verify(assetRepository, never()).save(any());
        }

        @Test
        void retriesExhausted_killsJobAndFailsAsset() {
            TranscodingJob job = staleJob(TranscodingJobStatus.PROCESSING, 3, 3);
            Asset asset = testAsset("asset-1");
            when(jobRepository.findById("job-1")).thenReturn(Optional.of(job));
            when(assetRepository.findByTenantIdAndEntryId("earnlumens", "entry-1"))
                    .thenReturn(List.of(asset));

            Optional<TranscodingJob> result = service.failJob("job-1", "Unsupported codec");

            assertTrue(result.isPresent());
            assertEquals(TranscodingJobStatus.DEAD, result.get().getStatus());
            assertEquals(AssetStatus.FAILED, asset.getStatus());
            verify(assetRepository).save(asset);
        }

        @Test
        void jobNotFound_returnsEmpty() {
            when(jobRepository.findById("no-job")).thenReturn(Optional.empty());

            Optional<TranscodingJob> result = service.failJob("no-job", "error");

            assertTrue(result.isEmpty());
        }
    }

    // ─── dispatchPendingJobs ──────────────────────────────────

    @Nested
    class DispatchPendingJobs {

        @Test
        void noPendingJobs_returnsZero() {
            when(jobRepository.findByStatus(TranscodingJobStatus.PENDING, 10))
                    .thenReturn(Collections.emptyList());

            int dispatched = service.dispatchPendingJobs();

            assertEquals(0, dispatched);
            verify(dispatchPort, never()).dispatch(any());
        }

        @Test
        void oneJob_dispatchesAndUpdatesStatus() {
            TranscodingJob job = staleJob(TranscodingJobStatus.PENDING, 0, 3);
            when(jobRepository.findByStatus(TranscodingJobStatus.PENDING, 10))
                    .thenReturn(List.of(job));

            int dispatched = service.dispatchPendingJobs();

            assertEquals(1, dispatched);
            verify(dispatchPort).dispatch(job);
            assertEquals(TranscodingJobStatus.DISPATCHED, job.getStatus());
            assertNotNull(job.getDispatchedAt());
            assertNotNull(job.getLastHeartbeat());
            verify(jobRepository).save(job);
        }

        @Test
        void dispatchFails_logsAndContinues() {
            TranscodingJob job1 = staleJob(TranscodingJobStatus.PENDING, 0, 3);
            job1.setId("job-fail");
            TranscodingJob job2 = staleJob(TranscodingJobStatus.PENDING, 0, 3);
            job2.setId("job-ok");

            when(jobRepository.findByStatus(TranscodingJobStatus.PENDING, 10))
                    .thenReturn(List.of(job1, job2));
            doThrow(new RuntimeException("Cloud Run unavailable"))
                    .when(dispatchPort).dispatch(job1);

            int dispatched = service.dispatchPendingJobs();

            assertEquals(1, dispatched);
            // job1 was NOT saved (dispatch failed)
            assertEquals(TranscodingJobStatus.PENDING, job1.getStatus());
            // job2 was dispatched successfully
            assertEquals(TranscodingJobStatus.DISPATCHED, job2.getStatus());
            verify(dispatchPort).dispatch(job1);
            verify(dispatchPort).dispatch(job2);
        }

        @Test
        void multipleJobs_allDispatched() {
            TranscodingJob job1 = staleJob(TranscodingJobStatus.PENDING, 0, 3);
            job1.setId("job-a");
            TranscodingJob job2 = staleJob(TranscodingJobStatus.PENDING, 1, 3);
            job2.setId("job-b");

            when(jobRepository.findByStatus(TranscodingJobStatus.PENDING, 10))
                    .thenReturn(List.of(job1, job2));

            int dispatched = service.dispatchPendingJobs();

            assertEquals(2, dispatched);
            verify(dispatchPort, times(2)).dispatch(any());
            verify(jobRepository, times(2)).save(any());
        }
    }

    // ─── heartbeat ──────────────────────────────────────────────

    @Nested
    class Heartbeat {

        @Test
        void dispatchedJob_transitionsToProcessing() {
            TranscodingJob job = staleJob(TranscodingJobStatus.DISPATCHED, 0, 3);
            when(jobRepository.findById("job-1")).thenReturn(Optional.of(job));

            service.heartbeat("job-1");

            assertEquals(TranscodingJobStatus.PROCESSING, job.getStatus());
            assertNotNull(job.getProcessingStartedAt());
            assertNotNull(job.getLastHeartbeat());
            verify(jobRepository).save(job);
        }

        @Test
        void processingJob_updatesHeartbeatOnly() {
            TranscodingJob job = staleJob(TranscodingJobStatus.PROCESSING, 0, 3);
            job.setProcessingStartedAt(LocalDateTime.now().minusMinutes(5));
            LocalDateTime oldProcessingStart = job.getProcessingStartedAt();
            when(jobRepository.findById("job-1")).thenReturn(Optional.of(job));

            service.heartbeat("job-1");

            assertEquals(TranscodingJobStatus.PROCESSING, job.getStatus());
            assertEquals(oldProcessingStart, job.getProcessingStartedAt());
            assertNotNull(job.getLastHeartbeat());
            verify(jobRepository).save(job);
        }

        @Test
        void unknownJob_noErrorNoSave() {
            when(jobRepository.findById("no-such-job")).thenReturn(Optional.empty());

            service.heartbeat("no-such-job");

            verify(jobRepository, never()).save(any());
        }
    }

    // ─── Helper ─────────────────────────────────────────────────

    private Asset testAsset(String assetId) {
        Asset a = new Asset();
        a.setId(assetId);
        a.setTenantId("earnlumens");
        a.setEntryId("entry-1");
        a.setStatus(AssetStatus.UPLOADED);
        return a;
    }
}
