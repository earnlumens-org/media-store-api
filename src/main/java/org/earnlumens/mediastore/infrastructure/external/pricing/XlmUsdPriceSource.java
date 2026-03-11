package org.earnlumens.mediastore.infrastructure.external.pricing;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Contract for an external XLM/USD price source.
 * Each implementation talks to one exchange/aggregator API.
 */
public interface XlmUsdPriceSource {

    /** Human-readable source name (e.g. "coinbase", "kraken", "coingecko"). */
    String name();

    /**
     * Fetch the current XLM/USD price.
     *
     * @return the price, or empty if the source is unavailable or returned invalid data
     */
    Optional<BigDecimal> fetchPrice();
}
