package org.earnlumens.mediastore.infrastructure.persistence.media.entity;

import jakarta.validation.constraints.NotBlank;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MongoDB document for tracking content moderation jobs.
 *
 * <p>Indexes are designed for the two critical query patterns:
 * <ul>
 *   <li>Dispatcher: find PENDING jobs ordered by creation time</li>
 *   <li>Watchdog: find DISPATCHED/PROCESSING jobs with stale heartbeat</li>
 * </ul>
 */
@Document(collection = "moderation_jobs")
@CompoundIndex(name = "idx_status_createdAt", def = "{'status': 1, 'createdAt': 1}")
@CompoundIndex(name = "idx_tenantId_entryId_status", def = "{'tenantId': 1, 'entryId': 1, 'status': 1}")
@CompoundIndex(name = "idx_status_lastHeartbeat", def = "{'status': 1, 'lastHeartbeat': 1}")
public class ModerationJobEntity {

    @Id
    private String id;

    @NotBlank
    private String tenantId;

    @NotBlank
    private String entryId;

    @NotBlank
    private String sourceR2Key;

    private String sourceContentType;

    private String sourceFileName;

    private String thumbnailR2Key;

    private String previewR2Key;

    @NotBlank
    private String entryType;

    private String entryTitle;
    private String entryDescription;
    private String entryTags;

    private String resourceContent;

    @NotBlank
    private String status;

    private String decision;
    private Double confidence;
    private List<String> categoriesDetected;
    private String decisionReason;
    private String decidingStep;

    private int retryCount;
    private int maxRetries;
    private String errorMessage;

    private LocalDateTime lastHeartbeat;
    private LocalDateTime dispatchedAt;
    private LocalDateTime processingStartedAt;
    private LocalDateTime completedAt;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    // ── Getters / Setters ──────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getEntryId() { return entryId; }
    public void setEntryId(String entryId) { this.entryId = entryId; }

    public String getSourceR2Key() { return sourceR2Key; }
    public void setSourceR2Key(String sourceR2Key) { this.sourceR2Key = sourceR2Key; }

    public String getSourceContentType() { return sourceContentType; }
    public void setSourceContentType(String sourceContentType) { this.sourceContentType = sourceContentType; }

    public String getSourceFileName() { return sourceFileName; }
    public void setSourceFileName(String sourceFileName) { this.sourceFileName = sourceFileName; }

    public String getThumbnailR2Key() { return thumbnailR2Key; }
    public void setThumbnailR2Key(String thumbnailR2Key) { this.thumbnailR2Key = thumbnailR2Key; }

    public String getPreviewR2Key() { return previewR2Key; }
    public void setPreviewR2Key(String previewR2Key) { this.previewR2Key = previewR2Key; }

    public String getEntryType() { return entryType; }
    public void setEntryType(String entryType) { this.entryType = entryType; }

    public String getEntryTitle() { return entryTitle; }
    public void setEntryTitle(String entryTitle) { this.entryTitle = entryTitle; }

    public String getEntryDescription() { return entryDescription; }
    public void setEntryDescription(String entryDescription) { this.entryDescription = entryDescription; }

    public String getEntryTags() { return entryTags; }
    public void setEntryTags(String entryTags) { this.entryTags = entryTags; }

    public String getResourceContent() { return resourceContent; }
    public void setResourceContent(String resourceContent) { this.resourceContent = resourceContent; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public List<String> getCategoriesDetected() { return categoriesDetected; }
    public void setCategoriesDetected(List<String> categoriesDetected) { this.categoriesDetected = categoriesDetected; }

    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }

    public String getDecidingStep() { return decidingStep; }
    public void setDecidingStep(String decidingStep) { this.decidingStep = decidingStep; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(LocalDateTime lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }

    public LocalDateTime getDispatchedAt() { return dispatchedAt; }
    public void setDispatchedAt(LocalDateTime dispatchedAt) { this.dispatchedAt = dispatchedAt; }

    public LocalDateTime getProcessingStartedAt() { return processingStartedAt; }
    public void setProcessingStartedAt(LocalDateTime processingStartedAt) { this.processingStartedAt = processingStartedAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
