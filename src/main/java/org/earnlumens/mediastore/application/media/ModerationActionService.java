package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.ModerationCase;
import org.earnlumens.mediastore.domain.media.model.ModerationJob;
import org.earnlumens.mediastore.domain.media.model.StatusChangeRecord;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.media.repository.ModerationJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Human-moderator actions on entries, invoked by admin-api through the
 * internal moderation surface (MYCELIUM-RESILIENCE.md H3 / N9 / S-05).
 *
 * <p>Two separate queues, two separate objects, no "scope" field (S-05):
 * <ul>
 *   <li><b>Content</b> actions act on the live document — approve / reject
 *       an {@code IN_REVIEW} entry, take down live content, reinstate.</li>
 *   <li><b>Pending-version</b> actions act on {@code Entry.pendingVersion}
 *       only: approving projects the edit, rejecting discards it. Neither
 *       touches {@code status} nor opens a dispute (D-13).</li>
 * </ul>
 * Everything is loaded and saved through the full {@code Entry} model, so an
 * administrative write can never drop fields the admin document model does
 * not know about.
 */
@Service
public class ModerationActionService {

    private static final Logger logger = LoggerFactory.getLogger(ModerationActionService.class);

    private final EntryRepository entryRepository;
    private final ModerationJobRepository jobRepository;
    private final ContentVersionService contentVersionService;
    private final ContentModerationService contentModerationService;
    private final ModerationJobService moderationJobService;

    public ModerationActionService(EntryRepository entryRepository,
                                   ModerationJobRepository jobRepository,
                                   ContentVersionService contentVersionService,
                                   ContentModerationService contentModerationService,
                                   ModerationJobService moderationJobService) {
        this.entryRepository = entryRepository;
        this.jobRepository = jobRepository;
        this.contentVersionService = contentVersionService;
        this.contentModerationService = contentModerationService;
        this.moderationJobService = moderationJobService;
    }

    // ─── Content (live document) ─────────────────────────────────

    /**
     * Approves an {@code IN_REVIEW} entry. A first submission goes to
     * {@code APPROVED} (the creator publishes). An entry that was live and
     * entered review by report escalation returns to its previous public
     * status; if it carried a {@code PREVENTIVE} block, the approval is the
     * human review that lifts it.
     */
    public Entry approveContent(String tenantId, String entryId, String actor) {
        Entry entry = require(tenantId, entryId);
        if (entry.getStatus() != EntryStatus.IN_REVIEW) {
            throw new IllegalStateException("Entry is not in IN_REVIEW status");
        }
        if (entry.isBlocked()) {
            Entry reinstated = contentModerationService.reinstateEntry(tenantId, entryId, actor);
            moderationJobService.ensurePostApprovalMediaPipeline(tenantId, reinstated);
            return reinstated;
        }
        EntryStatus target = ContentAccessPolicy.isPubliclyVisible(entry.getPreviousStatus())
                ? entry.getPreviousStatus() : EntryStatus.APPROVED;
        transition(entry, target, actor, "Approved by moderator");
        if (target != EntryStatus.APPROVED) {
            entry.setPreviousStatus(null);
        }
        entry.setModerationFeedback(null);
        entry.setUpdatedAt(LocalDateTime.now());
        Entry saved = entryRepository.save(entry);
        moderationJobService.ensurePostApprovalMediaPipeline(tenantId, saved);
        logger.info("moderation(admin): APPROVED entry={} tenant={} → {} by={}", entryId, tenantId, target, actor);
        return saved;
    }

    /**
     * Rejects an {@code IN_REVIEW} entry. If the entry had been live (report
     * escalation of published content) this is a {@code CONTENT_TAKEDOWN}:
     * block, case and disputes for its buyers (D-13). A never-published
     * submission is simply {@code REJECTED}.
     */
    public Entry rejectContent(String tenantId, String entryId, String justification, String actor) {
        requireJustification(justification);
        Entry entry = require(tenantId, entryId);
        if (entry.getStatus() != EntryStatus.IN_REVIEW) {
            throw new IllegalStateException("Entry is not in IN_REVIEW status");
        }
        boolean wasLive = ContentAccessPolicy.isPubliclyVisible(entry.getPreviousStatus());
        transition(entry, EntryStatus.REJECTED, actor, justification);
        entry.setModerationFeedback(justification);
        entry.setUpdatedAt(LocalDateTime.now());
        Entry saved = entryRepository.save(entry);
        logger.info("moderation(admin): REJECTED entry={} tenant={} by={} wasLive={}", entryId, tenantId, actor, wasLive);
        if (wasLive || saved.isBlocked()) {
            contentModerationService.blockRejectedLiveEntry(tenantId, saved, justification, actor);
        }
        return saved;
    }

    /** Explicit "remove content" (D-13 {@code CONTENT_TAKEDOWN}). */
    public ModerationCase takedown(String tenantId, String entryId, String justification, String actor) {
        requireJustification(justification);
        return contentModerationService.takedownEntry(tenantId, entryId, justification, actor);
    }

    public Entry reinstate(String tenantId, String entryId, String actor) {
        Entry entry = contentModerationService.reinstateEntry(tenantId, entryId, actor);
        if (ContentAccessPolicy.isPubliclyVisible(entry.getStatus())) {
            moderationJobService.ensurePostApprovalMediaPipeline(tenantId, entry);
        }
        return entry;
    }

    // ─── Pending version (staged edit) ───────────────────────────

    public Entry approvePendingVersion(String tenantId, String entryId, String actor) {
        Entry entry = require(tenantId, entryId);
        if (entry.getPendingVersion() == null) {
            throw new IllegalStateException("Entry has no pending edit");
        }
        closeActiveJob(tenantId, entry, "Pending edit approved by moderator " + actor);
        Entry saved = contentVersionService.applyPendingVersion(tenantId, entry);
        logger.info("moderation(admin): pending edit APPROVED entry={} tenant={} by={}", entryId, tenantId, actor);
        return saved;
    }

    public Entry rejectPendingVersion(String tenantId, String entryId, String justification, String actor) {
        requireJustification(justification);
        Entry entry = require(tenantId, entryId);
        if (entry.getPendingVersion() == null) {
            throw new IllegalStateException("Entry has no pending edit");
        }
        closeActiveJob(tenantId, entry, "Pending edit rejected by moderator " + actor);
        Entry saved = contentVersionService.rejectPendingVersion(tenantId, entry, justification);
        logger.info("moderation(admin): pending edit REJECTED entry={} tenant={} by={}", entryId, tenantId, actor);
        return saved;
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private void closeActiveJob(String tenantId, Entry entry, String reason) {
        jobRepository.findActiveByTenantIdAndEntryId(tenantId, entry.getId())
                .filter(ModerationJob::isPendingVersionScope)
                .ifPresent(job -> moderationJobService.cancelJob(job, reason));
    }

    private void transition(Entry entry, EntryStatus to, String actor, String reason) {
        EntryStatus from = entry.getStatus();
        entry.getStatusHistory().add(new StatusChangeRecord(from, to, actor, reason));
        entry.setStatus(to);
    }

    private Entry require(String tenantId, String entryId) {
        return entryRepository.findByTenantIdAndId(tenantId, entryId)
                .orElseThrow(() -> new IllegalArgumentException("Entry not found"));
    }

    private static void requireJustification(String justification) {
        if (justification == null || justification.isBlank()) {
            throw new IllegalArgumentException("Justification is required");
        }
    }
}
