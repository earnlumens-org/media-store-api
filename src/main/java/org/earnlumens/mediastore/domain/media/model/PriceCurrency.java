package org.earnlumens.mediastore.domain.media.model;

/**
 * The currency in which a creator originally set the entry price.
 * <ul>
 *   <li><b>XLM</b> — price was set in Stellar Lumens (native currency, long-term default)</li>
 *   <li><b>USD</b> — price was set in US Dollars (converted to XLM at purchase time)</li>
 * </ul>
 */
public enum PriceCurrency {
    XLM,
    USD
}
