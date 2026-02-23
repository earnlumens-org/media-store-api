package org.earnlumens.mediastore.domain.media.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO for a purchased entry — combines entry data with purchase metadata.
 * Returned by the GET /api/purchases endpoint.
 */
public record PurchasedEntryResponse(
        String id,
        String type,
        String title,
        String description,
        String authorName,
        String authorAvatarUrl,
        String publishedAt,
        String thumbnailR2Key,
        String previewR2Key,
        Integer durationSec,
        boolean isPaid,
        BigDecimal priceXlm,
        List<String> tags,
        String purchasedAt
) {}
