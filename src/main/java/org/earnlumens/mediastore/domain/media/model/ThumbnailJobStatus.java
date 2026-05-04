package org.earnlumens.mediastore.domain.media.model;

/**
 * Lifecycle of a thumbnail processing job.
 * <pre>
 *   PENDING ──► DISPATCHED ──► PROCESSING ──► COMPLETED
 *       │            │              │
 *       └────────────┴──────────────┴──► FAILED
 *                                            │
 *                    (retry &le; MAX_RETRIES) ─┘──► PENDING
 *                    (retry &gt; MAX_RETRIES) ─┘──► DEAD (graceful — original is used)
 * </pre>
 *
 * <p>Failure handling philosophy: thumbnail processing is a <b>best-effort</b>
 * optimisation. If a job ends up DEAD, the original image (already validated
 * at upload time) is served as-is — the entry remains fully sellable. We
 * never block content over a failed resize.
 */
public enum ThumbnailJobStatus {
    PENDING,
    DISPATCHED,
    PROCESSING,
    COMPLETED,
    /** Worker reported the input was below the minimum size — original used as-is, not an error. */
    SKIPPED,
    FAILED,
    DEAD
}
