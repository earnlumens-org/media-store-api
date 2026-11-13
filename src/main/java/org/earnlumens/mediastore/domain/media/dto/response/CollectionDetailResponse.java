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
        List<CollectionEntryItem> items,
        /** R2 prefix for cover variants. */
        String coverVariantsPrefix,
        /**
         * Items the creator removed after this buyer's purchase, which the
         * buyer retains (H2 / D-20). Empty for non-buyers.
         */
        List<CollectionEntryItem> retainedItems
) {
    public record CollectionEntryItem(
            String entryId,
            /** 0-based position among active items; {@code -1} for a retained (removed) item. */
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
            boolean unlocked,
            /** R2 prefix for thumbnail variants of this entry. */
            String thumbnailVariantsPrefix,
            /** Entry is under a moderation block (H3): not accessible to anyone until reinstated. */
            boolean blocked,
            /** Removed from the collection after the viewer's purchase; the viewer keeps access (D-20). */
            boolean retained
    ) {}
}
