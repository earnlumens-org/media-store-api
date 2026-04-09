package org.earnlumens.mediastore.domain.media.dto.response;

import java.math.BigDecimal;

/**
 * Unified response item for public feeds (profile, explore, purchased, favorites).
 * Represents either an entry or a collection in a single shape.
 * The {@code kind} discriminator tells the frontend which type it is.
 */
public record PublicFeedItemResponse(
        String id,
        /** "entry" or "collection" */
        String kind,
        /** Entry type (video, audio, image, resource) or collection type — lowercase */
        String type,
        String title,
        String description,
        String authorUsername,
        String authorAvatarUrl,
        /** Badge key (e.g. "u1", "u2") or null */
        String profileBadge,
        String publishedAt,
        String thumbnailR2Key,
        String coverR2Key,
        Integer durationSec,
        long viewCount,
        boolean isPaid,
        BigDecimal priceXlm,
        BigDecimal priceUsd,
        String priceCurrency,
        /** Number of items — only for collections */
        int itemCount,
        boolean locked,
        boolean unlocked
) {}
