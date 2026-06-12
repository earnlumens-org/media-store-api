package org.earnlumens.mediastore.infrastructure.security;

import org.earnlumens.mediastore.infrastructure.counter.DistributedCounter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AnonymousSearchBudget} over a shared
 * {@link DistributedCounter}: budget enforcement across instances,
 * per-IP independence, Cloudflare-aware IP resolution and the
 * fail-open contract when the counter backend is unavailable.
 */
class AnonymousSearchBudgetTest {

    private static final int BUDGET = 25;

    /** Shared in-memory counter double, same semantics as the Mongo backend. */
    static class FakeCounter implements DistributedCounter {
        final ConcurrentHashMap<String, AtomicLong> counts = new ConcurrentHashMap<>();
        volatile boolean failing = false;
        volatile String lastScope;
        volatile Duration lastRetention;

        @Override
        public OptionalLong incrementAndGet(String scope, String key, long windowBucket, Duration retention) {
            lastScope = scope;
            lastRetention = retention;
            if (failing) return OptionalLong.empty();
            return OptionalLong.of(counts
                    .computeIfAbsent(scope + ":" + key + ":" + windowBucket, k -> new AtomicLong())
                    .incrementAndGet());
        }
    }

    private static AnonymousSearchBudget budget(DistributedCounter counter) {
        return new AnonymousSearchBudget(BUDGET, 60, counter);
    }

    private static MockHttpServletRequest request(String ip) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/public/search");
        req.setRemoteAddr(ip);
        return req;
    }

    @Test
    void allowsUpToBudget_thenBlocks() {
        var counter = new FakeCounter();
        var budget = budget(counter);

        for (int i = 0; i < BUDGET; i++) {
            assertTrue(budget.tryConsume(request("1.1.1.1")), "search " + (i + 1) + " should be free");
        }
        assertFalse(budget.tryConsume(request("1.1.1.1")), "search " + (BUDGET + 1) + " requires login");
    }

    @Test
    void budgetIsShared_acrossInstances() {
        // Two budget instances simulate two Cloud Run instances sharing
        // the same counter backend.
        var counter = new FakeCounter();
        var instanceA = budget(counter);
        var instanceB = budget(counter);

        for (int i = 0; i < BUDGET; i++) {
            var consumer = (i % 2 == 0) ? instanceA : instanceB;
            assertTrue(consumer.tryConsume(request("2.2.2.2")));
        }
        // Exhausted across both instances — neither lets the next one through.
        assertFalse(instanceA.tryConsume(request("2.2.2.2")));
        assertFalse(instanceB.tryConsume(request("2.2.2.2")));
    }

    @Test
    void differentIps_haveIndependentBudgets() {
        var counter = new FakeCounter();
        var budget = budget(counter);

        for (int i = 0; i < BUDGET + 1; i++) {
            budget.tryConsume(request("3.3.3.3"));
        }
        assertFalse(budget.tryConsume(request("3.3.3.3")));
        assertTrue(budget.tryConsume(request("4.4.4.4")));
    }

    @Test
    void failsOpen_whenCounterBackendUnavailable() {
        var counter = new FakeCounter();
        counter.failing = true;
        var budget = budget(counter);

        assertTrue(budget.tryConsume(request("5.5.5.5")),
                "a counter outage must never block a visitor's search");
    }

    @Test
    void usesSearchScope_andRetentionCoveringTheWindow() {
        var counter = new FakeCounter();
        var budget = budget(counter);

        budget.tryConsume(request("6.6.6.6"));

        assertEquals("search", counter.lastScope);
        assertTrue(counter.lastRetention.compareTo(Duration.ofMinutes(60)) >= 0,
                "retention must be at least the window length");
    }

    @Test
    void prefersCfConnectingIp_overRemoteAddr() {
        var counter = new FakeCounter();
        var budget = budget(counter);

        // Exhaust the budget for the CF-resolved IP…
        for (int i = 0; i < BUDGET; i++) {
            var req = request("127.0.0.1");
            req.addHeader("CF-Connecting-IP", "99.99.99.99");
            assertTrue(budget.tryConsume(req));
        }
        var blocked = request("127.0.0.1");
        blocked.addHeader("CF-Connecting-IP", "99.99.99.99");
        assertFalse(budget.tryConsume(blocked));

        // …while a different CF IP behind the same proxy address is unaffected.
        var other = request("127.0.0.1");
        other.addHeader("CF-Connecting-IP", "88.88.88.88");
        assertTrue(budget.tryConsume(other));
    }
}
