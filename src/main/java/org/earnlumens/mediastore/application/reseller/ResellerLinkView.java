package org.earnlumens.mediastore.application.reseller;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A reseller link joined with the live state of its entry (title, status, and
 * current commission percent). The commission is read live, so a creator
 * changing it is reflected here immediately.
 */
public record ResellerLinkView(
        String id,
        String entryId,
        String entryTitle,
        String entryType,
        String code,
        String resellerWallet,
        /** False when the creator has disabled resells for this entry ("Resale disabled"). */
        boolean resellerEnabled,
        BigDecimal commissionPercent,
        BigDecimal priceXlm,
        BigDecimal priceUsd,
        String priceCurrency,
        Instant createdAt
) {}
