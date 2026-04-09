package org.earnlumens.mediastore.domain.media.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Collection detail with hydrated entry items and per-entry access status.
 */
public record CollectionDetailResponse(
        String id,
        String title,
        String description,
        String collectionType,
        String coverR2Key,
        String status,
        String visibility,
        String authorUsername,
        String authorAvatarUrl,
        /** Badge key (e.g. "u1", "u2") or null */
        String profileBadge,
        String publishedAt,
        boolean isPaid,
        BigDecimal priceXlm,
        BigDecimal priceUsd,
        String priceCurrency,
        int itemCount,
        boolean locked,
        boolean unlocked,
        boolean isOwner,
        String contentLanguage,
        List<CollectionEntryItem> items
) {
    public record CollectionEntryItem(
            String entryId,
            int position,
            String type,
            String title,
            String description,
            String authorUsername,
            String thumbnailR2Key,
            Integer durationSec,
            boolean isPaid,
            BigDecimal priceXlm,
            boolean locked,
            boolean unlocked
    ) {}
}
