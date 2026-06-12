package org.earnlumens.mediastore.infrastructure.counter;

import java.time.Duration;
import java.util.OptionalLong;

/**
 * Atomic, fixed-window counter shared by every API instance, used for
 * rate limiting decisions that must stay correct when the service scales
 * horizontally (Phase 3, task 3.1 of SCALABILITY-AUDIT.md — P0-5).
 *
 * <p>Callers identify a counter by {@code (scope, key, windowBucket)} —
 * e.g. {@code ("auth", "1.2.3.4", epochMinute)} — and receive the new count
 * after an atomic increment. A new window bucket implicitly starts a fresh
 * counter; old windows are garbage-collected by the backend.
 *
 * <p><b>Failure semantics are the caller's responsibility.</b> A backend
 * failure is reported as {@link OptionalLong#empty()} — never an exception —
 * so each call site can choose its own degradation mode:
 * <ul>
 *   <li><b>fail-closed</b> (auth brute-force protection): treat empty as
 *       "limit exceeded" and reject the request;</li>
 *   <li><b>fail-open</b> (anonymous search budget): treat empty as
 *       "within budget" and let the request through — the cdn-worker edge
 *       rate limit remains the first line of defense.</li>
 * </ul>
 *
 * <p>The interface deliberately hides the backend (currently MongoDB, see
 * {@link MongoDistributedCounter}) so that a future migration to Redis —
 * see the adoption triggers R1–R6 in SCALABILITY-AUDIT.md — is a local
 * implementation swap.
 */
public interface DistributedCounter {

    /**
     * Atomically increments the counter identified by
     * {@code (scope, key, windowBucket)} and returns the count <em>after</em>
     * the increment.
     *
     * @param scope        logical namespace (e.g. {@code "auth"}, {@code "search"})
     * @param key          per-client discriminator, typically the client IP
     * @param windowBucket fixed-window bucket number (e.g. epoch minute); a
     *                     different bucket addresses an independent counter
     * @param retention    how long the backend must keep the counter alive —
     *                     at least the window length; expired counters are
     *                     purged automatically
     * @return the count after this increment, or {@link OptionalLong#empty()}
     *         if the backend failed (caller decides fail-open vs fail-closed)
     */
    OptionalLong incrementAndGet(String scope, String key, long windowBucket, Duration retention);
}
