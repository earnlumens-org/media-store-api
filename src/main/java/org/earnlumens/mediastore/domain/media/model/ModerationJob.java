package org.earnlumens.mediastore.domain.media.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Domain model for a content moderation job.
 *
 * <p>Every uploaded entry gets exactly one ModerationJob. The job tracks
 * the full lifecycle from upload → dispatch → pipeline → decision.
 *
 * <p>Pipeline order (varies by entry type):
 * <ol>
 *   <li>ACRCloud — audio copyright detection (VIDEO, AUDIO)</li>
 *   <li>Gemini Flash — business rules + text analysis (ALL types)</li>
 * </ol>
 */
public class ModerationJob {

    private String id;
    private String tenantId;
    private String entryId;

    /** R2 key of the source file to moderate. */
    private String sourceR2Key;

    /** MIME type of the source file (e.g. "application/pdf", "audio/mpeg"). */
    private String sourceContentType;

    /** Original file name of the source file. */
    private String sourceFileName;

    /** R2 key of the thumbnail (if present). */
    private String thumbnailR2Key;

    /** R2 key of the preview (if present). */
    private String previewR2Key;

    /** Entry type determines which pipeline steps run. */
    private EntryType entryType;

    /** Entry metadata sent to Gemini for text-level analysis. */
    private String entryTitle;
    private String entryDescription;
    private String entryTags;

    /** Rich text body for RESOURCE entries. Sent to Gemini for content analysis. */
    private String resourceContent;

    private ModerationJobStatus status;

    /** Final decision from the moderation pipeline. */
    private ModerationDecision decision;

    /** Confidence score (0.0–1.0) from the pipeline. */
    private Double confidence;

    /** Categories detected (e.g., NSFW, COPYRIGHT_MUSIC, SPAM). */
    private List<String> categoriesDetected;

    /** Human-readable reason for the decision. */
    private String decisionReason;

    /** Which pipeline step produced the final decision. */
    private String decidingStep;

    /** Number of times this job has been retried after failure. */
    private int retryCount;

    /** Maximum retries before the job is marked DEAD. */
    private int maxRetries;

    /** Last error message from the moderation worker. */
    private String errorMessage;

    /** Periodically updated by the worker to prove it's still alive. */
    private LocalDateTime lastHeartbeat;

    /** When the job was dispatched to Cloud Run. */
    private LocalDateTime dispatchedAt;

    /** When the worker started processing. */
    private LocalDateTime processingStartedAt;

    /** When the job completed (success or final failure). */
    private LocalDateTime completedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public ModerationJob() {}

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

    public EntryType getEntryType() { return entryType; }
    public void setEntryType(EntryType entryType) { this.entryType = entryType; }

    public String getEntryTitle() { return entryTitle; }
    public void setEntryTitle(String entryTitle) { this.entryTitle = entryTitle; }

    public String getEntryDescription() { return entryDescription; }
    public void setEntryDescription(String entryDescription) { this.entryDescription = entryDescription; }

    public String getEntryTags() { return entryTags; }
    public void setEntryTags(String entryTags) { this.entryTags = entryTags; }

    public String getResourceContent() { return resourceContent; }
    public void setResourceContent(String resourceContent) { this.resourceContent = resourceContent; }

    public ModerationJobStatus getStatus() { return status; }
    public void setStatus(ModerationJobStatus status) { this.status = status; }

    public ModerationDecision getDecision() { return decision; }
    public void setDecision(ModerationDecision decision) { this.decision = decision; }

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
