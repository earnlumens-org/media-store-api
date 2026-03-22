package org.earnlumens.mediastore.domain.media.dto.response;

import java.util.List;

public record CollectionPageResponse(
        List<CollectionResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
