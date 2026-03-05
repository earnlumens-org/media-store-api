package org.earnlumens.mediastore.domain.media.dto.response;

import java.math.BigDecimal;

/**
 * DTO for entries returned to the authenticated owner (Creator Studio).
 * Includes status, createdAt, updatedAt — fields not exposed in the public DTO.
 */
public record OwnerEntryResponse(
        String id,
        String type,
        String title,
        String description,
        String status,
        String thumbnailR2Key,
        String previewR2Key,
        boolean isPaid,
        BigDecimal priceXlm,
        Integer durationSec,
        long viewCount,
        String createdAt,
        String updatedAt,
        String publishedAt
) {}
