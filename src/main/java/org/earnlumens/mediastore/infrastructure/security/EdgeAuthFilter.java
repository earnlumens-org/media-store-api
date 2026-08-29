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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Edge→origin authentication (SECURITY-TODO §7, custom-domain-upgrade Fase 0).
 * <p>
 * Cloud Run runs with {@code ingress: all}, so anyone can hit the
 * {@code *.run.app} hostname directly and forge {@code X-Visitor-Host} to
 * impersonate an arbitrary tenant. This filter closes that hole: the edge
 * Workers (tenants-router, cdn-worker) attach a shared secret in
 * {@code X-Edge-Auth} on every fetch to this backend, and when enforcement
 * is enabled every request without a valid secret gets a generic 404.
 * <p>
 * Exempt paths (they arrive directly on *.run.app, NOT through the edge,
 * and carry their own per-caller secrets):
 * <ul>
 *   <li>{@code /actuator/health/**} — Cloud Run probes.</li>
 *   <li>{@code /api/internal/**} — transcoding / moderation / thumbnail /
 *       cleanup / tenant-cache callbacks (Cloud Scheduler + Cloud Run jobs),
 *       each already authenticated with its own dedicated secret.</li>
 * </ul>
 * Enforcement is off by default ({@code mediastore.sec.edgeAuthEnabled=false})
 * so the rollout order is safe: deploy Workers with the secret first, then
 * flip the flag on the backend.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EdgeAuthFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(EdgeAuthFilter.class);

    /** Header set by the edge Workers (tenants-router, cdn-worker). */
    public static final String EDGE_AUTH_HEADER = "X-Edge-Auth";

    private final boolean enabled;
    private final byte[] secret;

    public EdgeAuthFilter(
            @Value("${mediastore.sec.edgeAuthEnabled:false}") boolean enabled,
            @Value("${mediastore.sec.edgeAuthSecret:}") String secret) {
        this.enabled = enabled;
        this.secret = secret == null ? new byte[0] : secret.trim().getBytes(StandardCharsets.UTF_8);
        if (enabled && this.secret.length == 0) {
            throw new IllegalStateException(
                    "mediastore.sec.edgeAuthEnabled=true but mediastore.sec.edgeAuthSecret is empty. "
                    + "Set MEDIASTORE_EDGE_AUTH_SECRET (Secret Manager: edge-auth-secret) or disable enforcement.");
        }
    }

    /**
     * True for paths that must bypass edge auth. Package-private for tests.
     */
    static boolean isExempt(String path) {
        if (path == null) return false;
        return path.equals("/actuator/health")
                || path.startsWith("/actuator/health/")
                || path.equals("/api/internal")
                || path.startsWith("/api/internal/");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || isExempt(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(EDGE_AUTH_HEADER);
        byte[] provided = header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);
        // Constant-time comparison — never leak how much of the secret matched.
        if (!MessageDigest.isEqual(secret, provided)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Rejected non-edge request to {} (X-Edge-Auth {})",
                        request.getRequestURI(), header == null ? "missing" : "invalid");
            }
            // Generic 404: an attacker probing *.run.app learns nothing about
            // which paths exist behind the edge.
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
