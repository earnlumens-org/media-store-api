package org.earnlumens.mediastore.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EdgeAuthFilter} (custom-domain-upgrade Fase 0.4/0.7).
 * Plain servlet mocks — no Spring context.
 */
class EdgeAuthFilterTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private MockHttpServletRequest request(String uri, String edgeAuth) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        if (edgeAuth != null) {
            req.addHeader(EdgeAuthFilter.EDGE_AUTH_HEADER, edgeAuth);
        }
        return req;
    }

    private MockHttpServletResponse run(EdgeAuthFilter filter, MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    void disabled_passesThroughWithoutHeader() throws Exception {
        EdgeAuthFilter filter = new EdgeAuthFilter(false, "");
        MockHttpServletResponse res = run(filter, request("/api/whoami", null));
        assertEquals(200, res.getStatus());
    }

    @Test
    void enabled_validHeader_passesThrough() throws Exception {
        EdgeAuthFilter filter = new EdgeAuthFilter(true, SECRET);
        MockHttpServletResponse res = run(filter, request("/api/whoami", SECRET));
        assertEquals(200, res.getStatus());
    }

    @Test
    void enabled_missingHeader_returns404() throws Exception {
        EdgeAuthFilter filter = new EdgeAuthFilter(true, SECRET);
        MockHttpServletResponse res = run(filter, request("/api/whoami", null));
        assertEquals(404, res.getStatus());
    }

    @Test
    void enabled_wrongHeader_returns404() throws Exception {
        EdgeAuthFilter filter = new EdgeAuthFilter(true, SECRET);
        MockHttpServletResponse res = run(filter, request("/api/whoami", "wrong-secret"));
        assertEquals(404, res.getStatus());
    }

    @Test
    void enabled_publicPath_requiresHeaderToo() throws Exception {
        EdgeAuthFilter filter = new EdgeAuthFilter(true, SECRET);
        assertEquals(404, run(filter, request("/public/feed", null)).getStatus());
        assertEquals(200, run(filter, request("/public/feed", SECRET)).getStatus());
    }

    @Test
    void enabled_exemptPaths_passWithoutHeader() throws Exception {
        EdgeAuthFilter filter = new EdgeAuthFilter(true, SECRET);
        assertEquals(200, run(filter, request("/actuator/health", null)).getStatus());
        assertEquals(200, run(filter, request("/actuator/health/liveness", null)).getStatus());
        assertEquals(200, run(filter, request("/api/internal/cleanup", null)).getStatus());
        assertEquals(200, run(filter, request("/api/internal/transcoding/complete", null)).getStatus());
    }

    @Test
    void exemptMatching_isExactPrefix_notSubstring() {
        assertTrue(EdgeAuthFilter.isExempt("/api/internal/cleanup"));
        assertFalse(EdgeAuthFilter.isExempt("/api/internals"));
        assertFalse(EdgeAuthFilter.isExempt("/actuator/healthcheck"));
        assertFalse(EdgeAuthFilter.isExempt("/api/media/entitlements/x"));
        assertFalse(EdgeAuthFilter.isExempt(null));
    }

    @Test
    void enabledWithoutSecret_failsAtConstruction() {
        assertThrows(IllegalStateException.class, () -> new EdgeAuthFilter(true, ""));
        assertThrows(IllegalStateException.class, () -> new EdgeAuthFilter(true, "  "));
    }
}
