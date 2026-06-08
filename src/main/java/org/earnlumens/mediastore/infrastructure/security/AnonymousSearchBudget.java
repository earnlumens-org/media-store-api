package org.earnlumens.mediastore.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-IP budget for <em>anonymous</em> searches, mirroring how large platforms
 * let visitors run a handful of searches before asking them to sign in.
 *
 * <p>This is deliberately in-memory and lock-free (a fixed-window counter per IP
 * per hour) so it costs effectively nothing per tenant — no database round-trips,
 * no shared state, and trivially cheap on hardware. It is a soft, UX-level gate
 * ("sign in to keep searching"), layered on top of the hard, IP-based
 * {@link RateLimitFilter} that protects the database from denial-of-service.
 *
 * <p>Authenticated users are never subject to this budget; they are still
 * covered by {@link RateLimitFilter} so a logged-in account cannot hammer the
 * search backend either.
 */
@Component
public class AnonymousSearchBudget {

    /** Free anonymous searches per IP per rolling window before login is required. */
    private final int maxAnonymousSearches;
    private final long windowMs;

    /** Key: "ip:windowBucket" → counter. */
    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanup = new AtomicLong(System.currentTimeMillis());
    private static final long CLEANUP_INTERVAL_MS = 300_000; // 5 minutes

    public AnonymousSearchBudget(
            @Value("${mediastore.search.anonymous-budget:25}") int maxAnonymousSearches,
            @Value("${mediastore.search.anonymous-window-minutes:60}") long windowMinutes
    ) {
        this.maxAnonymousSearches = maxAnonymousSearches;
        this.windowMs = windowMinutes * 60_000;
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
        String key = ip + ":" + bucket;

        WindowCounter counter = counters.computeIfAbsent(key, k -> new WindowCounter(bucket));
        int count = counter.incrementAndGet();

        cleanupIfNeeded();

        return count <= maxAnonymousSearches;
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

    private void cleanupIfNeeded() {
        long now = System.currentTimeMillis();
        long last = lastCleanup.get();
        if (now - last < CLEANUP_INTERVAL_MS) return;
        if (!lastCleanup.compareAndSet(last, now)) return;

        long currentBucket = now / windowMs;
        counters.entrySet().removeIf(e -> e.getValue().bucket < currentBucket);
    }

    private static class WindowCounter {
        final long bucket;
        private final AtomicInteger count = new AtomicInteger(0);

        WindowCounter(long bucket) {
            this.bucket = bucket;
        }

        int incrementAndGet() {
            return count.incrementAndGet();
        }
    }
}
