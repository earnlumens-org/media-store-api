package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.model.Asset;
import org.earnlumens.mediastore.domain.media.model.AssetStatus;
import org.earnlumens.mediastore.domain.media.model.TranscodingJob;
import org.earnlumens.mediastore.domain.media.model.TranscodingJobStatus;
import org.earnlumens.mediastore.domain.media.repository.AssetRepository;
import org.earnlumens.mediastore.domain.media.repository.TranscodingJobRepository;
import org.earnlumens.mediastore.infrastructure.config.TranscodingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
    private final AssetRepository assetRepository;
    private final TranscodingConfig config;

    public TranscodingJobService(TranscodingJobRepository jobRepository,
                                  AssetRepository assetRepository,
                                  TranscodingConfig config) {
        this.jobRepository = jobRepository;
        this.assetRepository = assetRepository;
        this.config = config;
    }

    // ─── Job creation (called by EntryUploadService) ───────────

    /**
     * Persists a new transcoding job. The job must be PENDING.
     *
     * @param job the job to create (status must be PENDING)
     * @return the saved job with generated ID
     */
    public TranscodingJob createJob(TranscodingJob job) {
        TranscodingJob saved = jobRepository.save(job);
        logger.info("Created transcoding job: id={}, asset={}, entry={}, tenant={}",
                saved.getId(), saved.getAssetId(), saved.getEntryId(), saved.getTenantId());
        return saved;
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

    // ─── Callback handlers (called by TranscodingCallbackController) ─

    /**
     * Marks a job as COMPLETED and transitions the asset to READY.
     *
     * @param jobId       the transcoding job ID
     * @param hlsR2Prefix the R2 prefix where HLS segments were written
     * @return the updated job, or empty if the job was not found
     * @throws IllegalStateException if the asset cannot be found
     */
    public Optional<TranscodingJob> completeJob(String jobId, String hlsR2Prefix) {
        Optional<TranscodingJob> opt = jobRepository.findById(jobId);
        if (opt.isEmpty()) {
            logger.warn("completeJob: job not found id={}", jobId);
            return Optional.empty();
        }

        TranscodingJob job = opt.get();
        job.setStatus(TranscodingJobStatus.COMPLETED);
        job.setHlsR2Prefix(hlsR2Prefix);
        job.setCompletedAt(LocalDateTime.now());
        jobRepository.save(job);

        // Transition the asset to READY
        List<Asset> assets = assetRepository.findByTenantIdAndEntryId(
                job.getTenantId(), job.getEntryId());
        assets.stream()
                .filter(a -> a.getId().equals(job.getAssetId()))
                .findFirst()
                .ifPresentOrElse(
                        asset -> {
                            asset.setStatus(AssetStatus.READY);
                            assetRepository.save(asset);
                            logger.info("completeJob: asset READY — job={}, asset={}, entry={}",
                                    jobId, asset.getId(), job.getEntryId());
                        },
                        () -> logger.error("completeJob: asset not found — job={}, assetId={}, entry={}",
                                jobId, job.getAssetId(), job.getEntryId())
                );

        logger.info("completeJob: job COMPLETED — id={}, hlsPrefix={}, entry={}",
                jobId, hlsR2Prefix, job.getEntryId());
        return Optional.of(job);
    }

    /**
     * Handles a FAILED callback from the worker.
     * If retries remain, resets to PENDING. Otherwise marks DEAD.
     *
     * @param jobId        the transcoding job ID
     * @param errorMessage error description from the worker
     * @return the updated job, or empty if the job was not found
     */
    public Optional<TranscodingJob> failJob(String jobId, String errorMessage) {
        Optional<TranscodingJob> opt = jobRepository.findById(jobId);
        if (opt.isEmpty()) {
            logger.warn("failJob: job not found id={}", jobId);
            return Optional.empty();
        }

        TranscodingJob job = opt.get();

        if (job.getRetryCount() < job.getMaxRetries()) {
            retryJob(job, errorMessage);
        } else {
            // Mark asset as FAILED so the user sees the error
            List<Asset> assets = assetRepository.findByTenantIdAndEntryId(
                    job.getTenantId(), job.getEntryId());
            assets.stream()
                    .filter(a -> a.getId().equals(job.getAssetId()))
                    .findFirst()
                    .ifPresent(asset -> {
                        asset.setStatus(AssetStatus.FAILED);
                        assetRepository.save(asset);
                    });

            killJob(job, errorMessage);
        }

        return Optional.of(job);
    }
}
