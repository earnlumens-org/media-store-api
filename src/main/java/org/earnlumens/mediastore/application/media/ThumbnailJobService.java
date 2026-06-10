package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.model.Collection;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.ThumbnailJob;
import org.earnlumens.mediastore.domain.media.model.ThumbnailJobKind;
import org.earnlumens.mediastore.domain.media.model.ThumbnailJobStatus;
import org.earnlumens.mediastore.domain.media.port.ThumbnailDispatchPort;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.media.repository.ThumbnailJobRepository;
import org.earnlumens.mediastore.infrastructure.config.ThumbnailConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Application service for thumbnail-processing job lifecycle.
 *
 * <p>Mirrors the transcoding-job state machine (PENDING → DISPATCHED → PROCESSING
 * → COMPLETED/SKIPPED/FAILED → DEAD) but with a key difference in failure
 * handling: a DEAD thumbnail job is <b>not</b> a hard failure. The original
 * image (already validated client-side and at upload time) remains usable;
 * the UI simply skips emitting a srcset for that image.
 *
 * <p>This is intentional: thumbnail processing is a best-effort optimisation,
 * not a gate. Sellers must never lose a sale because Sharp choked on a CMYK
 * profile or some EXIF edge case.
 */
@Service
public class ThumbnailJobService {

    private static final Logger logger = LoggerFactory.getLogger(ThumbnailJobService.class);

    private final ThumbnailJobRepository jobRepository;
    private final EntryRepository entryRepository;
    private final CollectionRepository collectionRepository;
    private final ThumbnailConfig config;
    private final ThumbnailDispatchPort dispatchPort;

    public ThumbnailJobService(ThumbnailJobRepository jobRepository,
                               EntryRepository entryRepository,
                               CollectionRepository collectionRepository,
                               ThumbnailConfig config,
                               ThumbnailDispatchPort dispatchPort) {
        this.jobRepository = jobRepository;
        this.entryRepository = entryRepository;
        this.collectionRepository = collectionRepository;
        this.config = config;
        this.dispatchPort = dispatchPort;
    }

    // ─── Job creation (called by ModerationJobService on APPROVE) ─────

    /**
     * Enqueues a thumbnail job for a given owner + kind, deriving the
     * source key and computing the output prefix automatically.
     *
     * <p>If the source key is null/blank, no job is created (caller's
     * responsibility to skip — defensive).
     *
     * <p>If an active job already exists for the same (tenant, owner, kind),
     * we skip to avoid duplicate processing.
     *
     * @return the saved job, or empty if skipped
     */
    public Optional<ThumbnailJob> enqueue(String tenantId, String ownerId,
                                          ThumbnailJobKind kind, String sourceR2Key) {
        if (sourceR2Key == null || sourceR2Key.isBlank()) {
            logger.debug("thumbnail enqueue: skipping — no source key for owner={}, kind={}", ownerId, kind);
            return Optional.empty();
        }

        if (jobRepository.findActiveByTenantIdAndOwnerIdAndKind(tenantId, ownerId, kind).isPresent()) {
            logger.debug("thumbnail enqueue: skipping — active job exists for owner={}, kind={}", ownerId, kind);
            return Optional.empty();
        }

        String outputPrefix = computeOutputPrefix(sourceR2Key);

        ThumbnailJob job = new ThumbnailJob();
        job.setTenantId(tenantId);
        job.setOwnerId(ownerId);
        job.setKind(kind);
        job.setSourceR2Key(sourceR2Key);
        job.setOutputR2Prefix(outputPrefix);
        job.setStatus(ThumbnailJobStatus.PENDING);
        job.setRetryCount(0);
        job.setMaxRetries(config.getMaxRetries());

        ThumbnailJob saved = jobRepository.save(job);
        logger.info("thumbnail enqueue: created job id={}, tenant={}, owner={}, kind={}, source={}, output={}",
                saved.getId(), tenantId, ownerId, kind, sourceR2Key, outputPrefix);
        return Optional.of(saved);
    }

    /**
     * Output prefix convention: parent directory of the source key + "/derived".
     * <p>e.g. {@code public/media/{tenantId}/{entryId}/thumbnail/abc-foo.png}
     * → {@code public/media/{tenantId}/{entryId}/thumbnail/derived}.
     */
    static String computeOutputPrefix(String sourceR2Key) {
        int slash = sourceR2Key.lastIndexOf('/');
        String parent = (slash > 0) ? sourceR2Key.substring(0, slash) : sourceR2Key;
        return parent + "/derived";
    }

    // ─── Dispatch (called by ThumbnailDispatcher) ─────────────────────

    public int dispatchPendingJobs() {
        List<ThumbnailJob> pending = jobRepository.findAllByStatus(
                ThumbnailJobStatus.PENDING, config.getDispatchBatchSize());

        if (pending.isEmpty()) {
            return 0;
        }

        int dispatched = 0;
        for (ThumbnailJob job : pending) {
            try {
                dispatchJob(job);
                dispatched++;
            } catch (Exception e) {
                logger.error("Failed to dispatch thumbnail job id={}, owner={}: {}",
                        job.getId(), job.getOwnerId(), e.getMessage(), e);
            }
        }

        if (dispatched > 0) {
            logger.info("Dispatched {}/{} pending thumbnail job(s)", dispatched, pending.size());
        }
        return dispatched;
    }

    private void dispatchJob(ThumbnailJob job) {
        dispatchPort.dispatch(job);

        job.setStatus(ThumbnailJobStatus.DISPATCHED);
        job.setDispatchedAt(LocalDateTime.now());
        job.setLastHeartbeat(LocalDateTime.now());
        jobRepository.save(job);

        logger.info("thumbnail dispatched: id={}, owner={}, kind={}, tenant={}",
                job.getId(), job.getOwnerId(), job.getKind(), job.getTenantId());
    }

    // ─── Heartbeat ────────────────────────────────────────────────────

    public void heartbeat(String tenantId, String jobId) {
        Optional<ThumbnailJob> opt = jobRepository.findByTenantIdAndId(tenantId, jobId);
        if (opt.isEmpty()) {
            logger.warn("thumbnail heartbeat: job not found id={}", jobId);
            return;
        }
        ThumbnailJob job = opt.get();
        if (job.getStatus() == ThumbnailJobStatus.DISPATCHED) {
            job.setStatus(ThumbnailJobStatus.PROCESSING);
            job.setProcessingStartedAt(LocalDateTime.now());
            logger.info("thumbnail heartbeat: job {} transitioned DISPATCHED → PROCESSING", jobId);
        }
        job.setLastHeartbeat(LocalDateTime.now());
        jobRepository.save(job);
    }

    // ─── Watchdog (stale-job recovery) ────────────────────────────────

    public int recoverStaleJobs() {
        LocalDateTime cutoff = LocalDateTime.now()
                .minusSeconds(config.getHeartbeatTimeoutSeconds());

        List<ThumbnailJob> staleJobs = jobRepository.findAllStaleJobs(
                cutoff, config.getStaleBatchSize());

        if (staleJobs.isEmpty()) {
            return 0;
        }

        logger.warn("Thumbnail watchdog: found {} stale job(s) (heartbeat before {})",
                staleJobs.size(), cutoff);

        int recovered = 0;
        for (ThumbnailJob job : staleJobs) {
            try {
                handleStaleJob(job);
                recovered++;
            } catch (Exception e) {
                logger.error("Thumbnail watchdog: failed to recover job id={}: {}",
                        job.getId(), e.getMessage(), e);
            }
        }
        return recovered;
    }

    void handleStaleJob(ThumbnailJob job) {
        if (job.getRetryCount() < job.getMaxRetries()) {
            retryJob(job, "Stale heartbeat detected — worker presumed crashed");
        } else {
            killJob(job, "Max retries exhausted (" + job.getMaxRetries()
                    + ") — last status: " + job.getStatus());
        }
    }

    void retryJob(ThumbnailJob job, String reason) {
        ThumbnailJobStatus previousStatus = job.getStatus();
        int attempt = job.getRetryCount() + 1;

        job.setStatus(ThumbnailJobStatus.PENDING);
        job.setRetryCount(attempt);
        job.setErrorMessage(reason);
        job.setLastHeartbeat(null);
        job.setDispatchedAt(null);
        job.setProcessingStartedAt(null);

        jobRepository.save(job);

        logger.info("Thumbnail watchdog: retrying job id={}, owner={}, attempt={}/{}, previous={}, reason={}",
                job.getId(), job.getOwnerId(), attempt, job.getMaxRetries(), previousStatus, reason);
    }

    void killJob(ThumbnailJob job, String reason) {
        ThumbnailJobStatus previousStatus = job.getStatus();

        job.setStatus(ThumbnailJobStatus.DEAD);
        job.setErrorMessage(reason);
        job.setCompletedAt(LocalDateTime.now());
        jobRepository.save(job);

        // Graceful failure: the original image remains valid. Log at WARN, not ERROR —
        // the entry/collection is still fully sellable.
        logger.warn("Thumbnail watchdog: job DEAD — id={}, owner={}, kind={}, tenant={}, "
                        + "retries={}/{}, previous={}, reason={} — original image will be used as-is",
                job.getId(), job.getOwnerId(), job.getKind(), job.getTenantId(),
                job.getRetryCount(), job.getMaxRetries(), previousStatus, reason);
    }

    // ─── Callback handlers (called by ThumbnailCallbackController) ────

    /**
     * Worker reports successful processing. Denormalises the variants prefix
     * onto the owning Entry / Collection so the UI can emit srcset.
     */
    public Optional<ThumbnailJob> completeJob(String tenantId, String jobId,
                                              String variantsR2Prefix,
                                              Integer sourceWidthPx, Integer sourceHeightPx) {
        Optional<ThumbnailJob> opt = jobRepository.findByTenantIdAndId(tenantId, jobId);
        if (opt.isEmpty()) {
            logger.warn("thumbnail completeJob: job not found id={}", jobId);
            return Optional.empty();
        }

        ThumbnailJob job = opt.get();

        // Idempotency: callbacks may be retried by the worker. A terminal job
        // must not be re-processed (a late duplicate could clobber state).
        if (isTerminal(job.getStatus())) {
            logger.info("thumbnail completeJob: ignoring callback for terminal job id={} (status={})",
                    jobId, job.getStatus());
            return Optional.of(job);
        }

        // Defence in depth: the worker controls the body of the callback,
        // so reject any prefix that does not match what we asked it to write.
        if (variantsR2Prefix == null || !variantsR2Prefix.equals(job.getOutputR2Prefix())) {
            logger.error("thumbnail completeJob: prefix mismatch — job={}, expected={}, got={}",
                    jobId, job.getOutputR2Prefix(), variantsR2Prefix);
            return Optional.empty();
        }

        job.setStatus(ThumbnailJobStatus.COMPLETED);
        job.setSourceWidthPx(sourceWidthPx);
        job.setSourceHeightPx(sourceHeightPx);
        job.setCompletedAt(LocalDateTime.now());
        jobRepository.save(job);

        denormalizeVariantsPrefix(job, variantsR2Prefix);

        logger.info("thumbnail completeJob: COMPLETED — id={}, owner={}, kind={}, prefix={}, source={}x{}",
                jobId, job.getOwnerId(), job.getKind(), variantsR2Prefix, sourceWidthPx, sourceHeightPx);
        return Optional.of(job);
    }

    /**
     * Worker reports the input was below the configured minimum size.
     * Not an error — the original is served as-is. We mark the job
     * SKIPPED for diagnostics and clear any stale variants prefix on
     * the owner so the UI does not point at non-existent files.
     */
    public Optional<ThumbnailJob> skipJob(String tenantId, String jobId, String reason,
                                          Integer sourceWidthPx, Integer sourceHeightPx) {
        Optional<ThumbnailJob> opt = jobRepository.findByTenantIdAndId(tenantId, jobId);
        if (opt.isEmpty()) {
            logger.warn("thumbnail skipJob: job not found id={}", jobId);
            return Optional.empty();
        }

        ThumbnailJob job = opt.get();
        if (isTerminal(job.getStatus())) {
            logger.info("thumbnail skipJob: ignoring callback for terminal job id={} (status={})",
                    jobId, job.getStatus());
            return Optional.of(job);
        }
        job.setStatus(ThumbnailJobStatus.SKIPPED);
        job.setErrorMessage(reason);
        job.setSourceWidthPx(sourceWidthPx);
        job.setSourceHeightPx(sourceHeightPx);
        job.setCompletedAt(LocalDateTime.now());
        jobRepository.save(job);

        denormalizeVariantsPrefix(job, null);

        logger.info("thumbnail skipJob: SKIPPED — id={}, owner={}, kind={}, source={}x{}, reason={}",
                jobId, job.getOwnerId(), job.getKind(), sourceWidthPx, sourceHeightPx, reason);
        return Optional.of(job);
    }

    /**
     * Worker reports a transient failure. Retries up to maxRetries; otherwise DEAD.
     */
    public Optional<ThumbnailJob> failJob(String tenantId, String jobId, String errorMessage) {
        Optional<ThumbnailJob> opt = jobRepository.findByTenantIdAndId(tenantId, jobId);
        if (opt.isEmpty()) {
            logger.warn("thumbnail failJob: job not found id={}", jobId);
            return Optional.empty();
        }
        ThumbnailJob job = opt.get();

        if (isTerminal(job.getStatus())) {
            logger.info("thumbnail failJob: ignoring callback for terminal job id={} (status={})",
                    jobId, job.getStatus());
            return Optional.of(job);
        }

        if (job.getRetryCount() < job.getMaxRetries()) {
            retryJob(job, errorMessage);
        } else {
            killJob(job, errorMessage);
        }
        return Optional.of(job);
    }

    /** Terminal job states: late/duplicate callbacks must never mutate these. */
    private static boolean isTerminal(ThumbnailJobStatus status) {
        return status == ThumbnailJobStatus.COMPLETED
                || status == ThumbnailJobStatus.SKIPPED
                || status == ThumbnailJobStatus.DEAD;
    }

    /**
     * Writes the variants prefix onto the owning Entry / Collection, depending on kind.
     * Pass {@code null} to clear the field (skipped jobs).
     */
    private void denormalizeVariantsPrefix(ThumbnailJob job, String variantsPrefix) {
        switch (job.getKind()) {
            case THUMBNAIL -> entryRepository.findByTenantIdAndId(job.getTenantId(), job.getOwnerId())
                    .ifPresent(entry -> {
                        entry.setThumbnailVariantsPrefix(variantsPrefix);
                        entryRepository.save(entry);
                    });
            case PREVIEW -> entryRepository.findByTenantIdAndId(job.getTenantId(), job.getOwnerId())
                    .ifPresent(entry -> {
                        entry.setPreviewVariantsPrefix(variantsPrefix);
                        entryRepository.save(entry);
                    });
            case COVER -> collectionRepository.findByTenantIdAndId(job.getTenantId(), job.getOwnerId())
                    .ifPresent(collection -> {
                        collection.setCoverVariantsPrefix(variantsPrefix);
                        collectionRepository.save(collection);
                    });
        }
    }

    /**
     * Enqueues thumbnail jobs for an approved Entry. Always tries the
     * thumbnail; also enqueues a PREVIEW job if {@code previewR2Key} is set.
     */
    public void enqueueForEntry(String tenantId, Entry entry) {
        enqueue(tenantId, entry.getId(), ThumbnailJobKind.THUMBNAIL, entry.getThumbnailR2Key());
        enqueue(tenantId, entry.getId(), ThumbnailJobKind.PREVIEW, entry.getPreviewR2Key());
    }

    /**
     * Enqueues a cover-thumbnail job for an approved Collection.
     */
    public void enqueueForCollection(String tenantId, Collection collection) {
        enqueue(tenantId, collection.getId(), ThumbnailJobKind.COVER, collection.getCoverR2Key());
    }

    // ─── Accessors ────────────────────────────────────────────────────

    public List<ThumbnailJob> findByStatus(ThumbnailJobStatus status) {
        return jobRepository.findAllByStatus(status, 100);
    }
}
