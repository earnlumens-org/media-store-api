package org.earnlumens.mediastore.domain.media.model;

import java.time.LocalDateTime;

/**
 * Domain model for an image-thumbnail processing job.
 *
 * <p>Created on moderation APPROVE for entries (thumbnail + optional preview)
 * and collections (cover). The worker downloads the source image from R2,
 * validates dimensions, generates 320/640/1280-wide WebP variants and
 * uploads them to {@code outputR2Prefix}. The API denormalises a "ready"
 * flag onto Entry/Collection so the UI can emit srcset.
 */
public class ThumbnailJob {

    private String id;
    private String tenantId;

    /** Entry ID for THUMBNAIL/PREVIEW; collection ID for COVER. */
    private String ownerId;

    /** What kind of image this job processes. Drives source/output paths and denormalisation target. */
    private ThumbnailJobKind kind;

    /** R2 key of the source image (already uploaded by the user). */
    private String sourceR2Key;

    /** R2 prefix where variants will be written (parent dir of source + /derived). */
    private String outputR2Prefix;

    private ThumbnailJobStatus status;

    private int retryCount;
    private int maxRetries;
    private String errorMessage;

    /** Original source dimensions reported by the worker after probing. */
    private Integer sourceWidthPx;
    private Integer sourceHeightPx;

    private LocalDateTime lastHeartbeat;
    private LocalDateTime dispatchedAt;
    private LocalDateTime processingStartedAt;
    private LocalDateTime completedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public ThumbnailJob() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public ThumbnailJobKind getKind() { return kind; }
    public void setKind(ThumbnailJobKind kind) { this.kind = kind; }

    public String getSourceR2Key() { return sourceR2Key; }
    public void setSourceR2Key(String sourceR2Key) { this.sourceR2Key = sourceR2Key; }

    public String getOutputR2Prefix() { return outputR2Prefix; }
    public void setOutputR2Prefix(String outputR2Prefix) { this.outputR2Prefix = outputR2Prefix; }

    public ThumbnailJobStatus getStatus() { return status; }
    public void setStatus(ThumbnailJobStatus status) { this.status = status; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Integer getSourceWidthPx() { return sourceWidthPx; }
    public void setSourceWidthPx(Integer sourceWidthPx) { this.sourceWidthPx = sourceWidthPx; }

    public Integer getSourceHeightPx() { return sourceHeightPx; }
    public void setSourceHeightPx(Integer sourceHeightPx) { this.sourceHeightPx = sourceHeightPx; }

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
