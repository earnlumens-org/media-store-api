package org.earnlumens.mediastore.domain.media.dto.response;

/**
 * Aggregated statistics for the authenticated creator (Creator Studio dashboard).
 * Computed server-side via MongoDB aggregation for accuracy and efficiency.
 */
public record OwnerStatsResponse(
        long totalEntries,
        long published,
        long drafts,
        long inReview,
        long rejected,
        long archived,
        long totalViews
) {}
