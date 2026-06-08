package org.earnlumens.mediastore.domain.media.dto.response;

import java.util.List;

/** Paginated reviews for an entry plus the aggregate summary. */
public record RatingListResponse(
        RatingAggregateResponse aggregate,
        List<RatingResponse> items,
        int page,
        int size,
        long total,
        boolean hasMore
) {}
