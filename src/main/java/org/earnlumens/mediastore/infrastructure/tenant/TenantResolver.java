package org.earnlumens.mediastore.infrastructure.tenant;

import jakarta.servlet.http.HttpServletRequest;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantConfigService;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantReadModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resolves the current tenant from the incoming HTTP request.
 * <p>
 * The canonical {@code tenantId} used throughout the persistence layer is the
 * tenant's <b>subdomain</b> string (e.g. {@code "alice"} for
 * {@code alice.earnlumens.org}). The platform's own default tenant uses the
 * reserved subdomain {@link #DEFAULT_TENANT}.
 * <p>
 * Spring's {@code ForwardedHeaderFilter} (enabled via
 * {@code server.forward-headers-strategy=framework}) ensures that the value
 * exposed via {@link HttpServletRequest#getServerName()} reflects the original
 * {@code X-Forwarded-Host} when behind a reverse proxy or CDN.
 */
@Component
public class TenantResolver {

    private static final Logger logger = LoggerFactory.getLogger(TenantResolver.class);

    public static final String DEFAULT_TENANT = "earnlumens";

    /** Direct Cloud Run traffic (health probes, internal jobs) — platform plane, never a custom domain. */
    private static final String CLOUD_RUN_SUFFIX = ".run.app";

    /** Paths and admin-plane hostnames that must never resolve to a custom tenant. */
    private static final Set<String> RESERVED_SUBDOMAINS = Set.of(
            DEFAULT_TENANT, "www", "api", "admin", "app", "app-dev", "api-dev", "cdn",
            "docs", "static", "assets", "mail", "stripe", "billing", "status"
    );

    /** RFC-1123-safe subdomain: same rule used by admin-api's SubdomainValidator. */
    private static final Pattern SUBDOMAIN = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{1,28}[a-z0-9])$");

    @Value("${mediastore.tenant.root-domain:earnlumens.org}")
    private String rootDomain;

    private final TenantConfigService tenantConfigService;

    public TenantResolver(TenantConfigService tenantConfigService) {
        this.tenantConfigService = tenantConfigService;
    }

    /**
     * Extracts the tenant identifier from the request host.
     * <ul>
     *   <li>{@code localhost} / {@code 127.0.0.1} / {@code rootDomain} / {@code *.run.app}
     *       → {@link #DEFAULT_TENANT}.</li>
     *   <li>{@code {sub}.{rootDomain}} → {@code sub} iff it is syntactically valid,
     *       not reserved, and an ACTIVE tenant document exists. Otherwise → {@link #DEFAULT_TENANT}.</li>
     *   <li>Any other host is treated as a custom domain (custom-domain-upgrade 3.1):
     *       served only when it matches a verified-ACTIVE domain of an ACTIVE, Pro
     *       tenant ({@code findActiveByCustomDomain}); otherwise {@code null} —
     *       <b>no tenant</b>. {@code TenantFilter} turns that into a 404 so an
     *       arbitrary host can never fall back to the default tenant's data.</li>
     * </ul>
     *
     * @return the canonical tenantId (subdomain), or {@code null} when the host
     *         is an unknown/non-servable custom domain
     */
    public String resolve(HttpServletRequest request) {
        String host = request.getServerName();
        if (host == null || host.isBlank()) {
            return DEFAULT_TENANT;
        }

        String hostname = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
        hostname = hostname.toLowerCase();

        if ("localhost".equals(hostname)
                || "localhost.dv".equals(hostname)
                || "127.0.0.1".equals(hostname)
                || rootDomain.equals(hostname)) {
            return DEFAULT_TENANT;
        }

        String suffix = "." + rootDomain;
        if (!hostname.endsWith(suffix)) {
            if (hostname.endsWith(CLOUD_RUN_SUFFIX)) {
                // Health probes and internal jobs hit .run.app directly (no
                // X-Visitor-Host). Keep the historical default-tenant behaviour;
                // EdgeAuthFilter already gates every non-exempt path there.
                return DEFAULT_TENANT;
            }
            // Custom domain: only a verified-ACTIVE domain of a Pro tenant may
            // serve. Miss ⇒ no tenant (never the default tenant).
            return tenantConfigService.findActiveByCustomDomain(hostname)
                    .map(TenantReadModel::getSubdomain)
                    .orElse(null);
        }

        String subdomain = hostname.substring(0, hostname.length() - suffix.length());
        if (subdomain.contains(".")) {
            // Multi-label subdomain (e.g. admin-api.staging). Refuse to guess.
            return DEFAULT_TENANT;
        }
        if (RESERVED_SUBDOMAINS.contains(subdomain) || !SUBDOMAIN.matcher(subdomain).matches()) {
            return DEFAULT_TENANT;
        }

        // Final gate: the subdomain must correspond to an ACTIVE tenant document.
        // Blocked/deleted tenants fall back to the default tenant so data stays isolated.
        if (tenantConfigService.findActiveBySubdomain(subdomain).isEmpty()) {
            logger.debug("No ACTIVE tenant for subdomain={}, falling back to default", subdomain);
            return DEFAULT_TENANT;
        }

        return subdomain;
    }
}
