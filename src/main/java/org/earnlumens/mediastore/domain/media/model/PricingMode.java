package org.earnlumens.mediastore.domain.media.model;

/**
 * How an entry can be purchased.
 * <ul>
 *   <li><b>INDIVIDUAL</b> — standalone purchase only (default, current behavior)</li>
 *   <li><b>COLLECTION_ONLY</b> — only accessible by buying a collection that contains it</li>
 *   <li><b>BOTH</b> — purchasable individually or via a collection</li>
 * </ul>
 */
public enum PricingMode {
    INDIVIDUAL,
    COLLECTION_ONLY,
    BOTH
}
