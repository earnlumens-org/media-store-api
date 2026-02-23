package org.earnlumens.mediastore.domain.media.dto.response;

import java.util.List;

/**
 * Paginated response for purchased entries.
 */
public record PurchasedEntryPageResponse(
        List<PurchasedEntryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
