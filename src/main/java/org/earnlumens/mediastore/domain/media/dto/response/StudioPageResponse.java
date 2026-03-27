package org.earnlumens.mediastore.domain.media.dto.response;

import java.util.List;

/**
 * Paginated response for the unified Creator Studio feed (entries + collections).
 */
public record StudioPageResponse(
        List<StudioItemResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
