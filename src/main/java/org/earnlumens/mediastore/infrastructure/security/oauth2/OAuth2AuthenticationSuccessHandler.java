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
     * resolver captured an originating tenant in the session (login came from
     * a tenant subdomain), the user is sent back there so the {@code _rFTo}
     * cookie subsequently emitted by {@code POST /api/auth/session} stays
     * scoped to the right host. Otherwise we fall back to the apex SPA.
     * <p>
     * The session attribute is consumed (removed) so a stale value cannot
     * affect a subsequent OAuth flow on the same browser session.
     */
    private String resolveCallbackBaseUrl(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return frontendBaseUri;
        }
        Object attr = session.getAttribute(TenantOAuth2AuthorizationRequestResolver.SESSION_ATTR);
        session.removeAttribute(TenantOAuth2AuthorizationRequestResolver.SESSION_ATTR);
        if (!(attr instanceof String tenant) || tenant.isBlank()) {
            return frontendBaseUri;
        }
        // Tenant value was already validated against subdomain regex,
        // reserved-list, and DB existence at resolve() time, so concatenation
        // here cannot produce a redirect outside our zone.
        return "https://" + tenant + "." + rootDomain;
    }
}
