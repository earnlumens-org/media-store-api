package org.earnlumens.mediastore.domain.media.dto.response;

import java.util.List;

/**
 * Paginated response for public entries.
 * Matches the frontend FeedPageDto contract.
 */
public record PublicEntryPageResponse(
        List<PublicEntryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
