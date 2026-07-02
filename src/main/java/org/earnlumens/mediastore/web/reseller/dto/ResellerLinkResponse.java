package org.earnlumens.mediastore.web.reseller.dto;

import org.earnlumens.mediastore.application.reseller.ResellerLinkView;

import java.math.BigDecimal;

/** A reseller link plus the live state of its entry. */
public record ResellerLinkResponse(
        String id,
        String entryId,
        String entryTitle,
        String entryType,
        String code,
        String resellerWallet,
        boolean resellerEnabled,
        BigDecimal commissionPercent,
        BigDecimal priceXlm,
        BigDecimal priceUsd,
        String priceCurrency,
        String createdAt
) {
    public static ResellerLinkResponse of(ResellerLinkView view) {
        return new ResellerLinkResponse(
                view.id(),
                view.entryId(),
                view.entryTitle(),
                view.entryType(),
                view.code(),
                view.resellerWallet(),
                view.resellerEnabled(),
                view.commissionPercent(),
                view.priceXlm(),
                view.priceUsd(),
                view.priceCurrency(),
                view.createdAt() != null ? view.createdAt().toString() : null);
    }
}
