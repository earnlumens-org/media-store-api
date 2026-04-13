package org.earnlumens.mediastore.domain.media.model;

/**
 * Lifecycle of a content moderation job.
 * <pre>
 *   PENDING ──► DISPATCHED ──► PROCESSING ──► COMPLETED
 *       │            │              │
 *       └────────────┴──────────────┴──► FAILED
 *                                            │
 *                    (retry ≤ MAX_RETRIES) ───┘──► PENDING
 *                    (retry > MAX_RETRIES) ───┘──► DEAD
 * </pre>
 *
 * <ul>
 *   <li>{@code PENDING} — queued, waiting for dispatch to Cloud Run</li>
 *   <li>{@code DISPATCHED} — Cloud Run Job triggered, waiting for it to start</li>
 *   <li>{@code PROCESSING} — moderation pipeline running (worker sent heartbeat)</li>
 *   <li>{@code COMPLETED} — moderation finished (decision: APPROVE, REJECT, or MANUAL_QUEUE)</li>
 *   <li>{@code FAILED} — transient failure, will be retried by watchdog</li>
 *   <li>{@code DEAD} — exhausted all retries, requires manual intervention</li>
 * </ul>
 */
public enum ModerationJobStatus {
    PENDING,
    DISPATCHED,
    PROCESSING,
    COMPLETED,
    FAILED,
    DEAD
}
