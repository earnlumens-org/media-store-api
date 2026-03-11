package org.earnlumens.mediastore.infrastructure.external.pricing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Exhaustive unit tests for {@link XlmUsdPriceService}.
 * <p>
 * Covers: initial load, cache TTL, incremental updates, spike detection (3% threshold),
 * median recalculation, source rotation, source failure/exception, boundary cases,
 * thread safety, and constructor validation.
 */
class XlmUsdPriceServiceTest {

    private XlmUsdPriceSource source1;
    private XlmUsdPriceSource source2;
    private XlmUsdPriceSource source3;

    @BeforeEach
    void setUp() {
        source1 = mock(XlmUsdPriceSource.class);
        source2 = mock(XlmUsdPriceSource.class);
        source3 = mock(XlmUsdPriceSource.class);
        when(source1.name()).thenReturn("source1");
        when(source2.name()).thenReturn("source2");
        when(source3.name()).thenReturn("source3");
    }

    private XlmUsdPriceService createService(Duration cacheTtl) {
        return new XlmUsdPriceService(List.of(source1, source2, source3), cacheTtl);
    }

    // ═══ Median utility ════════════════════════════════════════

    @Test
    @DisplayName("median: 3 values → returns middle")
    void median_threeValues_returnsMiddle() {
        assertEquals(bd("0.12"),
                XlmUsdPriceService.median(List.of(bd("0.10"), bd("0.12"), bd("0.15"))));
    }

    @Test
    @DisplayName("median: 2 values → returns average")
    void median_twoValues_returnsAverage() {
        BigDecimal result = XlmUsdPriceService.median(List.of(bd("0.10"), bd("0.20")));
        assertEquals(0, bd("0.15").compareTo(result));
    }

    @Test
    @DisplayName("median: single value → returns it")
    void median_singleValue_returnsIt() {
        assertEquals(bd("0.12"), XlmUsdPriceService.median(List.of(bd("0.12"))));
    }

    @Test
    @DisplayName("median: unsorted input → still correct")
    void median_unsortedInput_returnsCorrectMedian() {
        assertEquals(bd("0.12"),
                XlmUsdPriceService.median(List.of(bd("0.15"), bd("0.10"), bd("0.12"))));
    }

    @Test
    @DisplayName("median: empty list → throws")
    void median_emptyList_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> XlmUsdPriceService.median(List.of()));
    }

    // ═══ Initial Load ══════════════════════════════════════════

    @Test
    @DisplayName("initial load: all 3 sources succeed → median")
    void initialLoad_allSourcesSucceed_returnsMedian() {
        when(source1.fetchPrice()).thenReturn(Optional.of(bd("0.10")));
        when(source2.fetchPrice()).thenReturn(Optional.of(bd("0.12")));
        when(source3.fetchPrice()).thenReturn(Optional.of(bd("0.15")));

        XlmUsdPriceService service = createService(Duration.ofHours(1));
        PriceSnapshot snapshot = service.getPrice();

        assertEquals(bd("0.12"), snapshot.price());
        assertEquals(PriceUpdateMode.INITIAL_LOAD, snapshot.mode());
        assertNotNull(snapshot.timestamp());
        assertTrue(snapshot.sourceUsed().contains("median"));
    }

    @Test
    @DisplayName("initial load: 2 of 3 succeed → median of 2 (= average)")
    void initialLoad_twoSucceed_returnsMedianOfTwo() {
        when(source1.fetchPrice()).thenReturn(Optional.of(bd("0.10")));
        when(source2.fetchPrice()).thenReturn(Optional.empty());
        when(source3.fetchPrice()).thenReturn(Optional.of(bd("0.14")));

        XlmUsdPriceService service = createService(Duration.ofHours(1));
        PriceSnapshot snapshot = service.getPrice();

        // median of [0.10, 0.14] = average = 0.12
        assertEquals(0, bd("0.12").compareTo(snapshot.price()));
        assertEquals(PriceUpdateMode.INITIAL_LOAD, snapshot.mode());
    }

    @Test
    @DisplayName("initial load: 1 of 3 succeeds → single price fallback")
    void initialLoad_oneSucceeds_returnsSinglePrice() {
        when(source1.fetchPrice()).thenReturn(Optional.empty());
        when(source2.fetchPrice()).thenReturn(Optional.of(bd("0.12")));
        when(source3.fetchPrice()).thenReturn(Optional.empty());

        XlmUsdPriceService service = createService(Duration.ofHours(1));
        PriceSnapshot snapshot = service.getPrice();

        assertEquals(bd("0.12"), snapshot.price());
        assertEquals(PriceUpdateMode.INITIAL_LOAD, snapshot.mode());
        assertTrue(snapshot.sourceUsed().contains("single"));
    }

    @Test
    @DisplayName("initial load: all 3 fail → throws IllegalStateException")
    void initialLoad_allFail_throwsException() {
        when(source1.fetchPrice()).thenReturn(Optional.empty());
        when(source2.fetchPrice()).thenReturn(Optional.empty());
        when(source3.fetchPrice()).thenReturn(Optional.empty());

        XlmUsdPriceService service = createService(Duration.ofHours(1));

        IllegalStateException ex = assertThrows(IllegalStateException.class, service::getPrice);
        assertTrue(ex.getMessage().contains("all"));
    }

    @Test
    @DisplayName("initial load: one source throws → other sources still used")
    void initialLoad_oneSourceThrows_othersStillUsed() {
        when(source1.fetchPrice()).thenThrow(new RuntimeException("network error"));
        when(source2.fetchPrice()).thenReturn(Optional.of(bd("0.12")));
        when(source3.fetchPrice()).thenReturn(Optional.of(bd("0.14")));

        XlmUsdPriceService service = createService(Duration.ofHours(1));
        PriceSnapshot snapshot = service.getPrice();

        // median of [0.12, 0.14] = 0.13
        assertEquals(0, bd("0.13").compareTo(snapshot.price()));
    }

    // ═══ Cache TTL ═════════════════════════════════════════════

    @Test
    @DisplayName("cache hit: second call within TTL returns same object, no extra API calls")
    void getPrice_withinTtl_returnsCachedPrice() {
        when(source1.fetchPrice()).thenReturn(Optional.of(bd("0.10")));
        when(source2.fetchPrice()).thenReturn(Optional.of(bd("0.12")));
        when(source3.fetchPrice()).thenReturn(Optional.of(bd("0.15")));

        XlmUsdPriceService service = createService(Duration.ofHours(1));

        PriceSnapshot first = service.getPrice();
        PriceSnapshot second = service.getPrice();
        PriceSnapshot third = service.getPrice();

        assertSame(first, second);
        assertSame(second, third);
        // Each source called exactly once (initial load only)
        verify(source1, times(1)).fetchPrice();
        verify(source2, times(1)).fetchPrice();
        verify(source3, times(1)).fetchPrice();
    }

    @Test
    @DisplayName("cache expired: triggers refresh from rotated source")
    void getPrice_cacheExpired_refreshes() throws InterruptedException {
        when(source1.fetchPrice()).thenReturn(Optional.of(bd("0.10")));
        when(source2.fetchPrice()).thenReturn(Optional.of(bd("0.12")));
        when(source3.fetchPrice()).thenReturn(Optional.of(bd("0.15")));

        XlmUsdPriceService service = createService(Duration.ofMillis(50));

        PriceSnapshot first = service.getPrice();
        Thread.sleep(100);

        // After TTL, source1 returns slightly different price (< 3% diff)
        when(source1.fetchPrice()).thenReturn(Optional.of(bd("0.121")));

        PriceSnapshot second = service.getPrice();

        assertNotSame(first, second);
        verify(source1, atLeast(2)).fetchPrice();
    }

    // ═══ Incremental Update — Direct (< 3% diff) ══════════════

    @Test
    @DisplayName("incremental: < 3% diff → direct update from single source")
    void incrementalUpdate_smallDiff_directUpdate() throws InterruptedException {
        when(source1.fetchPrice()).thenReturn(Optional.of(bd("0.1000")));
        when(source2.fetchPrice()).thenReturn(Optional.of(bd("0.1000")));
        when(source3.fetchPrice()).thenReturn(Optional.of(bd("0.1000")));

        XlmUsdPriceService service = createService(Duration.ofMillis(50));
        service.getPrice(); // initial load → price = 0.1000

        Thread.sleep(100);

        // 1% diff — well below 3%
        when(source1.fetchPrice()).thenReturn(Optional.of(bd("0.1010")));

        PriceSnapshot updated = service.getPrice();

        assertEquals(bd("0.1010"), updated.price());
        assertEquals(PriceUpdateMode.DIRECT_UPDATE, updated.mode());
        assertEquals("source1", updated.sourceUsed());
    }

    // ═══ Spike Detection — Median Recalculation (>= 3%) ═══════

    @Test
    @DisplayName("spike: >= 3% diff → median recalculation from all 3 sources")
    void incrementalUpdate_largeDiff_medianRecalc() throws InterruptedException {
        when(source1.fetchPrice()).thenReturn(Optional.of(bd("0.1000")));
        when(source2.fetchPrice()).thenReturn(Optional.of(bd("0.1000")));
        when(source3.fetchPrice()).thenReturn(Optional.of(bd("0.1000")));

        XlmUsdPriceService service = createService(Duration.ofMillis(50));
        service.getPrice();

        Thread.sleep(100);

        // 5% diff → spike → queries all 3 for median
        when(source1.fetchPrice()).thenReturn(Optional.of(bd("0.1050")));
        when(source2.fetchPrice()).thenReturn(Optional.of(bd("0.1040")));
        when(source3.fetchPrice()).thenReturn(Optional.of(bd("0.1060")));

        PriceSnapshot updated = service.getPrice();

        assertEquals(bd("0.1050"), updated.price()); // median of [0.1040, 0.1050, 0.1060]
        assertEquals(PriceUpdateMode.MEDIAN_RECALCULATION, updated.mode());
    }

    @Test
    @DisplayName("spike: only 2 valid sources → median of 2 (= average)")
    void incrementalUpdate_spike_twoValid_usesMedianOfTwo() throws InterruptedException {
        when(source1.fetchPrice()).thenReturn(Optional.of(bd("0.1000")));
        when(source2.fetchPrice()).thenReturn(Optional.of(bd("0.1000")));
        when(source3.fetchPrice()).thenReturn(Optional.of(bd("0.1000")));

        XlmUsdPriceService service = createService(Duration.ofMillis(50));
        service.getPrice();

        Thread.sleep(100);

        // Spike from source1, source3 fails → 2 valid prices
        when(source1.fetchPrice()).thenReturn(Optional.of(bd("0.1050")));
        when(source2.fetchPrice()).thenReturn(Optional.of(bd("0.1040")));
        when(source3.fetchPrice()).thenReturn(Optional.empty());

        PriceSnapshot updated = service.getPrice();

        // median of [0.1040, 0.1050] = average = 0.1045
        assertEquals(0, bd("0.1045").compareTo(updated.price()));
        assertEquals(PriceUpdateMode.MEDIAN_RECALCULATION, updated.mode());
    }

    @Test
    @DisplayName("spike: only 1 valid (both others fail) → keeps old price")
    void incrementalUpdate_spike_onlyOneValid_keepsOldPrice() throws InterruptedException {
        when(source1.fetchPrice()).thenReturn(Optional.of(bd("0.1000")));
        when(source2.fetchPrice()).thenReturn(Optional.of(bd("0.1000")));
        when(source3.fetchPrice()).thenReturn(Optional.of(bd("0.1000")));

        XlmUsdPriceService service = createService(Duration.ofMillis(50));
        PriceSnapshot initial = service.getPrice();

        Thread.sleep(100);

        // Spike from source1 (50%!), both others fail → < 2 valid → keep old
        when(source1.fetchPrice()).thenReturn(Optional.of(bd("0.1500")));
        when(source2.fetchPrice()).thenReturn(Optional.empty());
        when(source3.fetchPrice()).thenReturn(Optional.empty());

        PriceSnapshot updated = service.getPrice();

        assertEquals(initial.price(), updated.price());
        assertEquals(PriceUpdateMode.INITIAL_LOAD, updated.mode()); // still the original snapshot
    }

    // ═══ Source Failure ════════════════════════════════════════

    @Test
    @DisplayName("incremental: primary source returns empty → keeps previous price")
    void incrementalUpdate_sourceFails_keepsPrevious() throws InterruptedException {
        when(source1.fetchPrice()).thenReturn(Optional.of(bd("0.1000")));
        when(source2.fetchPrice()).thenReturn(Optional.of(bd("0.1000")));
        when(source3.fetchPrice()).thenReturn(Optional.of(bd("0.1000")));

        XlmUsdPriceService service = createService(Duration.ofMillis(50));
        PriceSnapshot initial = service.getPrice();

        Thread.sleep(100);

        when(source1.fetchPrice()).thenReturn(Optional.empty());

        PriceSnapshot updated = service.getPrice();

        assertEquals(initial.price(), updated.price());
    }

    @Test
    @DisplayName("incremental: primary source throws exception → keeps previous price")
    void incrementalUpdate_sourceThrows_keepsPrevious() throws InterruptedException {
        when(source1.fetchPrice()).thenReturn(Optional.of(bd("0.1000")));
        when(source2.fetchPrice()).thenReturn(Optional.of(bd("0.1000")));
        when(source3.fetchPrice()).thenReturn(Optional.of(bd("0.1000")));

        XlmUsdPriceService service = createService(Duration.ofMillis(50));
        PriceSnapshot initial = service.getPrice();

        Thread.sleep(100);

        when(source1.fetchPrice()).thenThrow(new RuntimeException("connection refused"));

        PriceSnapshot updated = service.getPrice();

        assertEquals(initial.price(), updated.price());
    }

    // ═══ Source Rotation ═══════════════════════════════════════

    @Test
    @DisplayName("source rotation: cycles 1→2→3→1 on successive refreshes")
    void sourceRotation_cyclesCorrectly() throws InterruptedException {
        when(source1.fetchPrice()).thenReturn(Optional.of(bd("0.1000")));
        when(source2.fetchPrice()).thenReturn(Optional.of(bd("0.1001")));
        when(source3.fetchPrice()).thenReturn(Optional.of(bd("0.1002")));

        XlmUsdPriceService service = createService(Duration.ofMillis(1));
        service.getPrice(); // initial load (fetchAllSources, does NOT use rotation)

        clearInvocations(source1, source2, source3);
        when(source1.fetchPrice()).thenReturn(Optional.of(bd("0.1000")));
        when(source2.fetchPrice()).thenReturn(Optional.of(bd("0.1001")));
        when(source3.fetchPrice()).thenReturn(Optional.of(bd("0.1002")));

        Thread.sleep(10);
        PriceSnapshot r1 = service.getPrice();
        assertEquals("source1", r1.sourceUsed());

        Thread.sleep(10);
        PriceSnapshot r2 = service.getPrice();
        assertEquals("source2", r2.sourceUsed());

        Thread.sleep(10);
        PriceSnapshot r3 = service.getPrice();
        assertEquals("source3", r3.sourceUsed());

        Thread.sleep(10);
        PriceSnapshot r4 = service.getPrice();
        assertEquals("source1", r4.sourceUsed()); // wrapped around
    }

    // ═══ Boundary Cases ═══════════════════════════════════════

    @Test
    @DisplayName("exactly 3.0% diff → triggers median recalculation (>= threshold)")
    void incrementalUpdate_exactly3Percent_triggersMedian() throws InterruptedException {
        when(source1.fetchPrice()).thenReturn(Optional.of(bd("1.000000")));
        when(source2.fetchPrice()).thenReturn(Optional.of(bd("1.000000")));
        when(source3.fetchPrice()).thenReturn(Optional.of(bd("1.000000")));

        XlmUsdPriceService service = createService(Duration.ofMillis(50));
        service.getPrice();

        Thread.sleep(100);

        // Exactly 3% diff → >= threshold → median
        when(source1.fetchPrice()).thenReturn(Optional.of(bd("1.030000")));
        when(source2.fetchPrice()).thenReturn(Optional.of(bd("1.030000")));
        when(source3.fetchPrice()).thenReturn(Optional.of(bd("1.030000")));

        PriceSnapshot updated = service.getPrice();

        assertEquals(PriceUpdateMode.MEDIAN_RECALCULATION, updated.mode());
    }

    @Test
    @DisplayName("2.99% diff → direct update (< threshold)")
    void incrementalUpdate_justBelow3Percent_directUpdate() throws InterruptedException {
        when(source1.fetchPrice()).thenReturn(Optional.of(bd("1.000000")));
        when(source2.fetchPrice()).thenReturn(Optional.of(bd("1.000000")));
        when(source3.fetchPrice()).thenReturn(Optional.of(bd("1.000000")));

        XlmUsdPriceService service = createService(Duration.ofMillis(50));
        service.getPrice();

        Thread.sleep(100);

        // 2.99% diff → < threshold → direct
        when(source1.fetchPrice()).thenReturn(Optional.of(bd("1.029900")));

        PriceSnapshot updated = service.getPrice();

        assertEquals(PriceUpdateMode.DIRECT_UPDATE, updated.mode());
        assertEquals(bd("1.029900"), updated.price());
    }

    // ═══ Thread Safety ════════════════════════════════════════

    @Test
    @DisplayName("concurrent initial load: only one thread fetches, all threads get same price")
    void concurrentInitialLoad_onlyOneFetches() throws Exception {
        when(source1.fetchPrice()).thenAnswer(inv -> {
            Thread.sleep(100); // simulate slow API
            return Optional.of(bd("0.10"));
        });
        when(source2.fetchPrice()).thenReturn(Optional.of(bd("0.12")));
        when(source3.fetchPrice()).thenReturn(Optional.of(bd("0.11")));

        XlmUsdPriceService service = createService(Duration.ofHours(1));

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        java.util.List<Future<PriceSnapshot>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                startGate.await();
                return service.getPrice();
            }));
        }

        startGate.countDown(); // release all threads simultaneously

        BigDecimal firstPrice = null;
        for (Future<PriceSnapshot> f : futures) {
            PriceSnapshot snap = f.get(5, TimeUnit.SECONDS);
            assertNotNull(snap);
            assertNotNull(snap.price());
            if (firstPrice == null) {
                firstPrice = snap.price();
            } else {
                assertEquals(firstPrice, snap.price());
            }
        }

        executor.shutdown();
        // source1 called exactly once — only the winning thread performed the initial load
        verify(source1, times(1)).fetchPrice();
    }

    @Test
    @DisplayName("concurrent cache-hit reads: no contention, all return instantly")
    void concurrentCacheHit_noLockContention() throws Exception {
        when(source1.fetchPrice()).thenReturn(Optional.of(bd("0.10")));
        when(source2.fetchPrice()).thenReturn(Optional.of(bd("0.12")));
        when(source3.fetchPrice()).thenReturn(Optional.of(bd("0.11")));

        XlmUsdPriceService service = createService(Duration.ofHours(1));
        PriceSnapshot expected = service.getPrice(); // warm up cache

        int threads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        java.util.List<Future<PriceSnapshot>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                startGate.await();
                return service.getPrice();
            }));
        }

        startGate.countDown();

        for (Future<PriceSnapshot> f : futures) {
            PriceSnapshot snap = f.get(2, TimeUnit.SECONDS);
            assertSame(expected, snap); // exact same object — cache hit, no refresh
        }

        executor.shutdown();
        // No additional API calls after initial load
        verify(source1, times(1)).fetchPrice();
    }

    // ═══ Constructor Validation ═══════════════════════════════

    @Test
    @DisplayName("constructor: empty sources list → throws")
    void constructor_emptySources_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new XlmUsdPriceService(List.of(), Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("constructor: null sources → throws")
    void constructor_nullSources_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new XlmUsdPriceService(null, Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("getCurrentSnapshot: null before first getPrice() call")
    void getCurrentSnapshot_initiallyNull() {
        when(source1.fetchPrice()).thenReturn(Optional.of(bd("0.10")));
        XlmUsdPriceService service = createService(Duration.ofHours(1));
        assertNull(service.getCurrentSnapshot());
    }

    @Test
    @DisplayName("getCurrentSnapshot: populated after getPrice()")
    void getCurrentSnapshot_populatedAfterGetPrice() {
        when(source1.fetchPrice()).thenReturn(Optional.of(bd("0.10")));
        when(source2.fetchPrice()).thenReturn(Optional.of(bd("0.12")));
        when(source3.fetchPrice()).thenReturn(Optional.of(bd("0.11")));

        XlmUsdPriceService service = createService(Duration.ofHours(1));
        service.getPrice();

        assertNotNull(service.getCurrentSnapshot());
        assertEquals(bd("0.11"), service.getCurrentSnapshot().price()); // median of [0.10, 0.11, 0.12]
    }

    // ═══ Helpers ══════════════════════════════════════════════

    private static BigDecimal bd(String val) {
        return new BigDecimal(val);
    }
}
