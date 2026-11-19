package org.earnlumens.mediastore.infrastructure.tenant;

import jakarta.servlet.http.HttpServletRequest;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantConfigService;
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

    /** Exact platform hosts outside {@code rootDomain} (dev: api-dev sits beside app-dev). Empty in prod. */
    @Value("${mediastore.tenant.platform-hosts:}")
    private Set<String> platformHosts = Set.of();

    private final TenantConfigService tenantConfigService;

    public TenantResolver(TenantConfigService tenantConfigService) {
        this.tenantConfigService = tenantConfigService;
    }

    /**
     * Outcome of {@link #resolveTenant}: the canonical tenantId plus whether
     * the tenant is BLOCKED and must be served in read-only mode (H1).
     */
    public record ResolvedTenant(String tenantId, boolean suspended) {
        public static ResolvedTenant active(String tenantId) {
            return new ResolvedTenant(tenantId, false);
        }
    }

    /**
     * Extracts the tenant identifier from the request host.
     * <ul>
     *   <li>{@code localhost} / {@code 127.0.0.1} / {@code rootDomain} / {@code *.run.app}
     *       and the reserved platform subdomains → {@link #DEFAULT_TENANT}.</li>
     *   <li>{@code {sub}.{rootDomain}} → {@code sub} iff a tenant document exists
     *       (ACTIVE, or BLOCKED ⇒ read-only). A syntactically invalid, multi-label
     *       or unknown subdomain yields {@code null} — <b>never</b> the default
     *       tenant (H1 action 2: an unknown host must not leak another tenant's
     *       storefront).</li>
     *   <li>Any other host is treated as a custom domain (custom-domain-upgrade 3.1):
     *       served only when it matches a verified-ACTIVE domain of an ACTIVE, Pro
     *       tenant ({@code findActiveByCustomDomain}); otherwise {@code null}.
     *       A BLOCKED tenant's custom domain is therefore a 404 (D-04 / H1
     *       decision (b)): its buyers use the subdomain.</li>
     * </ul>
     * {@code TenantFilter} turns {@code null} into a 404 so no downstream code
     * ever runs without a tenant.
     *
     * @return the canonical tenantId (subdomain), or {@code null} when the host
     *         does not map to any tenant
     */
    public String resolve(HttpServletRequest request) {
        ResolvedTenant resolved = resolveTenant(request);
        return resolved == null ? null : resolved.tenantId();
    }

    /** Same as {@link #resolve} but also reports whether the tenant is suspended. */
    public ResolvedTenant resolveTenant(HttpServletRequest request) {
        String host = request.getServerName();
        if (host == null || host.isBlank()) {
            return ResolvedTenant.active(DEFAULT_TENANT);
        }

        String hostname = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
        hostname = hostname.toLowerCase();

        if ("localhost".equals(hostname)
                || "localhost.dv".equals(hostname)
                || "127.0.0.1".equals(hostname)
                || rootDomain.equals(hostname)
                || platformHosts.contains(hostname)) {
            return ResolvedTenant.active(DEFAULT_TENANT);
        }

        String suffix = "." + rootDomain;
        if (!hostname.endsWith(suffix)) {
            if (hostname.endsWith(CLOUD_RUN_SUFFIX)) {
                // Health probes and internal jobs hit .run.app directly (no
                // X-Visitor-Host). Keep the historical default-tenant behaviour;
                // EdgeAuthFilter already gates every non-exempt path there.
                return ResolvedTenant.active(DEFAULT_TENANT);
            }
            // Custom domain: only a verified-ACTIVE domain of a Pro tenant may
            // serve. Miss ⇒ no tenant (never the default tenant).
            return tenantConfigService.findActiveByCustomDomain(hostname)
                    .map(t -> ResolvedTenant.active(t.getSubdomain()))
                    .orElse(null);
        }

        String subdomain = hostname.substring(0, hostname.length() - suffix.length());
        if (RESERVED_SUBDOMAINS.contains(subdomain)) {
            // Platform plane (www, api, admin, …): the default storefront.
            return ResolvedTenant.active(DEFAULT_TENANT);
        }
        if (subdomain.contains(".") || !SUBDOMAIN.matcher(subdomain).matches()) {
            // Multi-label or malformed label: can never be a tenant. Refuse
            // instead of guessing the default tenant.
            logger.debug("Unresolvable subdomain label '{}' — no tenant", subdomain);
            return null;
        }

        // Final gate: the subdomain must correspond to a tenant document. An
        // unknown subdomain is a 404; a BLOCKED tenant resolves as itself in
        // read-only mode so its buyers keep their library (H1).
        return tenantConfigService.findBySubdomain(subdomain)
                .map(t -> new ResolvedTenant(subdomain, !t.isActive()))
                .orElseGet(() -> {
                    logger.debug("No tenant document for subdomain={} — no tenant", subdomain);
                    return null;
                });
    }
}
