package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.model.TranscodingJob;
import org.earnlumens.mediastore.domain.media.model.TranscodingJobStatus;
import org.earnlumens.mediastore.domain.media.repository.TranscodingJobRepository;
import org.earnlumens.mediastore.infrastructure.config.TranscodingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Application service for managing transcoding job lifecycle.
 *
 * <p>Every state transition is logged with structured fields for observability.
 * The watchdog calls {@link #recoverStaleJobs()} periodically to detect crashed
 * workers and retry or dead-letter jobs.
 *
 * <p>State machine:
 * <pre>
 *   PENDING ──dispatch──▶ DISPATCHED ──heartbeat──▶ PROCESSING ──complete──▶ COMPLETED
 *      ▲                      │                         │
 *      └────retry─────────────┘─────────────────────────┘
 *                                                        │
 *                          ┌──(retries exhausted)────────┘
 *                          ▼
 *                         DEAD
 * </pre>
 */
@Service
public class TranscodingJobService {

    private static final Logger logger = LoggerFactory.getLogger(TranscodingJobService.class);

    private final TranscodingJobRepository jobRepository;
    private final TranscodingConfig config;

    public TranscodingJobService(TranscodingJobRepository jobRepository,
                                  TranscodingConfig config) {
        this.jobRepository = jobRepository;
        this.config = config;
    }

    // ─── Stale-job recovery (called by Watchdog) ───────────────

    /**
     * Finds jobs that are DISPATCHED or PROCESSING but haven't sent a heartbeat
     * within the configured timeout. For each stale job, either retries it
     * (reset to PENDING) or marks it DEAD if retries are exhausted.
     *
     * @return number of stale jobs recovered
     */
    public int recoverStaleJobs() {
        LocalDateTime cutoff = LocalDateTime.now()
                .minusSeconds(config.getHeartbeatTimeoutSeconds());

        List<TranscodingJob> staleJobs = jobRepository.findStaleJobs(
                cutoff, config.getStaleBatchSize());

        if (staleJobs.isEmpty()) {
            return 0;
        }

        logger.warn("Watchdog: found {} stale transcoding job(s) (heartbeat before {})",
                staleJobs.size(), cutoff);

        int recovered = 0;
        for (TranscodingJob job : staleJobs) {
            try {
                handleStaleJob(job);
                recovered++;
            } catch (Exception e) {
                logger.error("Watchdog: failed to recover job id={}, asset={}: {}",
                        job.getId(), job.getAssetId(), e.getMessage(), e);
            }
        }

        return recovered;
    }

    /**
     * Decides whether to retry or dead-letter a stale job.
     */
    void handleStaleJob(TranscodingJob job) {
        if (job.getRetryCount() < job.getMaxRetries()) {
            retryJob(job, "Stale heartbeat detected — worker presumed crashed");
        } else {
            killJob(job, "Max retries exhausted (" + job.getMaxRetries()
                    + ") — last status: " + job.getStatus());
        }
    }

    // ─── Retry ──────────────────────────────────────────────────

    /**
     * Resets a failed or stale job to PENDING so the dispatcher picks it up again.
     */
    void retryJob(TranscodingJob job, String reason) {
        TranscodingJobStatus previousStatus = job.getStatus();
        int attempt = job.getRetryCount() + 1;

        job.setStatus(TranscodingJobStatus.PENDING);
        job.setRetryCount(attempt);
        job.setErrorMessage(reason);
        job.setLastHeartbeat(null);
        job.setDispatchedAt(null);
        job.setProcessingStartedAt(null);

        jobRepository.save(job);

        logger.info("Watchdog: retrying job id={}, asset={}, attempt={}/{}, previous={}, reason={}",
                job.getId(), job.getAssetId(), attempt, job.getMaxRetries(),
                previousStatus, reason);
    }

    // ─── Dead-letter ────────────────────────────────────────────

    /**
     * Marks a job as DEAD — no more retries. The user must be notified.
     */
    void killJob(TranscodingJob job, String reason) {
        TranscodingJobStatus previousStatus = job.getStatus();

        job.setStatus(TranscodingJobStatus.DEAD);
        job.setErrorMessage(reason);
        job.setCompletedAt(LocalDateTime.now());

        jobRepository.save(job);

        logger.error("Watchdog: job DEAD — id={}, asset={}, entry={}, tenant={}, "
                        + "retries={}/{}, previous={}, reason={}",
                job.getId(), job.getAssetId(), job.getEntryId(), job.getTenantId(),
                job.getRetryCount(), job.getMaxRetries(),
                previousStatus, reason);
    }

    // ─── Accessors for config (used by other services) ──────────

    public int getMaxRetries() {
        return config.getMaxRetries();
    }
}
