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

    /** How the visitor reached us; drives both the visitor payload and the PWA manifest. */
    enum HostKind { PLATFORM, SUBDOMAIN, CUSTOM_DOMAIN, NOT_FOUND }

    /**
     * Result of classifying the visitor host. {@code subdomain} is the
     * tenant slug for SUBDOMAIN/CUSTOM_DOMAIN hits and the typed label for
     * subdomain misses; {@code tenant} is non-null only on hits.
     */
    record HostContext(HostKind kind, String hostname, String subdomain, TenantReadModel tenant) {
        boolean isCustomDomain() { return kind == HostKind.CUSTOM_DOMAIN; }
    }

    /**
     * Classifies {@code request.getServerName()} with the same rules the
     * visitor endpoint has always used: apex/loopback/{@code .run.app}/reserved
     * ⇒ PLATFORM; valid {@code <sub>.<root>} ⇒ SUBDOMAIN (or NOT_FOUND);
     * anything else ⇒ CUSTOM_DOMAIN gated by {@code findActiveByCustomDomain}
     * (or NOT_FOUND — never platform, decision #6).
     */
    HostContext classify(HttpServletRequest request) {
        String host = request.getServerName();
        if (host == null || host.isBlank()) {
            return new HostContext(HostKind.PLATFORM, "", null, null);
        }
        String hostname = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
        hostname = hostname.toLowerCase();

        if ("localhost".equals(hostname)
                || "localhost.dv".equals(hostname)
                || "127.0.0.1".equals(hostname)
                || rootDomain.equals(hostname)) {
            return new HostContext(HostKind.PLATFORM, hostname, null, null);
        }

        String suffix = "." + rootDomain;
        if (!hostname.endsWith(suffix)) {
            if (hostname.endsWith(".run.app")) {
                // Direct Cloud Run host (probes, internal tooling) — platform.
                return new HostContext(HostKind.PLATFORM, hostname, null, null);
            }
            // Custom domain (custom-domain-upgrade 3.2): only when the domain
            // is verified ACTIVE and the tenant is Pro; anything else is a
            // hard 404 (no platform fallback).
            var byDomain = tenantConfigService.findActiveByCustomDomain(hostname);
            if (byDomain.isEmpty()) {
                return new HostContext(HostKind.NOT_FOUND, hostname, null, null);
            }
            return new HostContext(HostKind.CUSTOM_DOMAIN, hostname, byDomain.get().getSubdomain(), byDomain.get());
        }

        String subdomain = hostname.substring(0, hostname.length() - suffix.length());
        if (subdomain.contains(".")
                || RESERVED_SUBDOMAINS.contains(subdomain)
                || !SUBDOMAIN.matcher(subdomain).matches()) {
            return new HostContext(HostKind.PLATFORM, hostname, null, null);
        }

        var tenantOpt = tenantConfigService.findActiveBySubdomain(subdomain);
        if (tenantOpt.isEmpty()) {
            return new HostContext(HostKind.NOT_FOUND, hostname, subdomain, null);
        }
        return new HostContext(HostKind.SUBDOMAIN, hostname, subdomain, tenantOpt.get());
    }

    private static ResponseEntity<Map<String, Object>> notFound(HostContext ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "tenant_not_found");
        if (ctx.subdomain() != null) {
            body.put("subdomain", ctx.subdomain());
        } else {
            body.put("host", ctx.hostname());
        }
        return ResponseEntity.status(404).body(body);
    }

    @GetMapping("/visitor")
    public ResponseEntity<Map<String, Object>> visitor(HttpServletRequest request) {
        HostContext ctx = classify(request);
        Map<String, Object> body;
        switch (ctx.kind()) {
            case NOT_FOUND -> {
                return notFound(ctx);
            }
            case PLATFORM -> body = platform();
            default -> {
                body = new LinkedHashMap<>();
                body.put("kind", "tenant");
                body.put("subdomain", ctx.subdomain());
                applyTenantConfig(body, ctx.tenant(), ctx.subdomain());
                // Human-readable app/store name, independent of the logo-only
                // switch (brandText is blanked when hidden). Same chain as the
                // manifest so the install banner and the installed app agree.
                body.put("appName", appNameFor(ctx));
            }
        }
        // PWA install CTA policy: offered on the platform apex and on custom
        // domains (each is its own branded app); NOT offered on tenant
        // subdomains so users don't collect look-alike platform-badged apps.
        // The manifest is still served there (see /manifest), so a manual
        // "Add to Home screen" works — just without our prompt.
        body.put("installOffered", ctx.kind() != HostKind.SUBDOMAIN);
        // Own PWA icon only on custom domains (same policy as /manifest): the
        // SPA uses it for the iOS apple-touch-icon swap. Subdomains get the
        // generic badge, so the key is deliberately not emitted there.
        if (ctx.isCustomDomain()) {
            String ownIcon = ctx.tenant().getPwaIconR2Key();
            if (ownIcon != null && APPICON_KEY.matcher(ownIcon).matches()) {
                body.put("pwaIconR2Key", ownIcon);
            }
        }
        return ResponseEntity.ok(body);
    }

    // ------------------------------------------------------------ PWA manifest

    /** Platform-default colours; mirror media-store-ui/vite.config.mts manifest. */
    private static final String MANIFEST_THEME_COLOR = "#10131A";
    private static final String MANIFEST_DESCRIPTION =
            "A collaborative financial education platform on the Stellar network";
    private static final String PLATFORM_APP_NAME = "Earn Lumens";
    /** Web App Manifest spec recommends ≤ 12 chars for short_name. */
    private static final int SHORT_NAME_MAX = 12;
    private static final int NAME_MAX = 45;
    /** Only hostnames of this shape are echoed back into related_applications. */
    private static final Pattern SAFE_HOSTNAME = Pattern.compile("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$");
    /** R2 keys we are willing to turn into a manifest icon URL (set by admin-api's presign). */
    private static final Pattern APPICON_KEY = Pattern.compile("^public/tenants/[a-z0-9-]{3,30}/appicon/[A-Za-z0-9-]+\\.png$");

    /**
     * Per-host Web App Manifest. tenants-router rewrites
     * {@code GET /manifest.webmanifest} to this endpoint so every origin
     * (apex, tenant subdomain, custom domain) installs as a distinguishable
     * app while {@code id}/{@code scope}/{@code start_url} stay relative
     * (PWA identity is per-origin by construction):
     * <ul>
     *   <li>PLATFORM ⇒ the official Earn Lumens manifest.</li>
     *   <li>SUBDOMAIN ⇒ tenant name + the generic "store on EarnLumens"
     *       badge icon (never the tenant's own icon, never the bare
     *       platform icon).</li>
     *   <li>CUSTOM_DOMAIN ⇒ tenant name + its own icon when uploaded, else
     *       the badge icon.</li>
     *   <li>NOT_FOUND ⇒ 404 (the Worker then falls back to the static
     *       manifest from Pages; TenantFilter normally short-circuits before).</li>
     * </ul>
     * Everything serialized here is either a constant, an admin-api-validated
     * tenant field (title/brandText are length-capped and JSON-escaped by
     * Jackson) or a regex-allowlisted key/host.
     */
    @GetMapping(value = "/manifest", produces = "application/manifest+json")
    public ResponseEntity<Map<String, Object>> manifest(HttpServletRequest request) {
        HostContext ctx = classify(request);
        if (ctx.kind() == HostKind.NOT_FOUND) {
            return notFound(ctx);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "/");
        String name;
        java.util.List<Map<String, Object>> icons;
        if (ctx.kind() == HostKind.PLATFORM) {
            name = PLATFORM_APP_NAME;
            icons = platformIcons("/pwa/pwa-192.png", "/pwa/pwa-512.png", "/pwa/pwa-maskable-512.png");
        } else {
            TenantReadModel t = ctx.tenant();
            name = appNameFor(ctx);
            String ownIcon = ctx.isCustomDomain() ? t.getPwaIconR2Key() : null;
            if (ownIcon != null && APPICON_KEY.matcher(ownIcon).matches()) {
                String src = "/cdn/" + ownIcon;
                icons = java.util.List.of(icon(src, "512x512", "any"), icon(src, "512x512", "maskable"));
            } else {
                icons = platformIcons("/pwa/pwa-tenant-192.png", "/pwa/pwa-tenant-512.png", "/pwa/pwa-tenant-maskable-512.png");
            }
        }
        name = truncate(name.trim(), NAME_MAX);
        m.put("name", name);
        m.put("short_name", truncate(name, SHORT_NAME_MAX));
        m.put("description", MANIFEST_DESCRIPTION);
        m.put("theme_color", MANIFEST_THEME_COLOR);
        m.put("background_color", MANIFEST_THEME_COLOR);
        m.put("display", "standalone");
        // No `orientation` lock: the storefront must stay readable in both
        // portrait and landscape (video/landscape phones, tablets, desktop).
        m.put("scope", "/");
        m.put("start_url", "/?source=pwa");
        if (SAFE_HOSTNAME.matcher(ctx.hostname()).matches()) {
            m.put("related_applications", java.util.List.of(Map.of(
                    "platform", "webapp",
                    "url", "https://" + ctx.hostname() + "/manifest.webmanifest")));
        }
        m.put("icons", icons);
        return ResponseEntity.ok(m);
    }

    /**
     * Tenant app/store name: browserTitle → brandText → title → brand label of
     * the custom domain → subdomain. A title identical to the subdomain slug
     * is a placeholder, not a name, so it is skipped; on a custom domain the
     * domain the owner chose is a better brand than the slug.
     */
    private static String appNameFor(HostContext ctx) {
        TenantReadModel t = ctx.tenant();
        String title = t.getTitle();
        if (title != null && title.trim().equalsIgnoreCase(ctx.subdomain())) {
            title = null;
        }
        String domainLabel = ctx.isCustomDomain() ? brandLabelFromHost(ctx.hostname()) : null;
        String name = firstNonBlank(t.getBrowserTitle(), t.getBrandText(), title, domainLabel, ctx.subdomain());
        return truncate(name.trim(), NAME_MAX);
    }

    /** Public suffixes with two labels where the brand sits one label further left. */
    private static final Set<String> MULTI_LABEL_PUBLIC_SUFFIXES = Set.of(
            "co.uk", "org.uk", "ac.uk", "gov.uk", "com.ar", "com.br", "com.mx", "com.au",
            "com.co", "com.pe", "com.uy", "com.ve", "com.ec", "com.bo", "com.py", "com.tr",
            "co.jp", "co.kr", "co.nz", "co.za", "com.es", "com.pt", "net.ar", "org.ar");

    /**
     * Brand label of a custom hostname: drops a leading {@code www.} and
     * returns the registrable-domain label ({@code www.udemo.app} ⇒ {@code udemo},
     * {@code shop.acme.com} ⇒ {@code acme}, {@code tienda.com.ar} ⇒ {@code tienda}).
     * Null when the host has fewer than two labels.
     */
    static String brandLabelFromHost(String hostname) {
        if (hostname == null || hostname.isBlank()) return null;
        String host = hostname.toLowerCase();
        if (host.startsWith("www.")) host = host.substring(4);
        String[] labels = host.split("\\.");
        if (labels.length < 2) return null;
        String suffix2 = labels[labels.length - 2] + "." + labels[labels.length - 1];
        int idx = (labels.length >= 3 && MULTI_LABEL_PUBLIC_SUFFIXES.contains(suffix2))
                ? labels.length - 3
                : labels.length - 2;
        String label = labels[idx];
        return label.isBlank() ? null : label;
    }

    private static java.util.List<Map<String, Object>> platformIcons(String s192, String s512, String maskable) {
        return java.util.List.of(
                icon(s192, "192x192", "any"),
                icon(s512, "512x512", "any"),
                icon(maskable, "512x512", "maskable"));
    }

    private static Map<String, Object> icon(String src, String sizes, String purpose) {
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("src", src);
        i.put("sizes", sizes);
        i.put("type", "image/png");
        i.put("purpose", purpose);
        return i;
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max).trim();
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
        // Hiding the EarnLumens branding is a PRO feature: honoured only while
        // the plan (incl. grace) is active — read-side enforcement (1C.2), so
        // an expired tenant regains the default branding within the cache TTL
        // even before the hourly downgrade sweep persists the change.
        if (tenant.isBrandTextHidden() && tenant.isPro(java.time.Instant.now())) {
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
        // Optional per-tenant favicon. Storefront does a runtime swap of the
        // in-document <link rel="icon"> when this is set so each tenant can
        // show its own browser-tab icon; omit when unset so the SPA keeps the
        // baked-in EARNLUMENS favicon.
        if (tenant.getFaviconR2Key() != null && !tenant.getFaviconR2Key().isBlank()) {
            body.put("faviconR2Key", tenant.getFaviconR2Key());
        }
        // Optional browser-tab title. SPA writes it into document.title at
        // runtime; omit when unset so the baked-in <title> from index.html
        // wins (which is the EARNLUMENS default).
        if (tenant.getBrowserTitle() != null && !tenant.getBrowserTitle().isBlank()) {
            body.put("browserTitle", tenant.getBrowserTitle());
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
        // Per-tenant content-type allowlist. Only emit when restricted so
        // legacy tenants (and the platform/root) keep paying zero bytes.
        // The storefront treats a missing field as "all types allowed".
        if (tenant.getAllowedEntryTypes() != null && !tenant.getAllowedEntryTypes().isEmpty()) {
            body.put("allowedEntryTypes", tenant.getAllowedEntryTypes());
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

    /**
     * Root-tenant fallback themes. These were the platform defaults before
     * commit 4e624d6 swapped the storefront-wide defaults to nord/tokyoNight,
     * and remain the look-and-feel users associate with the EARNLUMENS root
     * site. We re-apply them here (only when the root tenant doc has not set
     * an explicit override) so the root domain keeps its historical theme
     * instead of inheriting the generic sub-tenant defaults.
     */
    private static final String ROOT_DEFAULT_LIGHT_THEME = "neoBrutalArt";
    private static final String ROOT_DEFAULT_DARK_THEME = "amoledGray";

    private Map<String, Object> platform() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "platform");
        // The platform context still mirrors the root tenant document so the
        // owner can configure brand, theme, banner and uploads from the same
        // admin-ui flow that powers every other tenant. We deliberately pass
        // a null displayFallback so the SPA keeps its hardcoded EARNLUMENS
        // brand when the owner has not set a brandText.
        loadRootTenant().ifPresent(t -> applyTenantConfig(body, t, null));
        // Root-tenant theme fallback: when the owner has not picked an
        // override, force rosePineDawn/amoledGray so the storefront does
        // NOT fall through to its generic nord/tokyoNight defaults.
        body.putIfAbsent("defaultLightTheme", ROOT_DEFAULT_LIGHT_THEME);
        body.putIfAbsent("defaultDarkTheme", ROOT_DEFAULT_DARK_THEME);
        return body;
    }
}
