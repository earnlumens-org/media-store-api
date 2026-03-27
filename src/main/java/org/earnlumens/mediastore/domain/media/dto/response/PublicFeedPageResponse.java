package org.earnlumens.mediastore.domain.media.dto.response;

import java.util.List;

/**
 * Paginated response for unified public feeds (entries + collections merged).
 */
public record PublicFeedPageResponse(
        List<PublicFeedItemResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
