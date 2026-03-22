package org.earnlumens.mediastore.domain.media.dto.response;

import java.util.List;

public record PurchasedCollectionPageResponse(
        List<PurchasedCollectionResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
