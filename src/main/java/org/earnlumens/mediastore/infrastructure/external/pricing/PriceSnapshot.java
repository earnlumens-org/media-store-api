package org.earnlumens.mediastore.infrastructure.external.pricing;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable snapshot of the latest XLM/USD price with audit metadata.
 *
 * @param price      how many USD per 1 XLM
 * @param timestamp  when this price was fetched/computed
 * @param sourceUsed which source(s) provided this price
 * @param mode       how the price was determined (initial, direct, or median recalc)
 */
public record PriceSnapshot(
        BigDecimal price,
        Instant timestamp,
        String sourceUsed,
        PriceUpdateMode mode
) {}
