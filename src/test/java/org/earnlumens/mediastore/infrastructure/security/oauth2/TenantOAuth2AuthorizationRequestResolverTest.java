package org.earnlumens.mediastore.infrastructure.security.oauth2;

import org.earnlumens.mediastore.infrastructure.tenant.read.TenantConfigService;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantReadModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenantOAuth2AuthorizationRequestResolver}: capture and
 * validation of the {@code tenant} and {@code return_host} parameters
 * (custom-domain-upgrade 3.3 — return_host is only honoured for
 * verified-ACTIVE custom domains found in the database; anything else is
 * discarded to prevent open redirects).
 */
class TenantOAuth2AuthorizationRequestResolverTest {

    private OAuth2AuthorizationRequestResolver delegate;
    private TenantConfigService tenantConfigService;
    private TenantOAuth2AuthorizationRequestResolver resolver;

    @BeforeEach
    void setUp() {
        delegate = mock(OAuth2AuthorizationRequestResolver.class);
        tenantConfigService = mock(TenantConfigService.class);
        resolver = new TenantOAuth2AuthorizationRequestResolver(delegate, tenantConfigService, "earnlumens.org");
        // Delegate produces an authorization request (i.e. the request really
        // starts an OAuth flow) unless a test overrides it.
        when(delegate.resolve(any())).thenReturn(authRequest());
        lenient().when(tenantConfigService.findActiveBySubdomain(anyString())).thenReturn(Optional.empty());
        lenient().when(tenantConfigService.findActiveByCustomDomain(anyString())).thenReturn(Optional.empty());
    }

    private static OAuth2AuthorizationRequest authRequest() {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://x.com/i/oauth2/authorize")
                .clientId("client-id")
                .build();
    }

    private static TenantReadModel activeTenant(String sub) {
        TenantReadModel t = new TenantReadModel();
        t.setSubdomain(sub);
        t.setStatus("ACTIVE");
        return t;
    }

    private MockHttpServletRequest oauthStart() {
        return new MockHttpServletRequest("GET", "/oauth2/authorization/x");
    }

    private Object sessionAttr(MockHttpServletRequest request, String attr) {
        return request.getSession().getAttribute(attr);
    }

    @Test
    void validReturnHost_isStoredInSession() {
        when(tenantConfigService.findActiveByCustomDomain("shop.example.com"))
                .thenReturn(Optional.of(activeTenant("alice")));
        MockHttpServletRequest request = oauthStart();
        request.setParameter("return_host", "shop.example.com");

        resolver.resolve(request);

        assertEquals("shop.example.com",
                sessionAttr(request, TenantOAuth2AuthorizationRequestResolver.RETURN_HOST_SESSION_ATTR));
        assertNull(sessionAttr(request, TenantOAuth2AuthorizationRequestResolver.SESSION_ATTR));
    }

    @Test
    void returnHost_isNormalizedToLowercase() {
        when(tenantConfigService.findActiveByCustomDomain("shop.example.com"))
                .thenReturn(Optional.of(activeTenant("alice")));
        MockHttpServletRequest request = oauthStart();
        request.setParameter("return_host", "  Shop.EXAMPLE.com ");

        resolver.resolve(request);

        assertEquals("shop.example.com",
                sessionAttr(request, TenantOAuth2AuthorizationRequestResolver.RETURN_HOST_SESSION_ATTR));
    }

    @Test
    void unknownReturnHost_isDiscarded() {
        // Not in DB (unknown, pending, suspended or plan-expired) — same result.
        MockHttpServletRequest request = oauthStart();
        request.setParameter("return_host", "attacker.example.com");

        resolver.resolve(request);

        assertNull(sessionAttr(request, TenantOAuth2AuthorizationRequestResolver.RETURN_HOST_SESSION_ATTR));
    }

    @Test
    void returnHostOnOwnZone_isDiscarded() {
        // Subdomains must use the tenant= flow; never accept them via return_host,
        // even if a (hypothetical) matching row existed in the DB.
        lenient().when(tenantConfigService.findActiveByCustomDomain("alice.earnlumens.org"))
                .thenReturn(Optional.of(activeTenant("alice")));
        MockHttpServletRequest request = oauthStart();
        request.setParameter("return_host", "alice.earnlumens.org");

        resolver.resolve(request);

        assertNull(sessionAttr(request, TenantOAuth2AuthorizationRequestResolver.RETURN_HOST_SESSION_ATTR));
    }

    @Test
    void syntacticallyInvalidReturnHost_isDiscardedWithoutDbLookup() {
        MockHttpServletRequest request = oauthStart();
        request.setParameter("return_host", "https://evil.com/path");

        resolver.resolve(request);

        assertNull(sessionAttr(request, TenantOAuth2AuthorizationRequestResolver.RETURN_HOST_SESSION_ATTR));
        org.mockito.Mockito.verify(tenantConfigService, org.mockito.Mockito.never())
                .findActiveByCustomDomain(anyString());
    }

    @Test
    void singleLabelReturnHost_isDiscarded() {
        MockHttpServletRequest request = oauthStart();
        request.setParameter("return_host", "localhost");

        resolver.resolve(request);

        assertNull(sessionAttr(request, TenantOAuth2AuthorizationRequestResolver.RETURN_HOST_SESSION_ATTR));
    }

    @Test
    void staleReturnHost_isClearedWhenNewFlowHasInvalidValue() {
        MockHttpServletRequest request = oauthStart();
        request.getSession().setAttribute(
                TenantOAuth2AuthorizationRequestResolver.RETURN_HOST_SESSION_ATTR, "old.example.com");
        request.setParameter("return_host", "not-verified.example.com");

        resolver.resolve(request);

        assertNull(sessionAttr(request, TenantOAuth2AuthorizationRequestResolver.RETURN_HOST_SESSION_ATTR));
    }

    @Test
    void validTenantParam_stillWorks() {
        when(tenantConfigService.findActiveBySubdomain("alice"))
                .thenReturn(Optional.of(activeTenant("alice")));
        MockHttpServletRequest request = oauthStart();
        request.setParameter("tenant", "alice");

        resolver.resolve(request);

        assertEquals("alice", sessionAttr(request, TenantOAuth2AuthorizationRequestResolver.SESSION_ATTR));
        assertNull(sessionAttr(request, TenantOAuth2AuthorizationRequestResolver.RETURN_HOST_SESSION_ATTR));
    }

    @Test
    void validReturnHost_winsOverTenantParam() {
        when(tenantConfigService.findActiveByCustomDomain("shop.example.com"))
                .thenReturn(Optional.of(activeTenant("alice")));
        lenient().when(tenantConfigService.findActiveBySubdomain("bob"))
                .thenReturn(Optional.of(activeTenant("bob")));
        MockHttpServletRequest request = oauthStart();
        request.setParameter("return_host", "shop.example.com");
        request.setParameter("tenant", "bob");

        resolver.resolve(request);

        assertEquals("shop.example.com",
                sessionAttr(request, TenantOAuth2AuthorizationRequestResolver.RETURN_HOST_SESSION_ATTR));
        assertNull(sessionAttr(request, TenantOAuth2AuthorizationRequestResolver.SESSION_ATTR));
    }

    @Test
    void nonAuthorizationRequest_leavesSessionAlone() {
        when(delegate.resolve(any())).thenReturn(null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/whoami");
        request.getSession().setAttribute(
                TenantOAuth2AuthorizationRequestResolver.RETURN_HOST_SESSION_ATTR, "shop.example.com");

        resolver.resolve(request);

        assertEquals("shop.example.com",
                sessionAttr(request, TenantOAuth2AuthorizationRequestResolver.RETURN_HOST_SESSION_ATTR));
    }
}
