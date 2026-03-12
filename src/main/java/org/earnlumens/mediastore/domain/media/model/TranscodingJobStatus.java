package org.earnlumens.mediastore.domain.media.model;

/**
 * Lifecycle of a transcoding job.
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
 *   <li>{@code PROCESSING} — FFmpeg is running (worker called back with progress)</li>
 *   <li>{@code COMPLETED} — HLS variants uploaded to R2, asset marked READY</li>
 *   <li>{@code FAILED} — transient failure, will be retried by watchdog</li>
 *   <li>{@code DEAD} — exhausted all retries, requires manual intervention (user notified)</li>
 * </ul>
 */
public enum TranscodingJobStatus {
    PENDING,
    DISPATCHED,
    PROCESSING,
    COMPLETED,
    FAILED,
    DEAD
}
