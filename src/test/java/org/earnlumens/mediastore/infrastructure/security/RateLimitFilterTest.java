package org.earnlumens.mediastore.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitFilterTest {

    private RateLimitFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        chain = (req, res) -> {}; // no-op chain
    }

    private MockHttpServletRequest request(String path, String ip) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setRemoteAddr(ip);
        return req;
    }

    @Nested
    class AllowsNormalTraffic {

        @Test
        void singleRequest_isAllowed() throws ServletException, IOException {
            var req = request("/api/auth/session", "1.2.3.4");
            var res = new MockHttpServletResponse();

            filter.doFilter(req, res, chain);

            assertNotEquals(429, res.getStatus());
            assertEquals("10", res.getHeader("X-RateLimit-Limit"));
            assertEquals("9", res.getHeader("X-RateLimit-Remaining"));
        }

        @Test
        void differentIps_haveIndependentLimits() throws ServletException, IOException {
            for (int i = 0; i < 8; i++) {
                var req = request("/api/auth/session", "1.1.1.1");
                filter.doFilter(req, new MockHttpServletResponse(), chain);
            }

            // Different IP should still be allowed
            var req = request("/api/auth/session", "2.2.2.2");
            var res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);
            assertNotEquals(429, res.getStatus());
        }

        @Test
        void differentTiers_haveIndependentLimits() throws ServletException, IOException {
            // Exhaust auth tier
            for (int i = 0; i < 10; i++) {
                var req = request("/api/auth/session", "3.3.3.3");
                filter.doFilter(req, new MockHttpServletResponse(), chain);
            }

            // Upload tier should still be allowed for same IP
            var req = request("/api/uploads/init", "3.3.3.3");
            var res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);
            assertNotEquals(429, res.getStatus());
        }
    }

    @Nested
    class BlocksAbuse {

        @Test
        void authEndpoint_blockedAfter10PerMinute() throws ServletException, IOException {
            String ip = "10.0.0.1";
            for (int i = 0; i < 10; i++) {
                var req = request("/api/auth/session", ip);
                var res = new MockHttpServletResponse();
                filter.doFilter(req, res, chain);
                assertNotEquals(429, res.getStatus(), "Request " + (i + 1) + " should pass");
            }

            // 11th request should be blocked
            var req = request("/api/auth/session", ip);
            var res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);
            assertEquals(429, res.getStatus());
            assertNotNull(res.getHeader("Retry-After"));
            assertEquals("0", res.getHeader("X-RateLimit-Remaining"));
        }

        @Test
        void waitlistEndpoint_blockedAfter10PerMinute() throws ServletException, IOException {
            String ip = "10.0.0.2";
            for (int i = 0; i < 10; i++) {
                var req = request("/api/waitlist/subscribe", ip);
                filter.doFilter(req, new MockHttpServletResponse(), chain);
            }

            var req = request("/api/waitlist/subscribe", ip);
            var res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);
            assertEquals(429, res.getStatus());
        }

        @Test
        void uploadEndpoint_blockedAfter30PerMinute() throws ServletException, IOException {
            String ip = "10.0.0.3";
            for (int i = 0; i < 30; i++) {
                var req = request("/api/uploads/init", ip);
                filter.doFilter(req, new MockHttpServletResponse(), chain);
            }

            var req = request("/api/uploads/init", ip);
            var res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);
            assertEquals(429, res.getStatus());
        }
    }

    @Nested
    class IpResolution {

        @Test
        void prefersCfConnectingIp() throws ServletException, IOException {
            var req = request("/api/auth/session", "127.0.0.1");
            req.addHeader("CF-Connecting-IP", "99.99.99.99");
            req.addHeader("X-Forwarded-For", "88.88.88.88");

            var res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);

            // First request from this CF IP → should be allowed
            assertNotEquals(429, res.getStatus());
            assertEquals("9", res.getHeader("X-RateLimit-Remaining"));
        }

        @Test
        void fallsBackToXForwardedFor() throws ServletException, IOException {
            var req = request("/api/auth/session", "127.0.0.1");
            req.addHeader("X-Forwarded-For", "77.77.77.77, 66.66.66.66");

            var res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);

            assertNotEquals(429, res.getStatus());
        }
    }

    @Nested
    class ResponseHeaders {

        @Test
        void includesRateLimitHeaders() throws ServletException, IOException {
            var req = request("/public/something", "5.5.5.5");
            var res = new MockHttpServletResponse();

            filter.doFilter(req, res, chain);

            assertEquals("200", res.getHeader("X-RateLimit-Limit"));
            assertEquals("199", res.getHeader("X-RateLimit-Remaining"));
        }

        @Test
        void blockedResponse_includesRetryAfter() throws ServletException, IOException {
            String ip = "6.6.6.6";
            for (int i = 0; i < 11; i++) {
                var req = request("/api/waitlist/test", ip);
                filter.doFilter(req, new MockHttpServletResponse(), chain);
            }

            var req = request("/api/waitlist/test", ip);
            var res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);
            assertEquals(429, res.getStatus());
            assertEquals("60", res.getHeader("Retry-After"));
            assertTrue(res.getContentAsString().contains("Too Many Requests"));
        }
    }
}
