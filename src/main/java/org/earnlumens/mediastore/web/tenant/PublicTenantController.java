package org.earnlumens.mediastore.web.tenant;

import jakarta.servlet.http.HttpServletRequest;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantConfigService;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantReadModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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

        var tenantOpt = tenantConfigService.findActiveBySubdomain(subdomain);
        if (tenantOpt.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "tenant_not_found");
            body.put("subdomain", subdomain);
            return ResponseEntity.status(404).body(body);
        }

        var tenant = tenantOpt.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "tenant");
        body.put("subdomain", subdomain);
        applyTenantConfig(body, tenant, subdomain);
        return ResponseEntity.ok(body);
    }

    /**
     * Writes every owner-configurable storefront field from {@code tenant} into
     * {@code body} using the conventions the SPA expects (omit-when-unset for
     * optional fields; emit empty-string when the owner has explicitly opted
     * out of brand text). Shared by both the {@code kind:"tenant"} branch and
     * the {@code kind:"platform"} branch so the root tenant
     * ({@code earnlumens} subdomain) exposes the SAME set of overrides
     * (theme defaults, banner, uploads switch) as any sub-tenant — without
     * this, configuring a theme on the root via admin-ui silently had no
     * effect on the storefront.
     *
     * @param displayFallback last-resort brand label when neither
     *                        {@code brandText} nor {@code title} is set.
     *                        {@code null} skips the fallback (platform case,
     *                        where the SPA owns the EARNLUMENS hardcoded brand).
     */
    private static void applyTenantConfig(Map<String, Object> body,
                                          TenantReadModel tenant,
                                          String displayFallback) {
        // Storefront app-bar label. Falls back from brandText (owner override)
        // to title (tenant display name) so a brand new tenant is usable from
        // second zero even before the owner customises it. When the owner has
        // flipped on "logo-only" mode, send an empty string so the storefront
        // can render no text at all (distinct from "unset" / null).
        if (tenant.isBrandTextHidden()) {
            body.put("brandText", "");
            body.put("brandTextHidden", true);
        } else {
            String label = displayFallback != null
                ? firstNonBlank(tenant.getBrandText(), tenant.getTitle(), displayFallback)
                : firstNonBlank(tenant.getBrandText(), tenant.getTitle(), null);
            if (label != null) {
                body.put("brandText", label);
            }
        }
        // Optional R2 key of the tenant's custom logo. The SPA composes the
        // CDN URL itself (cdnBaseUrl + key); omit when unset so the AppBar
        // falls back to the hardcoded EARNLUMENS svg.
        if (tenant.getLogoR2Key() != null && !tenant.getLogoR2Key().isBlank()) {
            body.put("logoR2Key", tenant.getLogoR2Key());
        }
        if (tenant.getLogoR2KeyDark() != null && !tenant.getLogoR2KeyDark().isBlank()) {
            body.put("logoR2KeyDark", tenant.getLogoR2KeyDark());
        }
        // Hero banner block — only emitted when the owner has flipped the
        // master switch on. Keeping the payload empty otherwise lets the SPA
        // skip rendering without an extra round-trip.
        if (tenant.isBannerEnabled()) {
            Map<String, Object> banner = new LinkedHashMap<>();
            banner.put("enabled", true);
            if (tenant.getBannerImageR2Key() != null && !tenant.getBannerImageR2Key().isBlank()) {
                banner.put("imageR2Key", tenant.getBannerImageR2Key());
            }
            if (tenant.getBannerEyebrow() != null && !tenant.getBannerEyebrow().isBlank()) {
                banner.put("eyebrow", tenant.getBannerEyebrow());
            }
            if (tenant.getBannerHeadline() != null && !tenant.getBannerHeadline().isBlank()) {
                banner.put("headline", tenant.getBannerHeadline());
            }
            if (tenant.getBannerSubheadline() != null && !tenant.getBannerSubheadline().isBlank()) {
                banner.put("subheadline", tenant.getBannerSubheadline());
            }
            if (tenant.getBannerCtaLabel() != null && !tenant.getBannerCtaLabel().isBlank()) {
                banner.put("ctaLabel", tenant.getBannerCtaLabel());
            }
            if (tenant.getBannerCtaUrl() != null && !tenant.getBannerCtaUrl().isBlank()) {
                banner.put("ctaUrl", tenant.getBannerCtaUrl());
            }
            if (tenant.getBannerImageAlt() != null && !tenant.getBannerImageAlt().isBlank()) {
                banner.put("imageAlt", tenant.getBannerImageAlt());
            }
            body.put("banner", banner);
        }
        // Per-tenant default Vuetify theme picks. Emitted only when the owner
        // has set them, so the storefront can keep its hardcoded defaults
        // (DEFAULT_LIGHT_THEME / DEFAULT_DARK_THEME) when the keys are null.
        if (tenant.getDefaultLightTheme() != null && !tenant.getDefaultLightTheme().isBlank()) {
            body.put("defaultLightTheme", tenant.getDefaultLightTheme());
        }
        if (tenant.getDefaultDarkTheme() != null && !tenant.getDefaultDarkTheme().isBlank()) {
            body.put("defaultDarkTheme", tenant.getDefaultDarkTheme());
        }
        // Uploads kill switch. Only emit when explicitly disabled so the
        // payload stays tiny for the vast majority of tenants where uploads
        // are on. The storefront treats a missing field as "enabled".
        if (!tenant.isUploadsEnabled()) {
            body.put("uploadsEnabled", false);
        }
    }

    /**
     * Resolves the tenant document that backs the platform / root domain
     * (subdomain matches the root domain's leading label — e.g.
     * {@code earnlumens} for {@code earnlumens.org}). Empty when the doc
     * does not exist; the SPA then falls back to its hardcoded brand +
     * default themes.
     */
    private Optional<TenantReadModel> loadRootTenant() {
        String rootSub = rootDomain.contains(".")
                ? rootDomain.substring(0, rootDomain.indexOf('.'))
                : rootDomain;
        return tenantConfigService.findActiveBySubdomain(rootSub);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private Map<String, Object> platform() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "platform");
        // The platform context still mirrors the root tenant document so the
        // owner can configure brand, theme, banner and uploads from the same
        // admin-ui flow that powers every other tenant. We deliberately pass
        // a null displayFallback so the SPA keeps its hardcoded EARNLUMENS
        // brand when the owner has not set a brandText.
        loadRootTenant().ifPresent(t -> applyTenantConfig(body, t, null));
        return body;
    }
}
