package org.earnlumens.mediastore.domain.media.dto.response;

import java.math.BigDecimal;

/**
 * DTO for a purchased collection — combines collection data with purchase metadata.
 */
public record PurchasedCollectionResponse(
        String id,
        String title,
        String description,
        String collectionType,
        String coverR2Key,
        String authorUsername,
        String authorAvatarUrl,
        boolean isPaid,
        BigDecimal priceXlm,
        int itemCount,
        String purchasedAt
) {}
