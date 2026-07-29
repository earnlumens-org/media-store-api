package org.earnlumens.mediastore.domain.media.dto.response;

import java.math.BigDecimal;

/**
 * Single favorite item hydrated with entry/collection data for the UI.
 */
public record FavoriteItemResponse(
        String id,
        String itemId,
        String itemType,
        String entryType,
        String title,
        String authorName,
        String authorAvatarUrl,
        /** Badge key (e.g. "u1", "u2") or null */
        String profileBadge,
        String publishedAt,
        String thumbnailUrl,
        String coverUrl,
        Integer durationSec,
        String collectionType,
        Integer itemsCount,
        boolean locked,
        boolean unlocked,
        String addedAt,
        /** R2 prefix for thumbnail variants (entries). */
        String thumbnailVariantsPrefix,
        /** R2 prefix for cover variants (collections). */
        String coverVariantsPrefix,
        /** Price fields (hydrated in the same query) so locked cards can label the price. */
        BigDecimal priceXlm,
        BigDecimal priceUsd,
        /** "XLM" or "USD", null for free items. */
        String priceCurrency
) {}
