package org.earnlumens.mediastore.domain.media.dto.response;

import java.util.List;

/**
 * Paginated response for owner entries (Creator Studio).
 */
public record OwnerEntryPageResponse(
        List<OwnerEntryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
