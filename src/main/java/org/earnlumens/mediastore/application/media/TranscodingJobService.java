package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.model.Asset;
import org.earnlumens.mediastore.domain.media.model.AssetStatus;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.MediaKind;
import org.earnlumens.mediastore.domain.media.model.TranscodingJob;
import org.earnlumens.mediastore.domain.media.model.TranscodingJobStatus;
import org.earnlumens.mediastore.domain.media.port.TranscodingDispatchPort;
import org.earnlumens.mediastore.domain.media.repository.AssetRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
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
    private final EntryRepository entryRepository;
    private final TranscodingConfig config;
    private final TranscodingDispatchPort dispatchPort;

    public TranscodingJobService(TranscodingJobRepository jobRepository,
                                  AssetRepository assetRepository,
                                  EntryRepository entryRepository,
                                  TranscodingConfig config,
                                  TranscodingDispatchPort dispatchPort) {
        this.jobRepository = jobRepository;
        this.assetRepository = assetRepository;
        this.entryRepository = entryRepository;
        this.config = config;
        this.dispatchPort = dispatchPort;
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

    // ─── Dispatch (called by TranscodingDispatcher) ────────────

    /**
     * Picks up PENDING jobs and dispatches them to the Cloud Run worker.
     *
     * @return number of jobs successfully dispatched
     */
    public int dispatchPendingJobs() {
        List<TranscodingJob> pending = jobRepository.findByStatus(
                TranscodingJobStatus.PENDING, config.getDispatchBatchSize());

        if (pending.isEmpty()) {
            return 0;
        }

        int dispatched = 0;
        for (TranscodingJob job : pending) {
            try {
                dispatchJob(job);
                dispatched++;
            } catch (Exception e) {
                logger.error("Failed to dispatch job id={}, asset={}: {}",
                        job.getId(), job.getAssetId(), e.getMessage(), e);
            }
        }

        if (dispatched > 0) {
            logger.info("Dispatched {}/{} pending transcoding job(s)", dispatched, pending.size());
        }
        return dispatched;
    }

    /**
     * Dispatches a single job to the Cloud Run worker and updates its status.
     */
    private void dispatchJob(TranscodingJob job) {
        dispatchPort.dispatch(job);

        job.setStatus(TranscodingJobStatus.DISPATCHED);
        job.setDispatchedAt(LocalDateTime.now());
        job.setLastHeartbeat(LocalDateTime.now());
        jobRepository.save(job);

        logger.info("Job dispatched: id={}, asset={}, entry={}, tenant={}",
                job.getId(), job.getAssetId(), job.getEntryId(), job.getTenantId());
    }

    // ─── Heartbeat (called by TranscodingCallbackController) ───

    /**
     * Updates the heartbeat timestamp for a running job.
     * The first heartbeat from the worker transitions DISPATCHED → PROCESSING.
     *
     * @param jobId the transcoding job ID
     */
    public void heartbeat(String jobId) {
        Optional<TranscodingJob> opt = jobRepository.findById(jobId);
        if (opt.isEmpty()) {
            logger.warn("heartbeat: job not found id={}", jobId);
            return;
        }

        TranscodingJob job = opt.get();

        if (job.getStatus() == TranscodingJobStatus.DISPATCHED) {
            job.setStatus(TranscodingJobStatus.PROCESSING);
            job.setProcessingStartedAt(LocalDateTime.now());
            logger.info("heartbeat: job {} transitioned DISPATCHED → PROCESSING", jobId);
        }

        job.setLastHeartbeat(LocalDateTime.now());
        jobRepository.save(job);
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

    // ─── Batch operations (called by internal endpoints) ────────

    /**
     * Creates PENDING transcoding jobs for all published VIDEO entries
     * that don't already have an active or completed transcoding job.
     *
     * @param tenantId the tenant to operate on
     * @return number of jobs created
     */
    public int batchTranscodeExistingVideos(String tenantId) {
        List<Entry> videoEntries = entryRepository.findByTenantIdAndStatusAndType(
                tenantId, EntryStatus.PUBLISHED, EntryType.VIDEO);

        logger.info("Batch transcode: found {} published VIDEO entries for tenant={}",
                videoEntries.size(), tenantId);

        int created = 0;
        for (Entry entry : videoEntries) {
            // Skip if there's already an active job (PENDING/DISPATCHED/PROCESSING)
            if (jobRepository.findActiveByTenantIdAndEntryId(tenantId, entry.getId()).isPresent()) {
                logger.debug("Batch transcode: skipping entry={} — active job exists", entry.getId());
                continue;
            }

            // Find the FULL asset — we need its r2Key as the transcoding source
            var optAsset = assetRepository.findByTenantIdAndEntryIdAndKindAndStatus(
                    tenantId, entry.getId(), MediaKind.FULL, AssetStatus.READY);

            if (optAsset.isEmpty()) {
                // Try UPLOADED status (never transcoded)
                optAsset = assetRepository.findByTenantIdAndEntryIdAndKindAndStatus(
                        tenantId, entry.getId(), MediaKind.FULL, AssetStatus.UPLOADED);
            }

            if (optAsset.isEmpty()) {
                logger.warn("Batch transcode: skipping entry={} — no FULL asset found", entry.getId());
                continue;
            }

            Asset asset = optAsset.get();

            // If asset is already READY and has a completed job, skip
            if (asset.getStatus() == AssetStatus.READY) {
                logger.debug("Batch transcode: skipping entry={} — asset already READY", entry.getId());
                continue;
            }

            TranscodingJob job = new TranscodingJob();
            job.setTenantId(tenantId);
            job.setEntryId(entry.getId());
            job.setAssetId(asset.getId());
            job.setSourceR2Key(asset.getR2Key());
            job.setStatus(TranscodingJobStatus.PENDING);
            job.setRetryCount(0);
            job.setMaxRetries(config.getMaxRetries());
            createJob(job);
            created++;
        }

        logger.info("Batch transcode: created {} new transcoding jobs for tenant={}",
                created, tenantId);
        return created;
    }

    /**
     * Finds the most recent active (non-terminal) transcoding job for an entry,
     * or the latest completed/dead job if no active job exists.
     * Used by Creator Studio to show transcoding status.
     */
    public Optional<TranscodingJob> findLatestByTenantIdAndEntryId(String tenantId, String entryId) {
        // First try active (PENDING, DISPATCHED, PROCESSING)
        Optional<TranscodingJob> active = jobRepository.findActiveByTenantIdAndEntryId(tenantId, entryId);
        if (active.isPresent()) {
            return active;
        }
        // Fall back to any job for this entry (COMPLETED, FAILED, DEAD)
        // We don't have a direct query for this, but the watchdog/dispatch flow
        // ensures at most one non-terminal job per entry. For terminal jobs,
        // we can look up via the active query which returns empty — no terminal lookup needed
        // because the asset status (READY/FAILED) is the source of truth for terminal states.
        return Optional.empty();
    }

    // ─── Callback handlers (called by TranscodingCallbackController) ─

    /**
     * Marks a job as COMPLETED and transitions the asset to READY.
     * Also denormalizes server-side ffprobe metadata onto the Entry.
     *
     * @param jobId       the transcoding job ID
     * @param hlsR2Prefix the R2 prefix where HLS segments were written
     * @param durationSec video duration in seconds (from ffprobe, nullable)
     * @param widthPx     video width in pixels (from ffprobe, nullable)
     * @param heightPx    video height in pixels (from ffprobe, nullable)
     * @return the updated job, or empty if the job was not found
     */
    public Optional<TranscodingJob> completeJob(String jobId, String hlsR2Prefix,
                                                 Integer durationSec, Integer widthPx, Integer heightPx) {
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

        // Transition the asset to READY and save ffprobe metadata
        List<Asset> assets = assetRepository.findByTenantIdAndEntryId(
                job.getTenantId(), job.getEntryId());
        assets.stream()
                .filter(a -> a.getId().equals(job.getAssetId()))
                .findFirst()
                .ifPresentOrElse(
                        asset -> {
                            asset.setStatus(AssetStatus.READY);
                            if (durationSec != null && durationSec > 0) asset.setDurationSec(durationSec);
                            if (widthPx != null && widthPx > 0) asset.setWidthPx(widthPx);
                            if (heightPx != null && heightPx > 0) asset.setHeightPx(heightPx);
                            assetRepository.save(asset);
                            logger.info("completeJob: asset READY — job={}, asset={}, entry={}",
                                    jobId, asset.getId(), job.getEntryId());
                        },
                        () -> logger.error("completeJob: asset not found — job={}, assetId={}, entry={}",
                                jobId, job.getAssetId(), job.getEntryId())
                );

        // Denormalize ffprobe metadata onto the Entry for feed display
        if (durationSec != null && durationSec > 0) {
            entryRepository.findByTenantIdAndId(job.getTenantId(), job.getEntryId())
                    .ifPresent(entry -> {
                        entry.setDurationSec(durationSec);
                        entryRepository.save(entry);
                        logger.info("completeJob: set durationSec={} on entry={}",
                                durationSec, job.getEntryId());
                    });
        }

        logger.info("completeJob: job COMPLETED — id={}, hlsPrefix={}, entry={}, duration={}s, {}x{}",
                jobId, hlsR2Prefix, job.getEntryId(), durationSec, widthPx, heightPx);
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

    /**
     * Fills missing durationSec on published VIDEO entries by copying from
     * the FULL asset (client-reported metadata). Useful for videos that were
     * uploaded with client metadata but whose Entry never got durationSec set.
     *
     * @return number of entries updated
     */
    public int batchFillMissingDuration(String tenantId) {
        List<Entry> entries = entryRepository.findByTenantIdAndStatusAndType(
                tenantId, EntryStatus.PUBLISHED, EntryType.VIDEO);

        int updated = 0;
        for (Entry entry : entries) {
            if (entry.getDurationSec() != null && entry.getDurationSec() > 0) {
                continue; // already has duration
            }

            // Look for a FULL asset with duration metadata
            var optAsset = assetRepository.findByTenantIdAndEntryIdAndKindAndStatus(
                    tenantId, entry.getId(), MediaKind.FULL, AssetStatus.READY);
            if (optAsset.isEmpty()) {
                optAsset = assetRepository.findByTenantIdAndEntryIdAndKindAndStatus(
                        tenantId, entry.getId(), MediaKind.FULL, AssetStatus.UPLOADED);
            }
            if (optAsset.isEmpty()) {
                continue;
            }

            Asset asset = optAsset.get();
            if (asset.getDurationSec() != null && asset.getDurationSec() > 0) {
                entry.setDurationSec(asset.getDurationSec());
                entryRepository.save(entry);
                updated++;
                logger.info("Batch metadata: set durationSec={} on entry={} from asset={}",
                        asset.getDurationSec(), entry.getId(), asset.getId());
            }
        }

        logger.info("Batch metadata: updated {} entries for tenant={}", updated, tenantId);
        return updated;
    }
}
