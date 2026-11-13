package org.earnlumens.mediastore.infrastructure.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that resolves the tenant from the Host header on every request
 * and stores it in {@link TenantContext}.
 * <p>
 * This runs before all other filters (security, auth) so the tenant is always
 * available. The context is always cleared after the request completes.
 * <p>
 * By centralizing tenant resolution here, controllers and services never need to
 * resolve tenant themselves — they simply call {@link TenantContext#require()}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TenantFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(TenantFilter.class);

    private final TenantResolver tenantResolver;

    public TenantFilter(TenantResolver tenantResolver) {
        this.tenantResolver = tenantResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        try {
            TenantResolver.ResolvedTenant resolved = tenantResolver.resolveTenant(request);
            if (resolved == null) {
                // Unknown subdomain or non-servable custom domain (H1 action 2,
                // custom-domain-upgrade 3.1). Short-circuit with a generic 404
                // so no downstream code ever runs without a tenant — and never
                // against the default tenant.
                logger.debug("No tenant for host={}, path={} — responding 404",
                        request.getServerName(), request.getRequestURI());
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"tenant_not_found\"}");
                return;
            }
            TenantContext.set(resolved.tenantId());
            if (resolved.suspended()) {
                // BLOCKED tenant: read-only mode (H1). Reads and session
                // plumbing go through; every operation is refused here.
                TenantContext.setSuspended(true);
                if (!TenantSuspensionPolicy.isAllowed(request.getMethod(), request.getRequestURI())) {
                    logger.info("Tenant {} suspended — refusing {} {}",
                            resolved.tenantId(), request.getMethod(), request.getRequestURI());
                    response.setStatus(TenantSuspensionPolicy.STATUS);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"" + TenantSuspensionPolicy.ERROR_CODE + "\"}");
                    return;
                }
            }
            logger.trace("TenantContext set: tenant={}, path={}", resolved.tenantId(), request.getRequestURI());
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
