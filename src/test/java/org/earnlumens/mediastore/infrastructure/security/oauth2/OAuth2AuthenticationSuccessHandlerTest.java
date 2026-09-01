package org.earnlumens.mediastore.infrastructure.security.oauth2;

import org.earnlumens.mediastore.application.auth.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OAuth2AuthenticationSuccessHandler}: the post-OAuth
 * redirect target must honour (in order) the verified custom-domain host
 * (custom-domain-upgrade 3.3), the originating tenant subdomain, and finally
 * the apex SPA — and must consume the session attributes either way.
 */
class OAuth2AuthenticationSuccessHandlerTest {

    private static final String UUID_VALUE = "123e4567-e89b-12d3-a456-426614174000";

    private OAuth2AuthenticationSuccessHandler handler;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        AuthService authService = mock(AuthService.class);
        authentication = mock(Authentication.class);
        when(authService.generateTempUUID(authentication)).thenReturn(UUID_VALUE);
        handler = new OAuth2AuthenticationSuccessHandler(authService);
        ReflectionTestUtils.setField(handler, "frontendBaseUri", "https://earnlumens.org");
        ReflectionTestUtils.setField(handler, "rootDomain", "earnlumens.org");
    }

    @Test
    void noSessionAttributes_redirectsToApex() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication);

        assertEquals("https://earnlumens.org/oauth2/callback?UUID=" + UUID_VALUE,
                response.getRedirectedUrl());
    }

    @Test
    void tenantAttribute_redirectsToSubdomain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(TenantOAuth2AuthorizationRequestResolver.SESSION_ATTR, "alice");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication);

        assertEquals("https://alice.earnlumens.org/oauth2/callback?UUID=" + UUID_VALUE,
                response.getRedirectedUrl());
        assertNull(request.getSession().getAttribute(TenantOAuth2AuthorizationRequestResolver.SESSION_ATTR));
    }

    @Test
    void returnHostAttribute_redirectsToCustomDomain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(
                TenantOAuth2AuthorizationRequestResolver.RETURN_HOST_SESSION_ATTR, "shop.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication);

        assertEquals("https://shop.example.com/oauth2/callback?UUID=" + UUID_VALUE,
                response.getRedirectedUrl());
        assertNull(request.getSession().getAttribute(
                TenantOAuth2AuthorizationRequestResolver.RETURN_HOST_SESSION_ATTR));
    }

    @Test
    void returnHost_winsOverTenant_andBothAreConsumed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(
                TenantOAuth2AuthorizationRequestResolver.RETURN_HOST_SESSION_ATTR, "shop.example.com");
        request.getSession().setAttribute(TenantOAuth2AuthorizationRequestResolver.SESSION_ATTR, "alice");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication);

        assertEquals("https://shop.example.com/oauth2/callback?UUID=" + UUID_VALUE,
                response.getRedirectedUrl());
        assertNull(request.getSession().getAttribute(
                TenantOAuth2AuthorizationRequestResolver.RETURN_HOST_SESSION_ATTR));
        assertNull(request.getSession().getAttribute(TenantOAuth2AuthorizationRequestResolver.SESSION_ATTR));
    }
}
