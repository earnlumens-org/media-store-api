package org.earnlumens.mediastore.infrastructure.stellar;

import org.junit.jupiter.api.Test;
import org.stellar.sdk.Server;
import org.stellar.sdk.exception.AccountNotFoundException;
import org.stellar.sdk.exception.BadRequestException;
import org.stellar.sdk.exception.BadResponseException;
import org.stellar.sdk.exception.ConnectionErrorException;
import org.stellar.sdk.exception.TooManyRequestsException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link HorizonServerPool}: prioritized cascade (ordered
 * failover) for reads, propagation of definitive answers, cooldown behaviour,
 * submit-node selection and raw-URL cascade.
 */
class HorizonServerPoolTest {

    private static final List<String> THREE_URLS =
            List.of("https://h1.example", "https://h2.example", "https://h3.example");

    /** Pool whose Servers are plain mocks, addressable by URL. */
    private static PoolFixture fixture(List<String> urls) {
        Map<String, Server> servers = new HashMap<>();
        Function<String, Server> factory = url -> servers.computeIfAbsent(url, u -> mock(Server.class));
        HorizonServerPool pool = new HorizonServerPool(urls, factory);
        return new PoolFixture(pool, servers);
    }

    private record PoolFixture(HorizonServerPool pool, Map<String, Server> servers) {
        String urlOf(Server s) {
            return servers.entrySet().stream()
                    .filter(e -> e.getValue() == s)
                    .map(Map.Entry::getKey)
                    .findFirst().orElseThrow();
        }
    }

    // ── construction ─────────────────────────────────────────────

    @Test
    void rejectsEmptyUrlList() {
        assertThrows(IllegalArgumentException.class,
                () -> new HorizonServerPool(List.of(), url -> mock(Server.class)));
    }

    @Test
    void sizeReflectsConfiguredNodes() {
        assertEquals(3, fixture(THREE_URLS).pool().size());
    }

    // ── read cascade ─────────────────────────────────────────────

    @Test
    void executeRead_sticksToPrimaryNodeWhileHealthy() {
        PoolFixture f = fixture(THREE_URLS);
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            f.pool().executeRead("op", s -> seen.add(f.urlOf(s)));
        }
        // Every read hits the first configured node — coherent ledger view
        assertEquals(6, seen.stream().filter("https://h1.example"::equals).count());
    }

    @Test
    void executeRead_failsOverOnRateLimit_andMarksNodeDown() {
        PoolFixture f = fixture(THREE_URLS);
        List<String> attempted = new ArrayList<>();
        String result = f.pool().executeRead("op", s -> {
            attempted.add(f.urlOf(s));
            if (attempted.size() == 1) {
                throw new TooManyRequestsException(null);
            }
            return "ok";
        });
        assertEquals("ok", result);
        // Cascade order: primary first, then the second configured node
        assertEquals(List.of("https://h1.example", "https://h2.example"), attempted);

        // The failed node is now cooling down: subsequent reads land on h2
        for (int i = 0; i < 6; i++) {
            f.pool().executeRead("op", s -> {
                assertEquals("https://h2.example", f.urlOf(s));
                return null;
            });
        }
    }

    @Test
    void executeRead_propagatesDefinitiveAnswers_withoutFailover() {
        PoolFixture f = fixture(THREE_URLS);
        List<String> attempted = new ArrayList<>();
        assertThrows(AccountNotFoundException.class, () -> f.pool().executeRead("op", s -> {
            attempted.add(f.urlOf(s));
            throw new AccountNotFoundException("GABC");
        }));
        assertEquals(1, attempted.size(), "definitive answer must not fail over");

        attempted.clear();
        assertThrows(BadRequestException.class, () -> f.pool().executeRead("op", s -> {
            attempted.add(f.urlOf(s));
            throw new BadRequestException(400, "bad request", null, null);
        }));
        assertEquals(1, attempted.size());
    }

    @Test
    void executeRead_whenAllNodesFail_rethrowsLastFailure() {
        PoolFixture f = fixture(THREE_URLS);
        List<String> attempted = new ArrayList<>();
        assertThrows(BadResponseException.class, () -> f.pool().executeRead("op", s -> {
            attempted.add(f.urlOf(s));
            throw new BadResponseException(503, "unavailable", null, null);
        }));
        assertEquals(3, attempted.size(), "every node should be tried once");
    }

    @Test
    void executeRead_whenAllNodesCoolingDown_stillTriesThem() {
        PoolFixture f = fixture(THREE_URLS);
        // Put every node in cooldown
        for (String url : THREE_URLS) {
            f.pool().markUrlDown(url, "test");
        }
        // Pool must degrade to using cooling-down nodes rather than blackout
        String result = f.pool().executeRead("op", s -> "ok");
        assertEquals("ok", result);
    }

    // ── node-failure classification ──────────────────────────────

    @Test
    void isNodeFailure_classifiesTransportErrorsOnly() {
        assertTrue(HorizonServerPool.isNodeFailure(new TooManyRequestsException(null)));
        assertTrue(HorizonServerPool.isNodeFailure(new BadResponseException(502, "bad gateway", null, null)));
        assertTrue(HorizonServerPool.isNodeFailure(new ConnectionErrorException(new IOException("refused"))));
        assertFalse(HorizonServerPool.isNodeFailure(new BadRequestException(400, "bad", null, null)));
        assertFalse(HorizonServerPool.isNodeFailure(new AccountNotFoundException("GABC")));
        assertFalse(HorizonServerPool.isNodeFailure(new IllegalStateException("app bug")));
    }

    // ── submit node selection ────────────────────────────────────

    @Test
    void submitServer_prefersFirstConfiguredNode() {
        PoolFixture f = fixture(THREE_URLS);
        assertEquals("https://h1.example", f.urlOf(f.pool().submitServer()));
        // Deterministic: repeated calls stay on the first healthy node
        assertEquals("https://h1.example", f.urlOf(f.pool().submitServer()));
    }

    @Test
    void submitServer_rotatesAwayAfterReportedFailure_andReturnsAfterCooldownWindow() {
        PoolFixture f = fixture(THREE_URLS);
        Server first = f.pool().submitServer();
        f.pool().reportSubmitFailure(first, new IOException("timeout"));
        assertEquals("https://h2.example", f.urlOf(f.pool().submitServer()));
    }

    @Test
    void submitServer_whenAllDown_fallsBackToFirst() {
        PoolFixture f = fixture(THREE_URLS);
        for (String url : THREE_URLS) {
            f.pool().markUrlDown(url, "test");
        }
        assertEquals("https://h1.example", f.urlOf(f.pool().submitServer()));
    }

    // ── raw-URL cascade (SDEX orderbook) ────────────────────

    @Test
    void nextReadUrl_prefersPrimary_andSkipsDownNodes() {
        PoolFixture f = fixture(THREE_URLS);
        assertEquals("https://h1.example", f.pool().nextReadUrl());
        f.pool().markUrlDown("https://h1.example", "test");
        for (int i = 0; i < 6; i++) {
            assertEquals("https://h2.example", f.pool().nextReadUrl());
        }
    }

    @Test
    void markUrlDown_unknownUrl_isIgnored() {
        PoolFixture f = fixture(THREE_URLS);
        assertDoesNotThrow(() -> f.pool().markUrlDown("https://unknown.example", "test"));
    }
}
