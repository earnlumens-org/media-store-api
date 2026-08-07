package org.earnlumens.mediastore.domain.media.dto.response;

import java.util.List;

/**
 * Paginated response for unified public feeds (entries + collections merged).
 *
 * <p>{@code languageFallback} is {@code true} when the requested content
 * language filter matched nothing and the server automatically re-ran the
 * query without it ("no content in your languages yet — showing all
 * languages"). The UI surfaces an explanatory banner and paginates the
 * unfiltered feed ({@code lang=all}) for subsequent pages.</p>
 */
public record PublicFeedPageResponse(
        List<PublicFeedItemResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean languageFallback
) {
    /** Convenience constructor for feeds without language fallback. */
    public PublicFeedPageResponse(List<PublicFeedItemResponse> content, int page, int size,
                                  long totalElements, int totalPages) {
        this(content, page, size, totalElements, totalPages, false);
    }
}
