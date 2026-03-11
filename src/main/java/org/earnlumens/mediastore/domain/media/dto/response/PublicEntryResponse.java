package org.earnlumens.mediastore.domain.media.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Public-facing DTO for a published entry.
 * Used in the Explore page feed and detail views.
 */
public record PublicEntryResponse(
        String id,
        String type,
        String title,
        String description,
        String resourceContent,
        String authorId,
        String authorName,
        String authorAvatarUrl,
        String publishedAt,
        String thumbnailR2Key,
        String previewR2Key,
        Integer durationSec,
        long viewCount,
        boolean isPaid,
        BigDecimal priceXlm,
        BigDecimal priceUsd,
        String priceCurrency,
        String contentLanguage,
        List<String> tags,
        AssetInfo asset
) {}
