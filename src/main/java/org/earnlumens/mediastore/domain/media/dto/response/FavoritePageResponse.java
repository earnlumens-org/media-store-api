package org.earnlumens.mediastore.domain.media.dto.response;

import java.util.List;

/**
 * Paginated list of hydrated favorite items.
 */
public record FavoritePageResponse(
        List<FavoriteItemResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
