package org.earnlumens.mediastore.domain.media.dto.response;

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
        String publishedAt,
        String thumbnailUrl,
        String coverUrl,
        Integer durationSec,
        String collectionType,
        Integer itemsCount,
        boolean locked,
        boolean unlocked,
        String addedAt
) {}
