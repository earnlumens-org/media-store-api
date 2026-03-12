package org.earnlumens.mediastore.infrastructure.persistence.media.entity;

import jakarta.validation.constraints.NotBlank;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * MongoDB document for tracking video transcoding jobs.
 *
 * <p>Indexes are designed for the two critical query patterns:
 * <ul>
 *   <li>Dispatcher: find PENDING jobs ordered by creation time</li>
 *   <li>Watchdog: find DISPATCHED/PROCESSING jobs with stale heartbeat</li>
 * </ul>
 */
@Document(collection = "transcoding_jobs")
@CompoundIndex(name = "idx_status_createdAt", def = "{'status': 1, 'createdAt': 1}")
@CompoundIndex(name = "idx_tenantId_assetId", def = "{'tenantId': 1, 'assetId': 1}", unique = true)
@CompoundIndex(name = "idx_tenantId_entryId_status", def = "{'tenantId': 1, 'entryId': 1, 'status': 1}")
@CompoundIndex(name = "idx_status_lastHeartbeat", def = "{'status': 1, 'lastHeartbeat': 1}")
public class TranscodingJobEntity {

    @Id
    private String id;

    @NotBlank
    private String tenantId;

    @NotBlank
    private String entryId;

    @NotBlank
    private String assetId;

    @NotBlank
    private String sourceR2Key;

    private String hlsR2Prefix;

    @NotBlank
    private String status;

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

    public String getAssetId() { return assetId; }
    public void setAssetId(String assetId) { this.assetId = assetId; }

    public String getSourceR2Key() { return sourceR2Key; }
    public void setSourceR2Key(String sourceR2Key) { this.sourceR2Key = sourceR2Key; }

    public String getHlsR2Prefix() { return hlsR2Prefix; }
    public void setHlsR2Prefix(String hlsR2Prefix) { this.hlsR2Prefix = hlsR2Prefix; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

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
