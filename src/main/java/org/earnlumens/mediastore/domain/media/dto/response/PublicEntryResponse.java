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
        /** Badge key (e.g. "u1", "u2") or null */
        String profileBadge,
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
        AssetInfo asset,
        /** True when HLS transcoding is complete and the adaptive stream is available. */
        boolean hlsReady,
        /**
         * R2 prefix containing pre-generated thumbnail variants (320.webp, 640.webp,
         * 1280.webp). Null when the worker has not run, was skipped, or failed —
         * UI must fall back to {@code thumbnailR2Key}.
         */
        String thumbnailVariantsPrefix,
        /** R2 prefix for preview-image variants. Same convention as {@link #thumbnailVariantsPrefix}. */
        String previewVariantsPrefix,
        /** Whether resellers may earn a commission distributing this (paid) entry. */
        boolean resellerEnabled,
        /** Current reseller commission as a percent of the total price (5–20). Live value. */
        BigDecimal resellerCommissionPercent,
        /** Original First: true when this entry duplicates another user's earlier upload. */
        boolean remix,
        /** Canonical original entry id (only when {@link #remix} is true). */
        String originalEntryId,
        /** Username of the original creator (only when {@link #remix} is true). */
        String originalAuthorUsername,
        /** Royalty percent the original creator earns on remix sales of THIS entry (5–50). */
        BigDecimal remixRoyaltyPercent
) {}
