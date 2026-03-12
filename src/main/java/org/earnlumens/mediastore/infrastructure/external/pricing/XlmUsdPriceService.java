package org.earnlumens.mediastore.infrastructure.external.pricing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe, in-memory cached XLM/USD price resolver.
 * <p>
 * <b>Cache strategy:</b>
 * <ul>
 *   <li>If {@code currentPrice} exists and is younger than 60 s → return without API call.</li>
 *   <li>On first load → query all 3 sources and use the <b>median</b>.</li>
 *   <li>On refresh → query the next source in rotation (1→2→3→1).</li>
 *   <li>If the new price differs &lt; 3% from the cached price → <b>direct update</b>.</li>
 *   <li>If ≥ 3% → query the other 2 sources and use the <b>median</b> of all valid prices.</li>
 *   <li>If median recalculation has &lt; 2 valid prices → <b>keep the old price</b>.</li>
 * </ul>
 * <p>
 * <b>Thread safety:</b> A {@link ReentrantLock} ensures only one thread refreshes at a time.
 * Other threads either return the stale (but valid) cached price or block during initial load.
 * The cached snapshot reference is {@code volatile} for safe publication.
 */
@Service
public class XlmUsdPriceService {

    private static final Logger logger = LoggerFactory.getLogger(XlmUsdPriceService.class);
    private static final BigDecimal SPIKE_THRESHOLD = new BigDecimal("0.03"); // 3%

    private final List<XlmUsdPriceSource> sources;
    private final SdexXlmPriceSource sdexSource;
    private final Duration cacheTtl;
    private final AtomicInteger rotationIndex = new AtomicInteger(0);
    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile PriceSnapshot currentSnapshot;

    /** Production constructor — injected by Spring with 60 s cache TTL. */
    @Autowired
    public XlmUsdPriceService(List<XlmUsdPriceSource> sources, SdexXlmPriceSource sdexSource) {
        this(sources, sdexSource, Duration.ofSeconds(60));
    }

    /** Package-private constructor for tests — allows custom cache TTL. */
    XlmUsdPriceService(List<XlmUsdPriceSource> sources, Duration cacheTtl) {
        this(sources, null, cacheTtl);
    }

    /** Package-private constructor for tests — allows custom cache TTL and optional SDEX source. */
    XlmUsdPriceService(List<XlmUsdPriceSource> sources, SdexXlmPriceSource sdexSource, Duration cacheTtl) {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("At least one price source is required");
        }
        this.sources = List.copyOf(sources);
        this.sdexSource = sdexSource;
        this.cacheTtl = cacheTtl;
    }

    // ─── Public API ────────────────────────────────────────────

    /**
     * Returns the current XLM/USD price, refreshing from external sources if the cache is stale.
     *
     * @return a non-null {@link PriceSnapshot} with the latest price
     * @throws IllegalStateException if no price is available (all sources failed on initial load)
     */
    public PriceSnapshot getPrice() {
        PriceSnapshot snapshot = currentSnapshot;
        if (snapshot != null && !isExpired(snapshot)) {
            return snapshot;
        }
        return refresh();
    }

    /** Peek at the current snapshot — may be null if never loaded. */
    public PriceSnapshot getCurrentSnapshot() {
        return currentSnapshot;
    }

    // ─── Refresh logic ─────────────────────────────────────────

    private boolean isExpired(PriceSnapshot snapshot) {
        return Duration.between(snapshot.timestamp(), Instant.now()).compareTo(cacheTtl) > 0;
    }

    private PriceSnapshot refresh() {
        // Fast path: try to acquire the lock without blocking.
        // If another thread is already refreshing, return the stale snapshot (if any).
        if (!refreshLock.tryLock()) {
            PriceSnapshot stale = currentSnapshot;
            if (stale != null) {
                return stale; // stale but non-null — acceptable during concurrent refresh
            }
            // First load — must block until the refreshing thread finishes.
            refreshLock.lock();
            try {
                PriceSnapshot loaded = currentSnapshot;
                if (loaded != null) return loaded;
                throw new IllegalStateException("XLM/USD price unavailable after waiting for initial load");
            } finally {
                refreshLock.unlock();
            }
        }

        // We hold the lock — double-check cache freshness (another thread may have refreshed while we waited).
        try {
            PriceSnapshot snapshot = currentSnapshot;
            if (snapshot != null && !isExpired(snapshot)) {
                return snapshot;
            }

            if (snapshot == null) {
                return performInitialLoad();
            } else {
                return performIncrementalUpdate(snapshot);
            }
        } finally {
            refreshLock.unlock();
        }
    }

    // ─── Initial load ──────────────────────────────────────────

    private PriceSnapshot performInitialLoad() {
        // ── Try SDEX first ──
        Optional<BigDecimal> sdexPrice = fetchSdex();
        if (sdexPrice.isPresent()) {
            PriceSnapshot snapshot = new PriceSnapshot(
                    sdexPrice.get(), Instant.now(), "sdex", PriceUpdateMode.INITIAL_LOAD);
            currentSnapshot = snapshot;
            logger.info("XLM/USD initial load from SDEX: price={}", sdexPrice.get().toPlainString());
            return snapshot;
        }

        // ── SDEX unavailable — fall back to CEX median ──
        logger.info("XLM/USD SDEX unavailable on initial load, falling back to {} CEX sources", sources.size());

        List<BigDecimal> prices = fetchAllSources();
        List<BigDecimal> valid = prices.stream().filter(Objects::nonNull).toList();

        if (valid.isEmpty()) {
            throw new IllegalStateException(
                    "XLM/USD price unavailable: all " + sources.size() + " sources failed on initial load");
        }

        BigDecimal price;
        String sourcesDesc;
        if (valid.size() >= 2) {
            price = median(valid);
            sourcesDesc = "median(" + valid.size() + " sources)";
        } else {
            price = valid.getFirst();
            sourcesDesc = "single-source-fallback";
        }

        PriceSnapshot newSnapshot = new PriceSnapshot(price, Instant.now(), sourcesDesc, PriceUpdateMode.INITIAL_LOAD);
        currentSnapshot = newSnapshot;
        logger.info("XLM/USD initial load complete: price={}, source={}", price.toPlainString(), sourcesDesc);
        return newSnapshot;
    }

    // ─── Incremental update ────────────────────────────────────

    private PriceSnapshot performIncrementalUpdate(PriceSnapshot previous) {
        // ── Try SDEX first ──
        Optional<BigDecimal> sdexPrice = fetchSdex();
        if (sdexPrice.isPresent()) {
            PriceSnapshot updated = new PriceSnapshot(
                    sdexPrice.get(), Instant.now(), "sdex", PriceUpdateMode.DIRECT_UPDATE);
            currentSnapshot = updated;
            logger.info("XLM/USD update from SDEX: price={}", sdexPrice.get().toPlainString());
            return updated;
        }

        // ── SDEX unavailable — fall back to CEX round-robin ──
        logger.info("XLM/USD SDEX unavailable, falling back to CEX rotation");

        // Pick the next source in round-robin rotation
        int idx = rotationIndex.getAndUpdate(i -> (i + 1) % sources.size());
        XlmUsdPriceSource primarySource = sources.get(idx);

        Optional<BigDecimal> fetched = safeFetch(primarySource);
        if (fetched.isEmpty()) {
            logger.warn("XLM/USD {} — source '{}' failed, keeping previous price {}",
                    Instant.now(), primarySource.name(), previous.price().toPlainString());
            return previous;
        }

        BigDecimal newPrice = fetched.get();
        BigDecimal diffRatio = newPrice.subtract(previous.price()).abs()
                .divide(previous.price(), 6, RoundingMode.HALF_UP);

        // ── Small diff (< 3%): direct update ──
        if (diffRatio.compareTo(SPIKE_THRESHOLD) < 0) {
            PriceSnapshot updated = new PriceSnapshot(
                    newPrice, Instant.now(), primarySource.name(), PriceUpdateMode.DIRECT_UPDATE);
            currentSnapshot = updated;
            logger.info("XLM/USD direct update: price={}, source={}, diff={}%",
                    newPrice.toPlainString(), primarySource.name(),
                    diffRatio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP));
            return updated;
        }

        // ── Spike (≥ 3%): median recalculation from all sources ──
        logger.info("XLM/USD spike detected: diff={}% from {} (source: {}), triggering median recalculation",
                diffRatio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP),
                previous.price().toPlainString(), primarySource.name());

        // We already have the primary source's price; query the other two.
        List<BigDecimal> allPrices = new ArrayList<>();
        allPrices.add(newPrice);
        for (int i = 0; i < sources.size(); i++) {
            if (i != idx) {
                safeFetch(sources.get(i)).ifPresent(allPrices::add);
            }
        }

        if (allPrices.size() >= 2) {
            BigDecimal medianPrice = median(allPrices);
            PriceSnapshot updated = new PriceSnapshot(
                    medianPrice, Instant.now(),
                    "median(" + allPrices.size() + " sources)",
                    PriceUpdateMode.MEDIAN_RECALCULATION);
            currentSnapshot = updated;
            logger.info("XLM/USD median recalculation: price={}, validSources={}",
                    medianPrice.toPlainString(), allPrices.size());
            return updated;
        } else {
            logger.warn("XLM/USD median recalculation failed: only {} valid price(s), keeping previous {}",
                    allPrices.size(), previous.price().toPlainString());
            return previous;
        }
    }

    // ─── Helpers ───────────────────────────────────────────────

    private Optional<BigDecimal> fetchSdex() {
        if (sdexSource == null) return Optional.empty();
        try {
            return sdexSource.fetchPrice();
        } catch (Exception e) {
            logger.error("[sdex] {} — unexpected error: {}", Instant.now(), e.getMessage(), e);
            return Optional.empty();
        }
    }

    private List<BigDecimal> fetchAllSources() {
        List<BigDecimal> prices = new ArrayList<>();
        for (XlmUsdPriceSource source : sources) {
            prices.add(safeFetch(source).orElse(null));
        }
        return prices;
    }

    private Optional<BigDecimal> safeFetch(XlmUsdPriceSource source) {
        try {
            return source.fetchPrice();
        } catch (Exception e) {
            logger.error("[{}] {} — unexpected error fetching price: {}",
                    source.name(), Instant.now(), e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Computes the median of a list of positive {@link BigDecimal} values.
     * <ul>
     *   <li>Odd count → middle element</li>
     *   <li>Even count → average of the two middle elements</li>
     * </ul>
     * Package-private for direct unit testing.
     */
    static BigDecimal median(List<BigDecimal> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Cannot compute median of empty list");
        }
        List<BigDecimal> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        } else {
            return sorted.get(n / 2 - 1).add(sorted.get(n / 2))
                    .divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);
        }
    }
}
