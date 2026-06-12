package org.earnlumens.mediastore.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import org.earnlumens.mediastore.infrastructure.counter.DistributedCounter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.OptionalLong;

/**
 * Per-IP budget for <em>anonymous</em> searches, mirroring how large platforms
 * let visitors run a handful of searches before asking them to sign in.
 *
 * <p>The budget is a fixed-window counter per IP per hour backed by a
 * {@link DistributedCounter} shared by every API instance (Phase 3, task 3.1
 * of SCALABILITY-AUDIT.md — P0-5), so the 25-search allowance cannot be
 * multiplied by scaling out Cloud Run. It is a soft, UX-level gate
 * ("sign in to keep searching"), layered on top of the hard, IP-based
 * {@link RateLimitFilter} that protects the database from denial-of-service.
 *
 * <p><b>Fail-open by design:</b> if the counter backend errors, the search is
 * allowed through — this gate is a product nicety, not a security boundary,
 * and the cdn-worker edge rate limit plus the {@code SEARCH} tier of
 * {@link RateLimitFilter} remain in force.
 *
 * <p>Authenticated users are never subject to this budget; they are still
 * covered by {@link RateLimitFilter} so a logged-in account cannot hammer the
 * search backend either.
 */
@Component
public class AnonymousSearchBudget {

    /** Scope name for budget windows in the shared counter backend. */
    static final String COUNTER_SCOPE = "search";

    /** Slack over the window length for Mongo's ~60 s TTL sweep granularity. */
    private static final Duration RETENTION_SLACK = Duration.ofMinutes(1);

    /** Free anonymous searches per IP per rolling window before login is required. */
    private final int maxAnonymousSearches;
    private final long windowMs;
    private final Duration retention;

    private final DistributedCounter distributedCounter;

    public AnonymousSearchBudget(
            @Value("${mediastore.search.anonymous-budget:25}") int maxAnonymousSearches,
            @Value("${mediastore.search.anonymous-window-minutes:60}") long windowMinutes,
            DistributedCounter distributedCounter
    ) {
        this.maxAnonymousSearches = maxAnonymousSearches;
        this.windowMs = windowMinutes * 60_000;
        this.retention = Duration.ofMillis(windowMs).plus(RETENTION_SLACK);
        this.distributedCounter = distributedCounter;
    }

    /**
     * Records one anonymous search for the request's client IP and reports
     * whether the visitor is still within their free budget.
     *
     * @return {@code true} if the search may proceed; {@code false} once the
     *         visitor has exhausted the budget and must sign in.
     */
    public boolean tryConsume(HttpServletRequest request) {
        String ip = resolveClientIp(request);
        long bucket = System.currentTimeMillis() / windowMs;

        OptionalLong count = distributedCounter.incrementAndGet(COUNTER_SCOPE, ip, bucket, retention);

        // Fail-open: a backend hiccup must never block a visitor's search.
        return count.isEmpty() || count.getAsLong() <= maxAnonymousSearches;
    }

    // ── IP resolution (Cloudflare-aware) — same precedence as RateLimitFilter ──

    private String resolveClientIp(HttpServletRequest request) {
        String cfIp = request.getHeader("CF-Connecting-IP");
        if (cfIp != null && !cfIp.isBlank()) {
            return cfIp.strip();
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }
}
