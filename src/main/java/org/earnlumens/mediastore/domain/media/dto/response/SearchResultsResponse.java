package org.earnlumens.mediastore.domain.media.dto.response;

import java.util.List;

/**
 * Unified search response — channels (creators) + content (entries/collections),
 * organized the way large platforms present their results.
 *
 * <p>{@code channels} is only populated on the first page of results (page 0).
 * {@code content} carries the paginated, infinitely-scrollable item feed.
 * {@code requiresLogin} is set when an anonymous visitor has exhausted their
 * free search budget and must sign in to keep searching (abuse mitigation).
 */
public record SearchResultsResponse(
        List<SearchChannelResponse> channels,
        PublicFeedPageResponse content,
        boolean requiresLogin
) {

    /**
     * Sentinel response returned when an anonymous visitor must sign in before
     * any further searches are served. No DB query is performed.
     */
    public static SearchResultsResponse loginRequired() {
        return new SearchResultsResponse(
                List.of(),
                new PublicFeedPageResponse(List.of(), 0, 0, 0, 0),
                true
        );
    }
}
