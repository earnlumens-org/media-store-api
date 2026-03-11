package org.earnlumens.mediastore.infrastructure.external.pricing;

/**
 * Describes how a cached XLM/USD price was determined.
 */
public enum PriceUpdateMode {
    /** First-ever load: median of all available sources */
    INITIAL_LOAD,
    /** Single-source refresh with less than 3% deviation from previous price */
    DIRECT_UPDATE,
    /** Spike detected (≥ 3% deviation): median recalculated from all available sources */
    MEDIAN_RECALCULATION
}
