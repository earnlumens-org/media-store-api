package org.earnlumens.mediastore.domain.media.model;

import java.time.LocalDateTime;

/**
 * Domain model for a video transcoding job.
 *
 * <p>Every uploaded video gets exactly one TranscodingJob. The job tracks
 * the full lifecycle from upload → dispatch → processing → completion/failure.
 *
 * <p>Robustness guarantees:
 * <ul>
 *   <li>Jobs are persisted <b>before</b> dispatch — if dispatch fails, the watchdog retries.</li>
 *   <li>{@code retryCount} + {@code maxRetries} ensure finite retries before marking DEAD.</li>
 *   <li>{@code lastHeartbeat} lets the watchdog detect stuck jobs (worker crashed mid-FFmpeg).</li>
 *   <li>{@code errorMessage} captures the last failure reason for debugging and user notification.</li>
 * </ul>
 */
public class TranscodingJob {

    private String id;
    private String tenantId;
    private String entryId;
    private String assetId;

    /** R2 key of the raw uploaded video (source). */
    private String sourceR2Key;

    /** R2 prefix where HLS output will be stored (set after completion). */
    private String hlsR2Prefix;

    private TranscodingJobStatus status;

    /** Number of times this job has been retried after failure. */
    private int retryCount;

    /** Maximum retries before the job is marked DEAD. */
    private int maxRetries;

    /** Last error message from the transcoding worker. */
    private String errorMessage;

    /** Periodically updated by the worker to prove it's still alive. */
    private LocalDateTime lastHeartbeat;

    /** When the job was dispatched to Cloud Run. */
    private LocalDateTime dispatchedAt;

    /** When the worker started FFmpeg processing. */
    private LocalDateTime processingStartedAt;

    /** When the job completed (success or final failure). */
    private LocalDateTime completedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public TranscodingJob() {}

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

    public TranscodingJobStatus getStatus() { return status; }
    public void setStatus(TranscodingJobStatus status) { this.status = status; }

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
