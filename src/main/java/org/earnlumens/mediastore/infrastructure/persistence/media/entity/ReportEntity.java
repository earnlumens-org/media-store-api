package org.earnlumens.mediastore.infrastructure.persistence.media.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * MongoDB document for the {@code reports} collection.
 *
 * <p>Indexes:
 * <ul>
 *   <li><b>idx_tenant_entry</b> — find all reports for an entry.</li>
 *   <li><b>idx_tenant_reporter_entry</b> — unique: one report per user per entry.</li>
 *   <li><b>idx_tenant_resolution_priority</b> — moderator queue: open reports by priority desc.</li>
 *   <li><b>idx_tenant_reporter_created</b> — rate-limit check: recent reports by user.</li>
 *   <li><b>idx_tenant_creator</b> — creator history: all reports against a creator's entries.</li>
 * </ul>
 */
@Document(collection = "reports")
@CompoundIndex(name = "idx_tenant_entry", def = "{'tenantId': 1, 'entryId': 1}")
@CompoundIndex(name = "idx_tenant_reporter_entry", def = "{'tenantId': 1, 'reporterUserId': 1, 'entryId': 1}", unique = true)
@CompoundIndex(name = "idx_tenant_resolution_priority", def = "{'tenantId': 1, 'resolution': 1, 'priorityScore': -1}")
@CompoundIndex(name = "idx_tenant_reporter_created", def = "{'tenantId': 1, 'reporterUserId': 1, 'createdAt': -1}")
@CompoundIndex(name = "idx_tenant_creator", def = "{'tenantId': 1, 'creatorUserId': 1}")
public class ReportEntity {

    @Id
    private String id;

    @NotBlank
    private String tenantId;

    @NotBlank
    private String entryId;

    /** Owner of the reported entry (denormalized for creator-history queries). */
    @NotBlank
    private String creatorUserId;

    @NotBlank
    private String reporterUserId;

    private String reporterUsername;

    @NotBlank
    private String reason;

    @NotBlank
    private String severity;

    @Size(max = 500)
    private String comment;

    /** Embedded snapshot of the entry at the time of report. */
    private SnapshotEmbeddable snapshot;

    private int priorityScore;

    @NotBlank
    private String resolution;

    private String resolvedBy;

    private LocalDateTime resolvedAt;

    @CreatedDate
    private LocalDateTime createdAt;

    public ReportEntity() {}

    // ── Getters & setters ──────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getEntryId() { return entryId; }
    public void setEntryId(String entryId) { this.entryId = entryId; }

    public String getCreatorUserId() { return creatorUserId; }
    public void setCreatorUserId(String creatorUserId) { this.creatorUserId = creatorUserId; }

    public String getReporterUserId() { return reporterUserId; }
    public void setReporterUserId(String reporterUserId) { this.reporterUserId = reporterUserId; }

    public String getReporterUsername() { return reporterUsername; }
    public void setReporterUsername(String reporterUsername) { this.reporterUsername = reporterUsername; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public SnapshotEmbeddable getSnapshot() { return snapshot; }
    public void setSnapshot(SnapshotEmbeddable snapshot) { this.snapshot = snapshot; }

    public int getPriorityScore() { return priorityScore; }
    public void setPriorityScore(int priorityScore) { this.priorityScore = priorityScore; }

    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }

    public String getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(String resolvedBy) { this.resolvedBy = resolvedBy; }

    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    /** Embeddable snapshot sub-document. */
    public static class SnapshotEmbeddable {
        private String title;
        private String description;
        private String thumbnailR2Key;
        private String authorUsername;

        public SnapshotEmbeddable() {}

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getThumbnailR2Key() { return thumbnailR2Key; }
        public void setThumbnailR2Key(String thumbnailR2Key) { this.thumbnailR2Key = thumbnailR2Key; }
        public String getAuthorUsername() { return authorUsername; }
        public void setAuthorUsername(String authorUsername) { this.authorUsername = authorUsername; }
    }
}
