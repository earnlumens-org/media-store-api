package org.earnlumens.mediastore.application.reseller;

import java.math.BigDecimal;

/**
 * Read model describing whether/how a given entry can be resold, plus the
 * current price so the "EARN LUMENS" modal can show an orientative commission.
 */
public record ResellerEntryInfo(
        String entryId,
        String entryTitle,
        String entryType,
        boolean resellerEnabled,
        BigDecimal commissionPercent,
        BigDecimal priceXlm,
        BigDecimal priceUsd,
        String priceCurrency,
        /** True when the caller is the content creator (cannot resell own content). */
        boolean ownContent
) {}
