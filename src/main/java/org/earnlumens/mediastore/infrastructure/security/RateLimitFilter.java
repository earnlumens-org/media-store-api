package org.earnlumens.mediastore.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IP-based rate limiting filter that runs before all other filters.
 * Uses a fixed-window counter per IP per route tier with automatic cleanup.
 *
 * <p>Tiers (requests per minute):
 * <ul>
 *   <li><b>AUTH</b>  – /api/auth/**:         10/min  (brute-force protection)</li>
 *   <li><b>ENTRIES</b> – /api/entries/**:     10/min  (anti-bot entry spam)</li>
 *   <li><b>UPLOAD</b> – /api/uploads/**:      30/min  (presigned URL spam)</li>
 *   <li><b>WAITLIST</b> – /api/waitlist/**:   10/min  (spam protection)</li>
 *   <li><b>INTERNAL</b> – /api/internal/**:  300/min  (worker callbacks)</li>
 *   <li><b>PUBLIC</b> – /public/**:          200/min  (feed browsing)</li>
 *   <li><b>DEFAULT</b> – everything else:    120/min  (general API)</li>
 * </ul>
 *
 * <p>IP is extracted Cloudflare-aware: CF-Connecting-IP → X-Forwarded-For → remoteAddr.
 * Expired windows are cleaned up lazily every 2 minutes.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1) // right after TenantFilter
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitFilter.class);

    // ── Rate limit tiers ──────────────────────────────────────────
    private enum Tier {
        AUTH(10),
        ENTRIES(60),
        UPLOAD(30),
        WAITLIST(10),
        INTERNAL(300),
        PUBLIC_API(200),
        DEFAULT(120);

        final int maxPerMinute;
        Tier(int maxPerMinute) { this.maxPerMinute = maxPerMinute; }
    }

    // ── Counter storage ───────────────────────────────────────────

    /** Key: "ip:tier:minuteBucket" → counter */
    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanup = new AtomicLong(System.currentTimeMillis());
    private static final long CLEANUP_INTERVAL_MS = 120_000; // 2 minutes

    /**
     * CORS allow-list for 429 responses. Loaded from the same config as
     * {@link WebSecurityConfig#corsConfigurationSource()} so the two stay in
     * sync; defaults to the frontend URI alone if no override is set.
     */
    private final Set<String> allowedCorsOrigins;

    public RateLimitFilter(
            @Value("${mediastore.frontend.uri:}") String frontendUri,
            @Value("${mediastore.cors.allowed-origins:}") String allowedOriginsConfig
    ) {
        Set<String> origins = new HashSet<>();
        if (allowedOriginsConfig != null && !allowedOriginsConfig.isBlank()) {
            Arrays.stream(allowedOriginsConfig.split(","))
                    .map(String::strip)
                    .filter(s -> !s.isEmpty() && !s.contains("*"))
                    .forEach(origins::add);
        }
        if (frontendUri != null && !frontendUri.isBlank()) {
            origins.add(frontendUri.strip());
        }
        this.allowedCorsOrigins = Collections.unmodifiableSet(origins);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // Skip rate limiting for the error page only. OAuth2 endpoints get
        // a higher tier (DEFAULT) but ARE rate limited — leaving them open
        // to brute-force / abuse via the callback was a finding in the
        // 2026-05 audit.
        String path = request.getRequestURI();
        if ("/error".equals(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = resolveClientIp(request);
        Tier tier = classifyRequest(path);
        long minute = System.currentTimeMillis() / 60_000;
        String key = ip + ":" + tier.name() + ":" + minute;

        WindowCounter counter = counters.computeIfAbsent(key,
                k -> new WindowCounter(minute));
        int count = counter.incrementAndGet();

        // Set rate limit headers (standard draft RFC)
        response.setHeader("X-RateLimit-Limit", String.valueOf(tier.maxPerMinute));
        response.setHeader("X-RateLimit-Remaining",
                String.valueOf(Math.max(0, tier.maxPerMinute - count)));

        if (count > tier.maxPerMinute) {
            logger.warn("Rate limited: ip={}, tier={}, count={}, path={}",
                    ip, tier, count, path);
            addCorsHeaders(request, response);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.setHeader("Retry-After", "60");
            response.getWriter().write("{\"error\":\"Too Many Requests\"}");
            return;
        }

        // Lazy cleanup of expired windows
        cleanupIfNeeded();

        filterChain.doFilter(request, response);
    }

    // ── IP resolution (Cloudflare-aware) ──────────────────────────

    private String resolveClientIp(HttpServletRequest request) {
        // Cloudflare always sets this to the true client IP
        String cfIp = request.getHeader("CF-Connecting-IP");
        if (cfIp != null && !cfIp.isBlank()) {
            return cfIp.strip();
        }

        // Fallback for non-CF proxies: first IP in X-Forwarded-For
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].strip();
        }

        return request.getRemoteAddr();
    }

    // ── Request classification ────────────────────────────────────

    private Tier classifyRequest(String path) {
        if (path.startsWith("/api/auth/"))      return Tier.AUTH;
        if (path.startsWith("/api/entries"))     return Tier.ENTRIES;
        if (path.startsWith("/api/uploads/"))    return Tier.UPLOAD;
        if (path.startsWith("/api/waitlist/"))   return Tier.WAITLIST;
        if (path.startsWith("/api/internal/"))   return Tier.INTERNAL;
        if (path.startsWith("/public/"))         return Tier.PUBLIC_API;
        return Tier.DEFAULT;
    }

    // ── Lazy cleanup ──────────────────────────────────────────────

    private void cleanupIfNeeded() {
        long now = System.currentTimeMillis();
        long last = lastCleanup.get();
        if (now - last < CLEANUP_INTERVAL_MS) return;
        if (!lastCleanup.compareAndSet(last, now)) return; // another thread won

        long currentMinute = now / 60_000;
        counters.entrySet().removeIf(e -> e.getValue().minute < currentMinute - 1);
    }

    // ── CORS helper for rejected responses ───────────────────────

    private void addCorsHeaders(HttpServletRequest request, HttpServletResponse response) {
        String origin = request.getHeader("Origin");
        if (origin != null && allowedCorsOrigins.contains(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
        }
    }

    // ── Counter record ────────────────────────────────────────────

    private static class WindowCounter {
        final long minute;
        private final AtomicInteger count = new AtomicInteger(0);

        WindowCounter(long minute) {
            this.minute = minute;
        }

        int incrementAndGet() {
            return count.incrementAndGet();
        }
    }
}
