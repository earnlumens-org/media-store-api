package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.model.Asset;
import org.earnlumens.mediastore.domain.media.model.AssetStatus;
import org.earnlumens.mediastore.domain.media.model.CollectionStatus;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.MediaKind;
import org.earnlumens.mediastore.domain.media.model.ModerationDecision;
import org.earnlumens.mediastore.domain.media.model.ModerationJob;
import org.earnlumens.mediastore.domain.media.model.ModerationJobStatus;
import org.earnlumens.mediastore.domain.media.model.TranscodingJob;
import org.earnlumens.mediastore.domain.media.model.TranscodingJobStatus;
import org.earnlumens.mediastore.domain.media.port.ModerationDispatchPort;
import org.earnlumens.mediastore.domain.media.repository.AssetRepository;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.media.repository.ModerationJobRepository;
import org.earnlumens.mediastore.infrastructure.config.ModerationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Application service for managing content moderation job lifecycle.
 *
 * <p>On APPROVE → creates a TranscodingJob (for VIDEO) or transitions entry to APPROVED.
 * <p>On REJECT → transitions entry to REJECTED.
 * <p>On MANUAL_QUEUE → entry stays in IN_REVIEW for human review.
 */
@Service
public class ModerationJobService {

    private static final Logger logger = LoggerFactory.getLogger(ModerationJobService.class);

    private final ModerationJobRepository jobRepository;
    private final EntryRepository entryRepository;
    private final CollectionRepository collectionRepository;
    private final AssetRepository assetRepository;
    private final ModerationConfig config;
    private final ModerationDispatchPort dispatchPort;
    private final TranscodingJobService transcodingJobService;
    private final ThumbnailJobService thumbnailJobService;
    private final Executor dispatchExecutor;

    public ModerationJobService(ModerationJobRepository jobRepository,
                                 EntryRepository entryRepository,
                                 CollectionRepository collectionRepository,
                                 AssetRepository assetRepository,
                                 ModerationConfig config,
                                 ModerationDispatchPort dispatchPort,
                                 TranscodingJobService transcodingJobService,
                                 ThumbnailJobService thumbnailJobService,
                                 @Qualifier("moderationDispatchExecutor") Executor dispatchExecutor) {
        this.jobRepository = jobRepository;
        this.entryRepository = entryRepository;
        this.collectionRepository = collectionRepository;
        this.assetRepository = assetRepository;
        this.config = config;
        this.dispatchPort = dispatchPort;
        this.transcodingJobService = transcodingJobService;
        this.thumbnailJobService = thumbnailJobService;
        this.dispatchExecutor = dispatchExecutor;
    }

    // ─── Job creation (called by EntryUploadService) ───────────

    public ModerationJob createJob(ModerationJob job) {
        ModerationJob saved = jobRepository.save(job);
        logger.info("Created moderation job: id={}, entry={}, type={}, tenant={}",
                saved.getId(), saved.getEntryId(), saved.getEntryType(), saved.getTenantId());
        return saved;
    }

    // ─── Dispatch (called by ModerationDispatcher) ─────────────

    public int dispatchPendingJobs() {
        List<ModerationJob> pending = jobRepository.findAllByStatus(
                ModerationJobStatus.PENDING, config.getDispatchBatchSize());

        if (pending.isEmpty()) {
            return 0;
        }

        AtomicInteger dispatched = new AtomicInteger(0);

        CompletableFuture<?>[] futures = pending.stream()
                .map(job -> CompletableFuture.runAsync(() -> {
                    try {
                        dispatchJob(job);
                        dispatched.incrementAndGet();
                    } catch (Exception e) {
                        logger.error("Failed to dispatch moderation job id={}, entry={}: {}",
                                job.getId(), job.getEntryId(), e.getMessage(), e);
                    }
                }, dispatchExecutor))
                .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures).join();

        int count = dispatched.get();
        if (count > 0) {
            logger.info("Dispatched {}/{} pending moderation job(s)", count, pending.size());
        }
        return count;
    }

    private void dispatchJob(ModerationJob job) {
        dispatchPort.dispatch(job);

        job.setStatus(ModerationJobStatus.DISPATCHED);
        job.setDispatchedAt(LocalDateTime.now());
        job.setLastHeartbeat(LocalDateTime.now());
        jobRepository.save(job);

        logger.info("Moderation job dispatched: id={}, entry={}, type={}, tenant={}",
                job.getId(), job.getEntryId(), job.getEntryType(), job.getTenantId());
    }

    // ─── Heartbeat (called by ModerationCallbackController) ────

    public void heartbeat(String jobId, String tenantId) {
        Optional<ModerationJob> opt = jobRepository.findByTenantIdAndId(tenantId, jobId);
        if (opt.isEmpty()) {
            logger.warn("moderation heartbeat: job not found id={}", jobId);
            return;
        }

        ModerationJob job = opt.get();

        if (job.getStatus() == ModerationJobStatus.DISPATCHED) {
            job.setStatus(ModerationJobStatus.PROCESSING);
            job.setProcessingStartedAt(LocalDateTime.now());
            logger.info("moderation heartbeat: job {} transitioned DISPATCHED → PROCESSING", jobId);
        }

        job.setLastHeartbeat(LocalDateTime.now());
        jobRepository.save(job);
    }

    // ─── Stale-job recovery (called by Watchdog) ───────────────

    public int recoverStaleJobs() {
        LocalDateTime cutoff = LocalDateTime.now()
                .minusSeconds(config.getHeartbeatTimeoutSeconds());

        List<ModerationJob> staleJobs = jobRepository.findAllStaleJobs(
                cutoff, config.getStaleBatchSize());

        if (staleJobs.isEmpty()) {
            return 0;
        }

        logger.warn("Moderation watchdog: found {} stale job(s) (heartbeat before {})",
                staleJobs.size(), cutoff);

        int recovered = 0;
        for (ModerationJob job : staleJobs) {
            try {
                handleStaleJob(job);
                recovered++;
            } catch (Exception e) {
                logger.error("Moderation watchdog: failed to recover job id={}, entry={}: {}",
                        job.getId(), job.getEntryId(), e.getMessage(), e);
            }
        }

        return recovered;
    }

    void handleStaleJob(ModerationJob job) {
        if (job.getRetryCount() < job.getMaxRetries()) {
            retryJob(job, "Stale heartbeat detected — worker presumed crashed");
        } else {
            killJob(job, "Max retries exhausted (" + job.getMaxRetries()
                    + ") — last status: " + job.getStatus());
        }
    }

    void retryJob(ModerationJob job, String reason) {
        ModerationJobStatus previousStatus = job.getStatus();
        int attempt = job.getRetryCount() + 1;

        job.setStatus(ModerationJobStatus.PENDING);
        job.setRetryCount(attempt);
        job.setErrorMessage(reason);
        job.setLastHeartbeat(null);
        job.setDispatchedAt(null);
        job.setProcessingStartedAt(null);

        jobRepository.save(job);

        logger.info("Moderation watchdog: retrying job id={}, entry={}, attempt={}/{}, previous={}, reason={}",
                job.getId(), job.getEntryId(), attempt, job.getMaxRetries(),
                previousStatus, reason);
    }

    void killJob(ModerationJob job, String reason) {
        ModerationJobStatus previousStatus = job.getStatus();

        job.setStatus(ModerationJobStatus.DEAD);
        job.setErrorMessage(reason);
        job.setCompletedAt(LocalDateTime.now());

        jobRepository.save(job);

        logger.error("Moderation watchdog: job DEAD — id={}, entry={}, tenant={}, "
                        + "retries={}/{}, previous={}, reason={}",
                job.getId(), job.getEntryId(), job.getTenantId(),
                job.getRetryCount(), job.getMaxRetries(),
                previousStatus, reason);
    }

    // ─── Accessors ──────────────────────────────────────────────

    public int getMaxRetries() {
        return config.getMaxRetries();
    }

    public Optional<ModerationJob> findActiveByTenantIdAndEntryId(String tenantId, String entryId) {
        return jobRepository.findActiveByTenantIdAndEntryId(tenantId, entryId);
    }

    /**
     * Cancels an active moderation job, marking it as FAILED.
     * Used when a new thumbnail/preview upload supersedes the existing job.
     */
    public void cancelJob(ModerationJob job, String reason) {
        ModerationJobStatus previousStatus = job.getStatus();
        job.setStatus(ModerationJobStatus.FAILED);
        job.setErrorMessage(reason);
        job.setCompletedAt(LocalDateTime.now());
        jobRepository.save(job);
        logger.info("Cancelled moderation job: id={}, entry={}, previous={}, reason={}",
                job.getId(), job.getEntryId(), previousStatus, reason);
    }

    public List<ModerationJob> findByStatus(ModerationJobStatus status) {
        return jobRepository.findAllByStatus(status, 100);
    }

    // ─── Callback handlers (called by ModerationCallbackController) ─

    /**
     * Marks a job as COMPLETED and acts on the moderation decision:
     * <ul>
     *   <li>APPROVE → entry transitions; for VIDEO, create TranscodingJob</li>
     *   <li>REJECT → entry marked REJECTED</li>
     *   <li>MANUAL_QUEUE → entry stays IN_REVIEW for human review</li>
     * </ul>
     */
    public Optional<ModerationJob> completeJob(String tenantId, String jobId,
                                                String decision, Double confidence,
                                                List<String> categoriesDetected,
                                                String reason, String step,
                                                String detectedLanguage) {
        Optional<ModerationJob> opt = jobRepository.findByTenantIdAndId(tenantId, jobId);
        if (opt.isEmpty()) {
            logger.warn("moderation completeJob: job not found id={}", jobId);
            return Optional.empty();
        }

        ModerationJob job = opt.get();
        ModerationDecision moderationDecision;
        try {
            moderationDecision = ModerationDecision.valueOf(decision);
        } catch (IllegalArgumentException e) {
            logger.warn("moderation completeJob: invalid decision '{}', defaulting to MANUAL_QUEUE", decision);
            moderationDecision = ModerationDecision.MANUAL_QUEUE;
        }

        job.setStatus(ModerationJobStatus.COMPLETED);
        job.setDecision(moderationDecision);
        job.setConfidence(confidence);
        job.setCategoriesDetected(categoriesDetected);
        job.setDecisionReason(reason);
        job.setDecidingStep(step);
        job.setDetectedLanguage(detectedLanguage);
        job.setCompletedAt(LocalDateTime.now());
        jobRepository.save(job);

        // Apply detected language to the entry/collection.
        // Source-of-truth rule: the moderation pipeline overrides the
        // user-declared default whenever it has a verdict. We skip on
        // REJECT (the entry will not be public anyway) and on null
        // (no signal — keep the user's default for human review).
        if (detectedLanguage != null && !detectedLanguage.isBlank()
                && moderationDecision != ModerationDecision.REJECT) {
            applyDetectedLanguage(job, detectedLanguage);
        }

        // Act on the decision
        switch (moderationDecision) {
            case APPROVE -> handleApproval(job);
            case REJECT -> handleRejection(job);
            case MANUAL_QUEUE -> logger.info("moderation completeJob: job={} queued for manual review, reason={}",
                    jobId, reason);
        }

        logger.info("moderation completeJob: job COMPLETED — id={}, decision={}, confidence={}, "
                        + "categories={}, entry={}, step={}, detectedLanguage={}",
                jobId, moderationDecision, confidence, categoriesDetected,
                job.getEntryId(), step, detectedLanguage);
        return Optional.of(job);
    }

    /**
     * Writes the AI-detected content language onto the entry or collection.
     * Called from {@link #completeJob} for any non-REJECT verdict that
     * carries a detected language. This is the source-of-truth path — the
     * uploader's declared default is overwritten.
     */
    private void applyDetectedLanguage(ModerationJob job, String detectedLanguage) {
        if (job.getEntryType() == EntryType.COLLECTION) {
            collectionRepository.findByTenantIdAndId(job.getTenantId(), job.getEntryId())
                    .ifPresent(collection -> {
                        collection.setContentLanguage(detectedLanguage);
                        collectionRepository.save(collection);
                        logger.info("moderation: collection {} contentLanguage → {} (AI-detected)",
                                collection.getId(), detectedLanguage);
                    });
        } else {
            entryRepository.findByTenantIdAndId(job.getTenantId(), job.getEntryId())
                    .ifPresent(entry -> {
                        entry.setContentLanguage(detectedLanguage);
                        entryRepository.save(entry);
                        logger.info("moderation: entry {} contentLanguage → {} (AI-detected)",
                                entry.getId(), detectedLanguage);
                    });
        }
    }

    /**
     * On approval: transition entry to APPROVED and create a TranscodingJob for videos.
     */
    private void handleApproval(ModerationJob job) {
        if (job.getEntryType() == EntryType.COLLECTION) {
            handleCollectionApproval(job);
            return;
        }
        entryRepository.findByTenantIdAndId(job.getTenantId(), job.getEntryId())
                .ifPresent(entry -> {
                    EntryStatus previous = entry.getStatus();
                    entry.setStatus(EntryStatus.APPROVED);
                    entry.getStatusHistory().add(
                            new org.earnlumens.mediastore.domain.media.model.StatusChangeRecord(
                                    previous, EntryStatus.APPROVED, "EarnLumens", job.getDecisionReason()));
                    entryRepository.save(entry);
                    logger.info("moderation: entry {} approved → status=APPROVED", entry.getId());

                    if (entry.getType() == org.earnlumens.mediastore.domain.media.model.EntryType.VIDEO) {
                        createTranscodingJobForEntry(job.getTenantId(), entry);
                    }

                    // Thumbnail processing runs for EVERY approved entry (any type),
                    // for thumbnail and (when present) preview images. Best-effort:
                    // a failure never blocks the entry — the original is served as-is.
                    try {
                        thumbnailJobService.enqueueForEntry(job.getTenantId(), entry);
                    } catch (Exception e) {
                        logger.warn("moderation: failed to enqueue thumbnail jobs for entry={} — {}",
                                entry.getId(), e.getMessage());
                    }
                });
    }

    private void handleCollectionApproval(ModerationJob job) {
        collectionRepository.findByTenantIdAndId(job.getTenantId(), job.getEntryId())
                .ifPresent(collection -> {
                    collection.setStatus(CollectionStatus.PUBLISHED);
                    collection.setPublishedAt(java.time.LocalDateTime.now());
                    collectionRepository.save(collection);
                    logger.info("moderation: collection {} approved → status=PUBLISHED", collection.getId());

                    // Cover-thumbnail processing for the collection grid card.
                    try {
                        thumbnailJobService.enqueueForCollection(job.getTenantId(), collection);
                    } catch (Exception e) {
                        logger.warn("moderation: failed to enqueue cover thumbnail job for collection={} — {}",
                                collection.getId(), e.getMessage());
                    }
                });
    }

    /**
     * Creates a TranscodingJob for a moderation-approved VIDEO entry.
     * The existing transcoding dispatcher/watchdog pipeline picks it up.
     */
    private void createTranscodingJobForEntry(String tenantId, Entry entry) {
        // Check if there's already an active transcoding job
        if (transcodingJobService.findLatestByTenantIdAndEntryId(tenantId, entry.getId()).isPresent()) {
            logger.debug("moderation: skipping TranscodingJob creation — active job exists for entry={}",
                    entry.getId());
            return;
        }

        // Look up the FULL asset (still UPLOADED because transcoding was deferred)
        var optAsset = assetRepository.findByTenantIdAndEntryIdAndKindAndStatus(
                tenantId, entry.getId(), MediaKind.FULL, AssetStatus.UPLOADED);
        if (optAsset.isEmpty()) {
            logger.warn("moderation: no UPLOADED FULL asset found for VIDEO entry={}", entry.getId());
            return;
        }

        Asset asset = optAsset.get();
        TranscodingJob job = new TranscodingJob();
        job.setTenantId(tenantId);
        job.setEntryId(entry.getId());
        job.setAssetId(asset.getId());
        job.setSourceR2Key(asset.getR2Key());
        job.setStatus(TranscodingJobStatus.PENDING);
        job.setRetryCount(0);
        job.setMaxRetries(transcodingJobService.getMaxRetries());
        transcodingJobService.createJob(job);
        logger.info("moderation: created TranscodingJob for approved VIDEO entry={}, asset={}",
                entry.getId(), asset.getId());
    }

    private void handleRejection(ModerationJob job) {
        if (job.getEntryType() == EntryType.COLLECTION) {
            handleCollectionRejection(job);
            return;
        }
        entryRepository.findByTenantIdAndId(job.getTenantId(), job.getEntryId())
                .ifPresent(entry -> {
                    EntryStatus previous = entry.getStatus();
                    entry.setStatus(EntryStatus.REJECTED);
                    entry.setModerationFeedback(job.getDecisionReason());
                    entry.getStatusHistory().add(
                            new org.earnlumens.mediastore.domain.media.model.StatusChangeRecord(
                                    previous, EntryStatus.REJECTED, "EarnLumens", job.getDecisionReason()));
                    entryRepository.save(entry);
                    logger.info("moderation: entry {} rejected — status=REJECTED, reason={}",
                            entry.getId(), job.getDecisionReason());
                });
    }

    private void handleCollectionRejection(ModerationJob job) {
        collectionRepository.findByTenantIdAndId(job.getTenantId(), job.getEntryId())
                .ifPresent(collection -> {
                    collection.setStatus(CollectionStatus.REJECTED);
                    collection.setModerationFeedback(job.getDecisionReason());
                    collectionRepository.save(collection);
                    logger.info("moderation: collection {} rejected — status=REJECTED, reason={}",
                            collection.getId(), job.getDecisionReason());
                });
    }

    /**
     * Handles a FAILED callback from the moderation worker.
     */
    public Optional<ModerationJob> failJob(String tenantId, String jobId, String errorMessage) {
        Optional<ModerationJob> opt = jobRepository.findByTenantIdAndId(tenantId, jobId);
        if (opt.isEmpty()) {
            logger.warn("moderation failJob: job not found id={}", jobId);
            return Optional.empty();
        }

        ModerationJob job = opt.get();

        if (job.getRetryCount() < job.getMaxRetries()) {
            retryJob(job, errorMessage);
        } else {
            killJob(job, errorMessage);
        }

        return Optional.of(job);
    }
}
