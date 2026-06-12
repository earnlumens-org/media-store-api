package org.earnlumens.mediastore.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.earnlumens.mediastore.infrastructure.counter.DistributedCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitFilterTest {

    /**
     * In-memory {@link DistributedCounter} double — behaves like the shared
     * Mongo backend (one counter per scope:key:window across every filter
     * instance) and can be switched into failure mode to exercise the
     * fail-closed path.
     */
    static class FakeCounter implements DistributedCounter {
        final ConcurrentHashMap<String, AtomicLong> counts = new ConcurrentHashMap<>();
        volatile boolean failing = false;

        @Override
        public OptionalLong incrementAndGet(String scope, String key, long windowBucket, Duration retention) {
            if (failing) return OptionalLong.empty();
            return OptionalLong.of(counts
                    .computeIfAbsent(scope + ":" + key + ":" + windowBucket, k -> new AtomicLong())
                    .incrementAndGet());
        }
    }

    private FakeCounter counter;
    private RateLimitFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        counter = new FakeCounter();
        filter = newFilter(counter);
        chain = (req, res) -> {}; // no-op chain
    }

    private static RateLimitFilter newFilter(DistributedCounter counter) {
        return new RateLimitFilter("https://earnlumens.org",
                "https://earnlumens.org,https://app-dev.earnlumens.org,http://localhost:3000",
                counter);
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

        @Test
        void entriesEndpoint_blockedAfter60PerMinute() throws ServletException, IOException {
            String ip = "10.0.0.4";
            for (int i = 0; i < 60; i++) {
                var req = request("/api/entries", ip);
                req.setMethod("POST");
                filter.doFilter(req, new MockHttpServletResponse(), chain);
            }

            var req = request("/api/entries", ip);
            req.setMethod("POST");
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

    @Nested
    class DistributedAuthTier {

        @Test
        void authLimit_isSharedAcrossInstances() throws ServletException, IOException {
            // Two filter instances simulate two Cloud Run instances sharing
            // the same counter backend.
            RateLimitFilter instanceA = newFilter(counter);
            RateLimitFilter instanceB = newFilter(counter);
            String ip = "20.0.0.1";

            for (int i = 0; i < 5; i++) {
                instanceA.doFilter(request("/api/auth/session", ip), new MockHttpServletResponse(), chain);
                instanceB.doFilter(request("/api/auth/session", ip), new MockHttpServletResponse(), chain);
            }

            // 10 requests consumed across both instances → the 11th is blocked
            // no matter which instance serves it.
            var res = new MockHttpServletResponse();
            instanceB.doFilter(request("/api/auth/session", ip), res, chain);
            assertEquals(429, res.getStatus());
        }

        @Test
        void authTier_failsClosed_whenCounterBackendUnavailable() throws ServletException, IOException {
            counter.failing = true;

            var res = new MockHttpServletResponse();
            filter.doFilter(request("/api/auth/session", "20.0.0.2"), res, chain);

            assertEquals(429, res.getStatus());
            assertEquals("60", res.getHeader("Retry-After"));
        }

        @Test
        void nonAuthTiers_unaffectedByCounterBackendFailure() throws ServletException, IOException {
            counter.failing = true;

            var res = new MockHttpServletResponse();
            filter.doFilter(request("/public/entries", "20.0.0.3"), res, chain);

            // Non-auth tiers stay on the in-memory window and keep serving.
            assertNotEquals(429, res.getStatus());
        }

        @Test
        void authWindows_rotateByMinuteBucket() throws ServletException, IOException {
            String ip = "20.0.0.4";
            for (int i = 0; i < 10; i++) {
                filter.doFilter(request("/api/auth/session", ip), new MockHttpServletResponse(), chain);
            }
            var blocked = new MockHttpServletResponse();
            filter.doFilter(request("/api/auth/session", ip), blocked, chain);
            assertEquals(429, blocked.getStatus());

            // A new minute bucket starts a fresh shared counter.
            counter.counts.clear();
            var res = new MockHttpServletResponse();
            filter.doFilter(request("/api/auth/session", ip), res, chain);
            assertNotEquals(429, res.getStatus());
        }
    }
}
