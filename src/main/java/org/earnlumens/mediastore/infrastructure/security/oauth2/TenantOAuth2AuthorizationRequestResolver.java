package org.earnlumens.mediastore.infrastructure.security.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * OAuth2 authorization request resolver that captures the originating tenant.
 * <p>
 * X (and most OAuth providers) require redirect URIs to be statically registered
 * — wildcard subdomains are not supported. To keep the registration list short
 * the entire OAuth handshake runs on the apex origin (earnlumens.org), but the
 * end-user session must end up on the tenant subdomain they came from. The SPA
 * therefore opens
 *
 *   https://earnlumens.org/oauth2/authorization/x?tenant=acme
 *
 * when login starts on {@code acme.earnlumens.org}. This resolver picks up the
 * {@code tenant} query parameter, validates it against the same rules used by
 * {@link org.earnlumens.mediastore.infrastructure.tenant.TenantResolver}, and
 * stores it in the HTTP session so
 * {@link OAuth2AuthenticationSuccessHandler} can redirect the browser back to
 * the originating tenant after the provider hands the code back.
 *
 * <p>Custom domains (custom-domain-upgrade 3.3): when login starts on a
 * tenant's own domain the SPA sends {@code return_host=<fqdn>} instead. The
 * host is validated against the database — it must be a verified-ACTIVE
 * custom domain of an ACTIVE, Pro tenant
 * ({@code TenantConfigService#findActiveByCustomDomain}) — never trusted from
 * the parameter alone, and hosts under our own root domain are rejected
 * (subdomains must keep using {@code tenant=}).</p>
 *
 * <h3>Open-redirect hardening</h3>
 * The redirect target is always built as {@code https://<tenant>.<rootDomain>}
 * (or {@code https://<return_host>} where the host was verified ACTIVE in the
 * database) — the raw parameter never reaches the URL whole. Combined with the
 * regex + reserved-subdomain check + an "active tenant exists in DB"
 * gate, this prevents:
 * <ul>
 *   <li>scheme/host injection (only {@code [a-z0-9-]} accepted);</li>
 *   <li>landing on system subdomains (api, admin, cdn, …);</li>
 *   <li>landing on subdomains that aren't real tenants (the value is dropped
 *       and the apex is used instead).</li>
 * </ul>
 */
public class TenantOAuth2AuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    public static final String SESSION_ATTR = "oauth.originating.tenant";
    /** Verified custom-domain host the browser must land on after OAuth (3.3). */
    public static final String RETURN_HOST_SESSION_ATTR = "oauth.originating.returnHost";

    private static final Logger logger = LoggerFactory.getLogger(TenantOAuth2AuthorizationRequestResolver.class);

    /** Mirror of TenantResolver#RESERVED_SUBDOMAINS. Kept private to avoid
     *  accidental coupling — drift here is far less dangerous than there
     *  because this list only gates the OAuth redirect target. */
    private static final Set<String> RESERVED_SUBDOMAINS = Set.of(
            "earnlumens", "www", "api", "admin", "app", "app-dev", "api-dev",
            "cdn", "cdn-dev", "admin-dev", "docs", "static", "assets",
            "mail", "stripe", "billing", "status"
    );

    /** Same regex as TenantResolver. */
    private static final Pattern SUBDOMAIN = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{1,28}[a-z0-9])$");

    /** RFC-1123 hostname label (for return_host syntax pre-check). */
    private static final Pattern HOST_LABEL = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");

    private final OAuth2AuthorizationRequestResolver delegate;
    private final TenantConfigService tenantConfigService;
    private final String rootDomain;

    public TenantOAuth2AuthorizationRequestResolver(
            OAuth2AuthorizationRequestResolver delegate,
            TenantConfigService tenantConfigService,
            String rootDomain
    ) {
        this.delegate = delegate;
        this.tenantConfigService = tenantConfigService;
        this.rootDomain = rootDomain == null ? "earnlumens.org" : rootDomain.toLowerCase();
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        OAuth2AuthorizationRequest authReq = delegate.resolve(request);
        captureOriginatingTenant(request, authReq);
        return authReq;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        OAuth2AuthorizationRequest authReq = delegate.resolve(request, clientRegistrationId);
        captureOriginatingTenant(request, authReq);
        return authReq;
    }

    /**
     * Side-effect only: when the inbound request actually starts an OAuth
     * authorization (i.e. the delegate produced a non-null request), parse the
     * {@code return_host} / {@code tenant} query parameters, validate them, and
     * stash the winner in the session for the success handler. If validation
     * fails both attributes are cleared so a stale value from a previous flow
     * can't influence the redirect target.
     */
    private void captureOriginatingTenant(HttpServletRequest request, OAuth2AuthorizationRequest authReq) {
        if (authReq == null) {
            return; // Not an authorization request (e.g. wrong path) — leave session alone.
        }

        HttpSession session = request.getSession(true);

        // Custom domain flow takes precedence: validated against the DB, only
        // verified-ACTIVE domains of Pro tenants are ever accepted (3.3).
        String rawHost = request.getParameter("return_host");
        String validatedHost = validateReturnHost(rawHost);
        if (validatedHost != null) {
            session.setAttribute(RETURN_HOST_SESSION_ATTR, validatedHost);
            session.removeAttribute(SESSION_ATTR);
            logger.debug("OAuth flow originated from custom domain={}", validatedHost);
            return;
        }
        session.removeAttribute(RETURN_HOST_SESSION_ATTR);
        if (rawHost != null && !rawHost.isBlank()) {
            logger.warn("Discarding invalid OAuth return_host parameter: {}", rawHost);
        }

        String raw = request.getParameter("tenant");
        String validated = validateTenant(raw);

        if (validated != null) {
            session.setAttribute(SESSION_ATTR, validated);
            logger.debug("OAuth flow originated from tenant={}", validated);
        } else {
            // Either no tenant param (login from apex) or invalid value. Drop
            // any previous attribute so the success handler falls back to apex.
            session.removeAttribute(SESSION_ATTR);
            if (raw != null && !raw.isBlank()) {
                logger.warn("Discarding invalid OAuth tenant parameter: {}", raw);
            }
        }
    }

    /**
     * Returns a normalized tenant subdomain when the input is a syntactically
     * valid, non-reserved, currently-active tenant; {@code null} otherwise.
     */
    private String validateTenant(String raw) {
        if (raw == null) return null;
        String candidate = raw.trim().toLowerCase();
        if (candidate.isEmpty()) return null;
        if (!SUBDOMAIN.matcher(candidate).matches()) return null;
        if (RESERVED_SUBDOMAINS.contains(candidate)) return null;
        if (tenantConfigService.findActiveBySubdomain(candidate).isEmpty()) return null;
        return candidate;
    }

    /**
     * Returns a normalized custom-domain host when the input is a syntactically
     * valid FQDN, outside our own zone, and currently servable (verified ACTIVE
     * domain of an ACTIVE, Pro tenant — checked against the database, never
     * trusted from the parameter); {@code null} otherwise.
     */
    private String validateReturnHost(String raw) {
        if (raw == null) return null;
        String host = raw.trim().toLowerCase();
        if (host.isEmpty() || host.length() > 253) return null;
        String[] labels = host.split("\\.", -1);
        if (labels.length < 2) return null;
        for (String label : labels) {
            if (!HOST_LABEL.matcher(label).matches()) return null;
        }
        // Hosts on our own zone must use the tenant= flow — never return_host.
        if (host.equals(rootDomain) || host.endsWith("." + rootDomain)) return null;
        if (tenantConfigService.findActiveByCustomDomain(host).isEmpty()) return null;
        return host;
    }
}
