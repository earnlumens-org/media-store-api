package org.earnlumens.mediastore.web.reseller.dto;

import org.earnlumens.mediastore.application.reseller.ResellerEntryInfo;

import java.math.BigDecimal;

/** Whether/how an entry can be resold, plus its current price. */
public record ResellerEntryInfoResponse(
        String entryId,
        String entryTitle,
        String entryType,
        boolean resellerEnabled,
        BigDecimal commissionPercent,
        BigDecimal priceXlm,
        BigDecimal priceUsd,
        String priceCurrency,
        boolean ownContent
) {
    public static ResellerEntryInfoResponse of(ResellerEntryInfo info) {
        return new ResellerEntryInfoResponse(
                info.entryId(),
                info.entryTitle(),
                info.entryType(),
                info.resellerEnabled(),
                info.commissionPercent(),
                info.priceXlm(),
                info.priceUsd(),
                info.priceCurrency(),
                info.ownContent());
    }
}
