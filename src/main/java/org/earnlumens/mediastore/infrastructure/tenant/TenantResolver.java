package org.earnlumens.mediastore.infrastructure.tenant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Resolves the tenantId from the request's server name (Host header).
 * <p>
 * Spring's ForwardedHeaderFilter (enabled via server.forward-headers-strategy=framework)
 * ensures that X-Forwarded-Host is reflected in request.getServerName().
 * <p>
 * Current mapping (single-tenant, multi-tenant-ready):
 * - *.earnlumens.org / localhost  →  "earnlumens"
 */
@Component
public class TenantResolver {

    private static final String DEFAULT_TENANT = "earnlumens";

    /**
     * Extracts the tenant identifier from the request host.
     *
     * @param request the current HTTP request
     * @return the resolved tenantId, never null
     */
    public String resolve(HttpServletRequest request) {
        String host = request.getServerName();
        if (host == null || host.isBlank()) {
            return DEFAULT_TENANT;
        }

        // Strip port if present (shouldn't be in serverName, but be safe)
        String hostname = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;

        // localhost / 127.0.0.1 -> default tenant
        if ("localhost".equals(hostname) || "127.0.0.1".equals(hostname)) {
            return DEFAULT_TENANT;
        }

        // *.earnlumens.org -> "earnlumens"
        if (hostname.equals("earnlumens.org") || hostname.endsWith(".earnlumens.org")) {
            return DEFAULT_TENANT;
        }

        // Future: resolve from a tenant registry for custom domains
        return DEFAULT_TENANT;
    }
}
