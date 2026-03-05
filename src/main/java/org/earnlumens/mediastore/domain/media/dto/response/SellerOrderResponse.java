package org.earnlumens.mediastore.domain.media.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Response DTO for a single completed sale visible to the seller.
 * Includes the payment breakdown (splits) and the Stellar transaction hash
 * so the seller can verify the on-chain settlement.
 */
public record SellerOrderResponse(
        String orderId,
        String entryId,
        String entryTitle,
        String entryType,
        BigDecimal amountXlm,
        String stellarTxHash,
        LocalDateTime completedAt,
        List<SplitDetail> splits
) {
    public record SplitDetail(
            String wallet,
            String role,
            BigDecimal percent,
            BigDecimal amountXlm
    ) {}
}
