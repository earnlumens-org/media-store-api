package org.earnlumens.mediastore.infrastructure.security.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.earnlumens.mediastore.application.auth.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    /**
     * Apex URL the SPA is served from. Used as the default landing page when
     * the OAuth flow did not originate from a tenant subdomain (e.g. login
     * started on the apex itself).
     */
    @Value("${mediastore.frontend.uri}")
    private String frontendBaseUri;

    /**
     * Apex domain (e.g. {@code earnlumens.org}) used to build per-tenant
     * redirect URLs of the form {@code https://<tenant>.<rootDomain>}.
     */
    @Value("${mediastore.tenant.root-domain:earnlumens.org}")
    private String rootDomain;

    private final AuthService authService;

    public OAuth2AuthenticationSuccessHandler(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        String tempUUID = authService.generateTempUUID(authentication);
        String redirectUrl = resolveCallbackBaseUrl(request) + "/oauth2/callback?UUID=" + tempUUID;
        response.sendRedirect(redirectUrl);
    }

    /**
     * Returns the origin the SPA must land on after OAuth completes. If the
     * resolver captured a verified custom-domain host (login came from a
     * tenant's own domain, custom-domain-upgrade 3.3) the user is sent back
     * there; else if it captured an originating tenant (login came from a
     * tenant subdomain), the user is sent back to that subdomain so the
     * {@code _rFTo} cookie subsequently emitted by {@code POST /api/auth/session}
     * stays scoped to the right host. Otherwise we fall back to the apex SPA.
     * <p>
     * Both session attributes are consumed (removed) so a stale value cannot
     * affect a subsequent OAuth flow on the same browser session. The values
     * were validated against the database at resolve() time (return_host only
     * matches verified-ACTIVE custom domains), so concatenation here cannot
     * produce a redirect outside a host we serve.
     */
    private String resolveCallbackBaseUrl(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return frontendBaseUri;
        }
        Object hostAttr = session.getAttribute(TenantOAuth2AuthorizationRequestResolver.RETURN_HOST_SESSION_ATTR);
        session.removeAttribute(TenantOAuth2AuthorizationRequestResolver.RETURN_HOST_SESSION_ATTR);
        Object attr = session.getAttribute(TenantOAuth2AuthorizationRequestResolver.SESSION_ATTR);
        session.removeAttribute(TenantOAuth2AuthorizationRequestResolver.SESSION_ATTR);

        if (hostAttr instanceof String returnHost && !returnHost.isBlank()) {
            return "https://" + returnHost;
        }
        if (!(attr instanceof String tenant) || tenant.isBlank()) {
            return frontendBaseUri;
        }
        // Tenant value was already validated against subdomain regex,
        // reserved-list, and DB existence at resolve() time, so concatenation
        // here cannot produce a redirect outside our zone.
        return "https://" + tenant + "." + rootDomain;
    }
}
