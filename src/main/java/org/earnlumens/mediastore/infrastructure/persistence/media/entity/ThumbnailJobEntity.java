package org.earnlumens.mediastore.infrastructure.persistence.media.entity;

import jakarta.validation.constraints.NotBlank;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * MongoDB document for tracking thumbnail-processing jobs.
 *
 * <p>Indexes mirror the transcoding-job pattern (PENDING fan-out + stale-watchdog).
 */
@Document(collection = "thumbnail_jobs")
@CompoundIndex(name = "idx_status_createdAt", def = "{'status': 1, 'createdAt': 1}")
@CompoundIndex(name = "idx_tenantId_owner_kind", def = "{'tenantId': 1, 'ownerId': 1, 'kind': 1}")
// Cross-tenant watchdog query: prefix with tenantId so the tenant-scoped
// lookups stay selective; the watchdog uses status + lastHeartbeat.
@CompoundIndex(name = "idx_tenantId_status_lastHeartbeat",
        def = "{'tenantId': 1, 'status': 1, 'lastHeartbeat': 1}")
public class ThumbnailJobEntity {

    @Id
    private String id;

    @NotBlank
    private String tenantId;

    @NotBlank
    private String ownerId;

    @NotBlank
    private String kind;

    @NotBlank
    private String sourceR2Key;

    @NotBlank
    private String outputR2Prefix;

    @NotBlank
    private String status;

    private int retryCount;
    private int maxRetries;
    private String errorMessage;

    private Integer sourceWidthPx;
    private Integer sourceHeightPx;

    private LocalDateTime lastHeartbeat;
    private LocalDateTime dispatchedAt;
    private LocalDateTime processingStartedAt;
    private LocalDateTime completedAt;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getSourceR2Key() { return sourceR2Key; }
    public void setSourceR2Key(String sourceR2Key) { this.sourceR2Key = sourceR2Key; }

    public String getOutputR2Prefix() { return outputR2Prefix; }
    public void setOutputR2Prefix(String outputR2Prefix) { this.outputR2Prefix = outputR2Prefix; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

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
