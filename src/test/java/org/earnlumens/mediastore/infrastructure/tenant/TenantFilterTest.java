package org.earnlumens.mediastore.infrastructure.tenant;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenantFilter}: tenant context lifecycle and the 404
 * short-circuit for unresolvable hosts (custom-domain-upgrade 3.1 — an unknown
 * custom domain must never execute downstream code, let alone against the
 * default tenant).
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
        when(resolver.resolve(any())).thenReturn("alice");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/whoami");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(any(), any());
        assertEquals(200, response.getStatus());
        // Context must be cleared after the request completes.
        assertNull(TenantContext.get());
    }

    @Test
    void unresolvableHost_returns404AndSkipsChain() throws Exception {
        when(resolver.resolve(any())).thenReturn(null);
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
}
