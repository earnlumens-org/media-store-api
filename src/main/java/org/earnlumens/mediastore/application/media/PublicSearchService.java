package org.earnlumens.mediastore.application.media;

import org.bson.Document;
import org.earnlumens.mediastore.domain.media.dto.response.PublicFeedPageResponse;
import org.earnlumens.mediastore.domain.media.dto.response.SearchChannelResponse;
import org.earnlumens.mediastore.domain.media.dto.response.SearchResultsResponse;
import org.earnlumens.mediastore.domain.media.dto.response.SearchSuggestionsResponse;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the unified, tenant-scoped search experience: creator channels +
 * content (entries/collections), plus lightweight autocomplete suggestions.
 *
 * <p>All queries are bounded — capped query length, capped page size, capped
 * page index and a fixed channel/suggestion count — so a single request can
 * never ask the database for an unbounded amount of work. This is the
 * application-layer half of the abuse story; IP rate limiting and the anonymous
 * budget live in the infrastructure/security layer.
 */
@Service
public class PublicSearchService {

    /** Defensive caps so no single query can be turned into a DB stress test. */
    private static final int MAX_QUERY_LENGTH = 80;
    private static final int MAX_PAGE = 50;
    private static final int MAX_SIZE = 48;
    private static final int CHANNEL_LIMIT = 6;
    private static final int SUGGESTION_LIMIT = 8;
    private static final int MAX_SUGGESTION_QUERY_LENGTH = 60;

    private final EntryRepository entryRepository;
    private final PublicEntryService publicEntryService;

    public PublicSearchService(EntryRepository entryRepository, PublicEntryService publicEntryService) {
        this.entryRepository = entryRepository;
        this.publicEntryService = publicEntryService;
    }

    /**
     * Runs a unified search. Channels are only included on the first page so
     * subsequent infinite-scroll pages stay lean.
     */
    public SearchResultsResponse search(String tenantId, String rawQuery, String type, String sort,
                                        int page, int size) {
        String query = normalizeQuery(rawQuery);
        int safePage = clamp(page, 0, MAX_PAGE);
        int safeSize = clamp(size, 1, MAX_SIZE);

        if (query.isBlank()) {
            return new SearchResultsResponse(
                    List.of(),
                    new PublicFeedPageResponse(List.of(), safePage, safeSize, 0, 0),
                    false);
        }

        PublicFeedPageResponse content = publicEntryService.searchContent(
                tenantId, query, type, sort, safePage, safeSize);

        // Channels only on the first page; collection-only filters never match channels.
        List<SearchChannelResponse> channels = (safePage == 0 && !"collection".equalsIgnoreCase(type))
                ? findChannels(tenantId, query)
                : List.of();

        return new SearchResultsResponse(channels, content, false);
    }

    /**
     * Autocomplete suggestions for the search box.
     */
    public SearchSuggestionsResponse suggestions(String tenantId, String rawQuery) {
        String query = normalizeQuery(rawQuery);
        if (query.isBlank() || query.length() > MAX_SUGGESTION_QUERY_LENGTH) {
            return new SearchSuggestionsResponse(List.of());
        }
        List<String> suggestions = entryRepository.searchSuggestions(tenantId, query, SUGGESTION_LIMIT);
        return new SearchSuggestionsResponse(suggestions);
    }

    private List<SearchChannelResponse> findChannels(String tenantId, String query) {
        List<Document> docs = entryRepository.searchChannels(tenantId, query, CHANNEL_LIMIT);
        List<SearchChannelResponse> channels = new ArrayList<>(docs.size());
        for (Document doc : docs) {
            String username = doc.getString("_id");
            if (username == null || username.isBlank()) {
                continue;
            }
            long count = doc.get("contentCount") instanceof Number n ? n.longValue() : 0L;
            channels.add(new SearchChannelResponse(
                    username,
                    doc.getString("avatarUrl"),
                    doc.getString("badge"),
                    count));
        }
        return channels;
    }

    private String normalizeQuery(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_QUERY_LENGTH) {
            trimmed = trimmed.substring(0, MAX_QUERY_LENGTH);
        }
        return trimmed;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
