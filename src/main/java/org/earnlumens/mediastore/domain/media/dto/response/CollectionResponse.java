package org.earnlumens.mediastore.domain.media.dto.response;

import java.math.BigDecimal;

/**
 * Public-facing DTO for a collection.
 * Used in the collection feed/detail views.
 */
public record CollectionResponse(
        String id,
        String title,
        String description,
        String collectionType,
        String coverR2Key,
        String status,
        String visibility,
        String authorUsername,
        String authorAvatarUrl,
        String publishedAt,
        boolean isPaid,
        BigDecimal priceXlm,
        BigDecimal priceUsd,
        String priceCurrency,
        int itemCount,
        boolean locked,
        boolean unlocked
) {}
