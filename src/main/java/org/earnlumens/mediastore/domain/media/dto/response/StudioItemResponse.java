package org.earnlumens.mediastore.domain.media.dto.response;

import java.math.BigDecimal;

/**
 * Unified response for Creator Studio: represents either an entry or a collection.
 * The {@code kind} discriminator tells the frontend which type it is.
 */
public record StudioItemResponse(
        String id,
        /** "entry" or "collection" */
        String kind,
        /** Entry type (video, audio, image, resource) or collection type (CATALOG, ALBUM, etc.) — lowercase */
        String type,
        String title,
        String description,
        String status,
        String thumbnailR2Key,
        String coverR2Key,
        boolean isPaid,
        BigDecimal priceXlm,
        BigDecimal priceUsd,
        String priceCurrency,
        String contentLanguage,
        Integer durationSec,
        long viewCount,
        /** Number of items — only for collections */
        int itemCount,
        String createdAt,
        String updatedAt,
        String publishedAt,
        /** Transcoding status — only for video entries */
        String transcodingStatus,
        String sellerWallet,
        /** Human-readable moderation feedback (rejection/suspension reason). Null if none. */
        String moderationFeedback
) {}
