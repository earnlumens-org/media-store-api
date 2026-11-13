package org.earnlumens.mediastore.infrastructure.tenant;

import java.util.Locale;

/**
 * Read-only mode for a BLOCKED tenant (MYCELIUM-RESILIENCE.md H1).
 * <p>
 * A sanctioned tenant loses every operational capability, but its buyers keep
 * what they legitimately acquired. The rule is an <b>allowlist</b>:
 * <ul>
 *   <li>safe methods ({@code GET}/{@code HEAD}/{@code OPTIONS}) — storefront,
 *       visitor payload, library, entry pages, {@code /api/media/entitlements}
 *       (CDN gate), favourites listing, login redirects;</li>
 *   <li>session plumbing — {@code /api/auth/**} (session, refresh, logout) and
 *       the OAuth2 callback endpoints, so a buyer can sign in to reach the
 *       library;</li>
 *   <li>platform plane — {@code /api/internal/**} and {@code /actuator/**}
 *       (never tenant-scoped by host in practice, listed for completeness).</li>
 * </ul>
 * Everything else — purchases, tips, uploads, publishing, catalogue
 * mutations, franchise/reseller onboarding, ratings, reports — is refused
 * with {@code 423 TENANT_SUSPENDED} before it reaches a controller.
 * Moderation blocks on content are unaffected: {@code ContentAccessPolicy}
 * still applies on every allowed read.
 */
final class TenantSuspensionPolicy {

    static final String ERROR_CODE = "TENANT_SUSPENDED";
    static final int STATUS = 423;

    private TenantSuspensionPolicy() {
    }

    static boolean isAllowed(String method, String path) {
        if (path == null) return false;
        String m = method == null ? "" : method.toUpperCase(Locale.ROOT);
        if ("GET".equals(m) || "HEAD".equals(m) || "OPTIONS".equals(m)) {
            return true;
        }
        return path.startsWith("/api/auth/")
                || path.startsWith("/login/oauth2/")
                || path.startsWith("/oauth2/")
                || path.equals("/api/internal") || path.startsWith("/api/internal/")
                || path.equals("/actuator") || path.startsWith("/actuator/");
    }
}
