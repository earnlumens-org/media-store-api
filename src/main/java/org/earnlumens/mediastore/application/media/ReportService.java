package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.model.*;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.ReportEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.ReportMongoRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Handles report creation, priority scoring, and threshold-based escalation.
 *
 * <h3>Severity mapping (from reason):</h3>
 * <ul>
 *   <li>HIGH: SCAM, PHISHING, MALWARE, DOXXING, EXTREME_VIOLENCE, ILLEGAL, CSAM</li>
 *   <li>MEDIUM: HATE_SPEECH, NSFW, SPAM, IMPERSONATION</li>
 *   <li>LOW: COPYRIGHT, STOLEN_CONTENT, DUPLICATE, OFF_TOPIC, OTHER</li>
 * </ul>
 *
 * <h3>Priority score (0–100) factors:</h3>
 * <ol>
 *   <li>Severity of the reason (0–30)</li>
 *   <li>Reporter reputation (0–25)</li>
 *   <li>Creator prior history (0–20)</li>
 *   <li>Distinct report count on this entry (0–15)</li>
 *   <li>Report accumulation velocity (0–10)</li>
 * </ol>
 *
 * <h3>Escalation threshold:</h3>
 * Entry transitions to IN_REVIEW when any report reaches priority ≥ 60,
 * OR when 3+ distinct users have reported it.
 */
@Service
public class ReportService {

    private static final Logger logger = LoggerFactory.getLogger(ReportService.class);

    /** Max reports a user can file per day. */
    private static final int DAILY_REPORT_LIMIT = 5;

    /** Distinct-user threshold for automatic escalation. */
    private static final int ESCALATION_REPORT_COUNT = 3;

    /** Priority score threshold for immediate escalation. */
    private static final int ESCALATION_PRIORITY_THRESHOLD = 60;

    private static final Set<ReportReason> HIGH_SEVERITY = EnumSet.of(
            ReportReason.SCAM, ReportReason.PHISHING, ReportReason.MALWARE,
            ReportReason.DOXXING, ReportReason.EXTREME_VIOLENCE, ReportReason.ILLEGAL,
            ReportReason.CSAM
    );
    private static final Set<ReportReason> MEDIUM_SEVERITY = EnumSet.of(
            ReportReason.HATE_SPEECH, ReportReason.NSFW, ReportReason.SPAM,
            ReportReason.IMPERSONATION
    );

    private final ReportMongoRepository reportRepository;
    private final EntryRepository entryRepository;

    public ReportService(ReportMongoRepository reportRepository,
                         EntryRepository entryRepository) {
        this.reportRepository = reportRepository;
        this.entryRepository = entryRepository;
    }

    // ── Public API ─────────────────────────────────────────────

    /**
     * Submit a new report. Returns the created report entity.
     *
     * @throws IllegalArgumentException on invalid input
     * @throws IllegalStateException    on rate-limit or duplicate
     */
    public ReportEntity submitReport(String tenantId, String reporterUserId,
                                      String reporterUsername, String entryId,
                                      ReportReason reason, String comment) {

        // 1. Validate entry exists and is published
        Entry entry = entryRepository.findByTenantIdAndId(tenantId, entryId)
                .orElseThrow(() -> new IllegalArgumentException("ENTRY_NOT_FOUND"));

        if (entry.getStatus() != EntryStatus.PUBLISHED && entry.getStatus() != EntryStatus.APPROVED) {
            throw new IllegalArgumentException("ENTRY_NOT_REPORTABLE");
        }

        // 2. Prevent self-report
        if (entry.getUserId().equals(reporterUserId)) {
            throw new IllegalArgumentException("CANNOT_REPORT_OWN_CONTENT");
        }

        // 3. Duplicate check
        if (reportRepository.existsByTenantIdAndReporterUserIdAndEntryId(tenantId, reporterUserId, entryId)) {
            throw new IllegalStateException("ALREADY_REPORTED");
        }

        // 4. Rate limit: 5 reports per day per user
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
        long recentCount = reportRepository.countByTenantIdAndReporterUserIdAndCreatedAtAfter(
                tenantId, reporterUserId, yesterday);
        if (recentCount >= DAILY_REPORT_LIMIT) {
            throw new IllegalStateException("DAILY_REPORT_LIMIT_REACHED");
        }

        // 5. Compute severity
        ReportSeverity severity = computeSeverity(reason);

        // 6. Compute priority score
        int priority = computePriority(tenantId, entryId, entry.getUserId(),
                reporterUserId, severity);

        // 7. Build snapshot
        ReportEntity.SnapshotEmbeddable snapshot = new ReportEntity.SnapshotEmbeddable();
        snapshot.setTitle(entry.getTitle());
        snapshot.setDescription(truncate(entry.getDescription(), 500));
        snapshot.setThumbnailR2Key(entry.getThumbnailR2Key());
        snapshot.setAuthorUsername(entry.getAuthorUsername());

        // 8. Save report
        ReportEntity report = new ReportEntity();
        report.setTenantId(tenantId);
        report.setEntryId(entryId);
        report.setCreatorUserId(entry.getUserId());
        report.setReporterUserId(reporterUserId);
        report.setReporterUsername(reporterUsername);
        report.setReason(reason.name());
        report.setSeverity(severity.name());
        report.setComment(truncate(comment, 500));
        report.setSnapshot(snapshot);
        report.setPriorityScore(priority);
        report.setResolution(ReportResolution.OPEN.name());
        report.setCreatedAt(LocalDateTime.now());

        ReportEntity saved = reportRepository.save(report);
        logger.info("Report created: id={}, entry={}, reason={}, severity={}, priority={}, reporter={}",
                saved.getId(), entryId, reason, severity, priority, reporterUsername);

        // 9. Check escalation: re-count after saving
        long totalReports = reportRepository.countByTenantIdAndEntryId(tenantId, entryId);

        if (shouldEscalate(priority, totalReports, entry)) {
            escalateToReview(entry, tenantId, totalReports);
        }

        return saved;
    }

    // ── Priority scoring ───────────────────────────────────────

    /**
     * Score: 0–100.
     * <ol>
     *   <li>Severity: HIGH=30, MEDIUM=18, LOW=8</li>
     *   <li>Reporter reputation: 0–25 (high rep = high score, many dismissed = low)</li>
     *   <li>Creator history: 0–20 (prior sanctions = high score)</li>
     *   <li>Entry report count: 0–15</li>
     *   <li>Velocity: 0–10 (many reports in short time = high)</li>
     * </ol>
     */
    int computePriority(String tenantId, String entryId, String creatorUserId,
                        String reporterUserId, ReportSeverity severity) {
        int score = 0;

        // Factor 1: Severity (0–30)
        score += switch (severity) {
            case HIGH -> 30;
            case MEDIUM -> 18;
            case LOW -> 8;
        };

        // Factor 2: Reporter reputation (0–25)
        score += computeReporterReputation(tenantId, reporterUserId);

        // Factor 3: Creator history (0–20)
        score += computeCreatorHistory(tenantId, creatorUserId);

        // Factor 4: Distinct report count on this entry (0–15)
        long entryReports = reportRepository.countByTenantIdAndEntryId(tenantId, entryId);
        score += Math.min(15, (int) (entryReports * 5)); // 5 pts per report, capped at 15

        // Factor 5: Velocity — reports in last hour (0–10)
        List<ReportEntity> recentForEntry = reportRepository.findByTenantIdAndEntryId(tenantId, entryId);
        long reportsLastHour = recentForEntry.stream()
                .filter(r -> r.getCreatedAt() != null &&
                        Duration.between(r.getCreatedAt(), LocalDateTime.now()).toHours() < 1)
                .count();
        score += Math.min(10, (int) (reportsLastHour * 4)); // 4 pts per report/hour, capped at 10

        return Math.min(100, score);
    }

    /**
     * Reporter reputation: 0–25.
     * Starts at 15 (neutral). Goes up with accepted reports, down with dismissed.
     */
    private int computeReporterReputation(String tenantId, String reporterUserId) {
        long totalReports = reportRepository.countByTenantIdAndReporterUserId(tenantId, reporterUserId);
        if (totalReports == 0) {
            return 15; // New reporter — neutral trust
        }

        long dismissed = reportRepository.countByTenantIdAndReporterUserIdAndResolution(
                tenantId, reporterUserId, ReportResolution.DISMISSED.name());
        long actioned = reportRepository.countByTenantIdAndReporterUserIdAndResolution(
                tenantId, reporterUserId, ReportResolution.REMOVED.name())
                + reportRepository.countByTenantIdAndReporterUserIdAndResolution(
                tenantId, reporterUserId, ReportResolution.SANCTIONED.name());

        // Ratio-based: more actioned = higher rep, more dismissed = lower
        double actionRate = (double) actioned / totalReports;
        double dismissRate = (double) dismissed / totalReports;

        int rep = 15;
        rep += (int) (actionRate * 12);  // up to +12 for perfect track record
        rep -= (int) (dismissRate * 15); // up to -15 for all dismissed
        return Math.max(0, Math.min(25, rep));
    }

    /**
     * Creator history: 0–20.
     * More prior sanctions = higher modifier.
     */
    private int computeCreatorHistory(String tenantId, String creatorUserId) {
        long totalReportsAgainst = reportRepository.countByTenantIdAndCreatorUserId(tenantId, creatorUserId);
        long priorSanctions = reportRepository.countByTenantIdAndCreatorUserIdAndResolutionIn(
                tenantId, creatorUserId, List.of(ReportResolution.REMOVED.name(), ReportResolution.SANCTIONED.name()));

        int score = 0;
        score += Math.min(8, (int) (totalReportsAgainst * 2)); // 2 per report, max 8
        score += Math.min(12, (int) (priorSanctions * 6));     // 6 per sanction, max 12
        return Math.min(20, score);
    }

    // ── Severity ───────────────────────────────────────────────

    ReportSeverity computeSeverity(ReportReason reason) {
        if (HIGH_SEVERITY.contains(reason)) return ReportSeverity.HIGH;
        if (MEDIUM_SEVERITY.contains(reason)) return ReportSeverity.MEDIUM;
        return ReportSeverity.LOW;
    }

    // ── Escalation ─────────────────────────────────────────────

    private boolean shouldEscalate(int priority, long totalReports, Entry entry) {
        // Don't re-escalate if already in review
        if (entry.getStatus() == EntryStatus.IN_REVIEW) return false;

        return priority >= ESCALATION_PRIORITY_THRESHOLD
                || totalReports >= ESCALATION_REPORT_COUNT;
    }

    private void escalateToReview(Entry entry, String tenantId, long reportCount) {
        EntryStatus prev = entry.getStatus();
        entry.setPreviousStatus(prev);
        entry.setStatus(EntryStatus.IN_REVIEW);
        entry.getStatusHistory().add(new StatusChangeRecord(
                prev, EntryStatus.IN_REVIEW,
                "EarnLumens",
                "Escalated by user reports (" + reportCount + " reports)"
        ));
        entry.setUpdatedAt(LocalDateTime.now());
        entryRepository.save(entry);

        logger.warn("Entry {} escalated to IN_REVIEW by user reports (count={}, tenant={})",
                entry.getId(), reportCount, tenantId);
    }

    // ── Helpers ────────────────────────────────────────────────

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
