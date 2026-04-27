package org.earnlumens.mediastore.web.tenant;

import jakarta.servlet.http.HttpServletRequest;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Public visitor-context endpoint used by the SPA to decide what to render
 * before any other API call:
 *
 * <ul>
 *   <li>{@code 200 { kind: "platform" }} — visitor is on the apex, the local
 *       loopback, or a reserved/admin subdomain. The SPA renders the normal
 *       storefront for the platform's default tenant.</li>
 *   <li>{@code 200 { kind: "tenant", subdomain }} — visitor is on a known,
 *       active tenant subdomain. The SPA renders that tenant's storefront.</li>
 *   <li>{@code 404 { error: "tenant_not_found", subdomain }} — visitor is on
 *       a syntactically valid {@code <sub>.<rootDomain>} that does not match
 *       any active tenant. The SPA renders a "tenant not found" page (no
 *       storefront data is fetched), avoiding the previous behaviour where
 *       any unknown subdomain silently rendered the default tenant.</li>
 * </ul>
 *
 * <p>The hostname read here is whatever {@code request.getServerName()}
 * returns. {@code VisitorHostFilter} runs at HIGHEST_PRECEDENCE and rewrites
 * that to the original visitor host (forwarded by the edge Worker via
 * {@code X-Visitor-Host}), which is what makes this work behind Cloud Run.</p>
 *
 * <p>This endpoint deliberately mirrors {@code TenantResolver}'s rules
 * instead of delegating to it, because {@code TenantResolver} collapses
 * "reserved", "syntax invalid" and "not in DB" into the same fallback
 * (the default tenant). For the SPA we need to tell those apart.</p>
 */
@RestController
@RequestMapping("/public/tenant")
public class PublicTenantController {

    /** Mirror of {@code TenantResolver#RESERVED_SUBDOMAINS}. */
    private static final Set<String> RESERVED_SUBDOMAINS = Set.of(
            "earnlumens", "www", "api", "admin", "app", "app-dev", "api-dev", "cdn",
            "docs", "static", "assets", "mail", "stripe", "billing", "status"
    );

    /** Same RFC-1123-safe pattern used by {@code TenantResolver}. */
    private static final Pattern SUBDOMAIN = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{1,28}[a-z0-9])$");

    @Value("${mediastore.tenant.root-domain:earnlumens.org}")
    private String rootDomain;

    private final TenantConfigService tenantConfigService;

    public PublicTenantController(TenantConfigService tenantConfigService) {
        this.tenantConfigService = tenantConfigService;
    }

    @GetMapping("/visitor")
    public ResponseEntity<Map<String, Object>> visitor(HttpServletRequest request) {
        String host = request.getServerName();
        if (host == null || host.isBlank()) {
            return ResponseEntity.ok(platform());
        }

        String hostname = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
        hostname = hostname.toLowerCase();

        if ("localhost".equals(hostname)
                || "localhost.dv".equals(hostname)
                || "127.0.0.1".equals(hostname)
                || rootDomain.equals(hostname)) {
            return ResponseEntity.ok(platform());
        }

        String suffix = "." + rootDomain;
        if (!hostname.endsWith(suffix)) {
            // Unknown root domain (custom domain, preview deploy, etc.).
            // Treat as platform so the SPA still renders something usable.
            return ResponseEntity.ok(platform());
        }

        String subdomain = hostname.substring(0, hostname.length() - suffix.length());
        if (subdomain.contains(".")
                || RESERVED_SUBDOMAINS.contains(subdomain)
                || !SUBDOMAIN.matcher(subdomain).matches()) {
            return ResponseEntity.ok(platform());
        }

        if (tenantConfigService.findActiveBySubdomain(subdomain).isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "tenant_not_found");
            body.put("subdomain", subdomain);
            return ResponseEntity.status(404).body(body);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "tenant");
        body.put("subdomain", subdomain);
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> platform() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "platform");
        return body;
    }
}
