package org.earnlumens.mediastore.domain.media.dto.request;

/**
 * Heartbeat request sent periodically by the transcoding worker
 * to prove it is still alive.
 *
 * <p>The first heartbeat also transitions the job from DISPATCHED → PROCESSING.
 */
public record TranscodingHeartbeatRequest(String jobId, String tenantId) {}
