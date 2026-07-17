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
        BigDecimal priceUsd,
        String priceCurrency,
        String contentLanguage,
        Integer durationSec,
        long viewCount,
        String createdAt,
        String updatedAt,
        String publishedAt,
        /** Transcoding job status for VIDEO entries: PENDING, DISPATCHED, PROCESSING, COMPLETED, FAILED, DEAD. Null for non-video. */
        String transcodingStatus,
        /** Stellar public key of the seller wallet for paid content. Null for free content. */
        String sellerWallet,
        /** Human-readable moderation feedback (rejection/suspension reason). Null if none. */
        String moderationFeedback,
        /** R2 prefix for thumbnail variants. */
        String thumbnailVariantsPrefix,
        /** R2 prefix for preview-image variants. */
        String previewVariantsPrefix,
        /** Whether resellers may earn a commission distributing this entry. */
        boolean resellerEnabled,
        /** Reseller commission as a percent of the total price (5–20). */
        BigDecimal resellerCommissionPercent,
        /** Original First: true when this entry was detected as a duplicate of another user's upload. */
        boolean remix,
        /** Original First royalty on remix sales (5–50). */
        BigDecimal remixRoyaltyPercent
) {}
