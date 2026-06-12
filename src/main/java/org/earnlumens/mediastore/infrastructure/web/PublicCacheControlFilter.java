package org.earnlumens.mediastore.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Sets {@code Cache-Control} headers on anonymous GET requests to public
 * endpoints so the edge (Cloudflare Workers / Cache Rules) can absorb read
 * traffic instead of every request hitting Cloud Run + MongoDB (Phase 1 of
 * SCALABILITY-AUDIT.md).
 * <p>
 * Rules:
 * <ul>
 *   <li>Only {@code GET /public/**} requests are considered.</li>
 *   <li>If the request carries an {@code Authorization} header or the session
 *       refresh cookie, the response may be personalized (e.g. language
 *       filtering, archived-content visibility) → {@code private, no-store}.</li>
 *   <li>Otherwise a short, per-resource-type {@code public, max-age} is set.
 *       TTLs are deliberately short for feeds (content freshness) and longer
 *       for slow-moving resources (tenant config, guidelines, ratings).</li>
 * </ul>
 * Headers are set before the chain executes because {@code @ResponseBody}
 * responses are written (and possibly committed) inside the handler. A side
 * effect is that error responses share the short public TTL — bounded negative
 * caching, which is acceptable and even protective under load.
 */
@Component
public class PublicCacheControlFilter extends OncePerRequestFilter {

    private final String sessionCookieName;

    public PublicCacheControlFilter(@Value("${mediastore.sec.cookieName}") String sessionCookieName) {
        this.sessionCookieName = sessionCookieName;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"GET".equalsIgnoreCase(request.getMethod())
                || request.getRequestURI() == null
                || !request.getRequestURI().startsWith("/public/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (isAuthenticated(request)) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        } else {
            int ttl = ttlSecondsFor(request.getRequestURI());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=" + ttl);
        }
        filterChain.doFilter(request, response);
    }

    private boolean isAuthenticated(HttpServletRequest request) {
        if (request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
            return true;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (sessionCookieName.equals(cookie.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Edge TTL per resource family. Feeds stay short; config-like data longer. */
    private int ttlSecondsFor(String path) {
        if (path.startsWith("/public/tenant")
                || path.startsWith("/public/guidelines")
                || path.startsWith("/public/franchises")
                || path.startsWith("/public/ratings")) {
            return 300;
        }
        if (path.startsWith("/public/price")) {
            return 60;
        }
        if (path.startsWith("/public/spaces")) {
            return 60;
        }
        if (path.startsWith("/public/search")) {
            return 30;
        }
        // Feeds and detail pages under /public/entries and /public/collections.
        return 15;
    }
}
