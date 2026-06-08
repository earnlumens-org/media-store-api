package org.earnlumens.mediastore.domain.media.dto.response;

import java.util.List;

/**
 * Autocomplete suggestions for the search box — lightweight title/creator
 * completions used while the visitor is still typing.
 */
public record SearchSuggestionsResponse(
        List<String> suggestions
) {}
