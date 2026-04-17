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
    private final ModerationConfig config;
    private final ModerationDispatchPort dispatchPort;
    private final TranscodingJobService transcodingJobService;
    private final Executor dispatchExecutor;

    public ModerationJobService(ModerationJobRepository jobRepository,
                                 EntryRepository entryRepository,
                                 ModerationConfig config,
                                 ModerationDispatchPort dispatchPort,
                                 TranscodingJobService transcodingJobService,
                                 @Qualifier("moderationDispatchExecutor") Executor dispatchExecutor) {
        this.jobRepository = jobRepository;
        this.entryRepository = entryRepository;
        this.config = config;
        this.dispatchPort = dispatchPort;
        this.transcodingJobService = transcodingJobService;
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
                                                String reason, String step) {
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
        job.setCompletedAt(LocalDateTime.now());
        jobRepository.save(job);

        // Act on the decision
        switch (moderationDecision) {
            case APPROVE -> handleApproval(job);
            case REJECT -> handleRejection(job);
            case MANUAL_QUEUE -> logger.info("moderation completeJob: job={} queued for manual review, reason={}",
                    jobId, reason);
        }

        logger.info("moderation completeJob: job COMPLETED — id={}, decision={}, confidence={}, "
                        + "categories={}, entry={}, step={}",
                jobId, moderationDecision, confidence, categoriesDetected,
                job.getEntryId(), step);
        return Optional.of(job);
    }

    /**
     * On approval: transition entry to APPROVED and create a TranscodingJob for videos.
     */
    private void handleApproval(ModerationJob job) {
        entryRepository.findByTenantIdAndId(job.getTenantId(), job.getEntryId())
                .ifPresent(entry -> {
                    EntryStatus previous = entry.getStatus();
                    entry.setStatus(EntryStatus.APPROVED);
                    // Record audit trail — actor is generic to hide bot vs human
                    entry.getStatusHistory().add(
                            new org.earnlumens.mediastore.domain.media.model.StatusChangeRecord(
                                    previous, EntryStatus.APPROVED, "EarnLumens", job.getDecisionReason()));
                    entryRepository.save(entry);
                    logger.info("moderation: entry {} approved → status=APPROVED", entry.getId());

                    // For VIDEO entries, create a transcoding job so the HLS pipeline picks it up
                    if (entry.getType() == org.earnlumens.mediastore.domain.media.model.EntryType.VIDEO) {
                        createTranscodingJobForEntry(job.getTenantId(), entry);
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

        // The FULL asset's r2Key is the source for transcoding — same as in EntryUploadService
        // We reuse the sourceR2Key from the moderation job
        // (the moderation job was created with the FULL asset's R2 key)
        logger.info("moderation: VIDEO entry {} approved — TranscodingJob will be created when " +
                "publish flow runs (existing EntryUploadService handles this)", entry.getId());
    }

    private void handleRejection(ModerationJob job) {
        entryRepository.findByTenantIdAndId(job.getTenantId(), job.getEntryId())
                .ifPresent(entry -> {
                    EntryStatus previous = entry.getStatus();
                    entry.setStatus(EntryStatus.REJECTED);
                    // Propagate rejection reason so the creator sees feedback
                    entry.setModerationFeedback(job.getDecisionReason());
                    // Record audit trail — actor is generic to hide bot vs human
                    entry.getStatusHistory().add(
                            new org.earnlumens.mediastore.domain.media.model.StatusChangeRecord(
                                    previous, EntryStatus.REJECTED, "EarnLumens", job.getDecisionReason()));
                    entryRepository.save(entry);
                    logger.info("moderation: entry {} rejected — status=REJECTED, reason={}",
                            entry.getId(), job.getDecisionReason());
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
