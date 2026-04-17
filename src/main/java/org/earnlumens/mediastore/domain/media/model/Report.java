package org.earnlumens.mediastore.domain.media.model;

import java.time.LocalDateTime;

/**
 * A user-submitted report against a published entry.
 *
 * <p>Reports are the last line of defense: content that passed AI moderation
 * and human review can still be flagged by the community. Reports accumulate
 * per entry and, once a threshold is reached, escalate the entry to IN_REVIEW.</p>
 */
public class Report {

    private String id;
    private String tenantId;
    private String entryId;

    /** ID of the user who filed the report. */
    private String reporterUserId;
    /** Username of the reporter (denormalized for quick display). */
    private String reporterUsername;

    /** Structured reason. */
    private ReportReason reason;
    /** Severity derived from the reason. */
    private ReportSeverity severity;
    /** Optional free-text detail from the reporter. */
    private String comment;

    /** Snapshot of the entry at the moment of the report. */
    private ReportSnapshot snapshot;

    /** Computed priority score (0–100). Higher = more urgent. */
    private int priorityScore;

    /** How this report was resolved. Starts as OPEN. */
    private ReportResolution resolution;
    /** Moderator who resolved this report (null while OPEN). */
    private String resolvedBy;
    /** When the report was resolved. */
    private LocalDateTime resolvedAt;

    private LocalDateTime createdAt;

    public Report() {}

    // ── Getters & Setters ──────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getEntryId() { return entryId; }
    public void setEntryId(String entryId) { this.entryId = entryId; }

    public String getReporterUserId() { return reporterUserId; }
    public void setReporterUserId(String reporterUserId) { this.reporterUserId = reporterUserId; }

    public String getReporterUsername() { return reporterUsername; }
    public void setReporterUsername(String reporterUsername) { this.reporterUsername = reporterUsername; }

    public ReportReason getReason() { return reason; }
    public void setReason(ReportReason reason) { this.reason = reason; }

    public ReportSeverity getSeverity() { return severity; }
    public void setSeverity(ReportSeverity severity) { this.severity = severity; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public ReportSnapshot getSnapshot() { return snapshot; }
    public void setSnapshot(ReportSnapshot snapshot) { this.snapshot = snapshot; }

    public int getPriorityScore() { return priorityScore; }
    public void setPriorityScore(int priorityScore) { this.priorityScore = priorityScore; }

    public ReportResolution getResolution() { return resolution; }
    public void setResolution(ReportResolution resolution) { this.resolution = resolution; }

    public String getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(String resolvedBy) { this.resolvedBy = resolvedBy; }

    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
