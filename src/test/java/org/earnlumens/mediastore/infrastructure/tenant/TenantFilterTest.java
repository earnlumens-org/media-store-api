package org.earnlumens.mediastore.infrastructure.tenant;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenantFilter}: tenant context lifecycle, the 404
 * short-circuit for unresolvable hosts (H1 action 2 / custom-domain-upgrade
 * 3.1) and the read-only mode of a BLOCKED tenant (H1: reads and session
 * plumbing pass, every operation is refused with 423 before any controller).
 */
class TenantFilterTest {

    private final TenantResolver resolver = mock(TenantResolver.class);
    private final TenantFilter filter = new TenantFilter(resolver);

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void resolvedTenant_setsContextAndContinuesChain() throws Exception {
        when(resolver.resolveTenant(any())).thenReturn(TenantResolver.ResolvedTenant.active("alice"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/whoami");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(any(), any());
        assertEquals(200, response.getStatus());
        // Context must be cleared after the request completes.
        assertNull(TenantContext.get());
        assertFalse(TenantContext.isSuspended());
    }

    @Test
    void unresolvableHost_returns404AndSkipsChain() throws Exception {
        when(resolver.resolveTenant(any())).thenReturn(null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/whoami");
        request.setServerName("unknown-domain.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(404, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertEquals("{\"error\":\"tenant_not_found\"}", response.getContentAsString());
        assertNull(TenantContext.get());
    }

    // ── H1: read-only mode for a BLOCKED tenant ──────────────────────────

    private static final String[] SUSPENDED_ALLOWED = {
            "GET /public/tenant/visitor",
            "GET /public/entries/abc",
            "GET /public/collections/abc",
            "GET /api/purchases",
            "GET /api/purchases/feed",
            "GET /api/media/entitlements/abc",
            "GET /api/favorites",
            "GET /oauth2/authorization/x",
            "GET /login/oauth2/code/x",
            "POST /api/auth/session",
            "POST /api/auth/refresh",
            "POST /api/auth/logout",
            "HEAD /public/entries/abc",
            "OPTIONS /api/payments/prepare",
            "POST /api/internal/transcoding/complete",
    };

    private static final String[] SUSPENDED_REFUSED = {
            "POST /api/payments/prepare",
            "POST /api/payments/submit",
            "POST /api/payments/tip/prepare",
            "POST /api/uploads/init",
            "POST /api/uploads/finalize",
            "POST /api/entries",
            "PUT /api/entries/abc",
            "PATCH /api/entries/abc/status",
            "DELETE /api/collections/abc",
            "POST /api/collections",
            "POST /api/favorites/abc",
            "POST /api/ratings",
            "POST /api/reports",
            "POST /api/franchises",
            "POST /api/resellers/links",
            "POST /api/plan/orders/prepare",
            "POST /api/publishing/queue",
    };

    static Stream<String> suspendedAllowed() { return Stream.of(SUSPENDED_ALLOWED); }
    static Stream<String> suspendedRefused() { return Stream.of(SUSPENDED_REFUSED); }

    @ParameterizedTest
    @MethodSource("suspendedAllowed")
    void suspendedTenant_allowsReadsAndSession(String methodAndPath) throws Exception {
        String[] parts = methodAndPath.split(" ", 2);
        when(resolver.resolveTenant(any())).thenReturn(new TenantResolver.ResolvedTenant("alice", true));
        MockHttpServletRequest request = new MockHttpServletRequest(parts[0], parts[1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(any(), any());
        assertEquals(200, response.getStatus(), methodAndPath);
        assertNull(TenantContext.get());
        assertFalse(TenantContext.isSuspended(), "flag must not leak past the request");
    }

    @ParameterizedTest
    @MethodSource("suspendedRefused")
    void suspendedTenant_refusesEveryOperationWith423(String methodAndPath) throws Exception {
        String[] parts = methodAndPath.split(" ", 2);
        when(resolver.resolveTenant(any())).thenReturn(new TenantResolver.ResolvedTenant("alice", true));
        MockHttpServletRequest request = new MockHttpServletRequest(parts[0], parts[1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(423, response.getStatus(), methodAndPath);
        assertEquals("{\"error\":\"TENANT_SUSPENDED\"}", response.getContentAsString());
        assertNull(TenantContext.get());
        assertFalse(TenantContext.isSuspended());
    }

    @Test
    void suspendedTenant_contextFlagIsVisibleDownstream() throws Exception {
        when(resolver.resolveTenant(any())).thenReturn(new TenantResolver.ResolvedTenant("alice", true));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/purchases");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] seen = new boolean[2];
        FilterChain chain = (req, res) -> {
            seen[0] = "alice".equals(TenantContext.get());
            seen[1] = TenantContext.isSuspended();
        };

        filter.doFilter(request, response, chain);

        assertTrue(seen[0]);
        assertTrue(seen[1]);
    }

    @Test
    void activeTenant_operationsGoThrough() throws Exception {
        when(resolver.resolveTenant(any())).thenReturn(TenantResolver.ResolvedTenant.active("alice"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/payments/prepare");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(any(), any());
        assertEquals(200, response.getStatus());
    }
}
