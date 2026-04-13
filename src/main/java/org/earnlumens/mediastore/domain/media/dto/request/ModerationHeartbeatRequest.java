package org.earnlumens.mediastore.domain.media.dto.request;

/**
 * Heartbeat request sent periodically by the moderation worker
 * to prove it is still alive.
 *
 * <p>The first heartbeat also transitions the job from DISPATCHED → PROCESSING.
 */
public record ModerationHeartbeatRequest(String jobId, String tenantId) {}
