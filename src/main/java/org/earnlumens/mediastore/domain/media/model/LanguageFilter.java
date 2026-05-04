package org.earnlumens.mediastore.domain.media.model;

import java.util.List;

/**
 * Consumer-side content language filter for public feed queries.
 * <p>
 * Encapsulates the three Phase 4 user preferences:
 * <ul>
 *   <li>{@code languages} — ISO 639-1 codes the user wants to see</li>
 *   <li>{@code includeMulti} — whether to also show language-free content
 *       tagged {@code "multi"} (default behavior)</li>
 *   <li>{@code showAllLanguages} — escape hatch; when {@code true} the
 *       filter is disabled entirely</li>
 * </ul>
 * The {@code applies()} method returns {@code true} only when a filter
 * should actually be added to the Mongo aggregation; this lets repositories
 * skip the language stage entirely for anonymous users or users that opted
 * into "show all languages".
 *
 * @param languages       lowercase ISO 639-1 codes; never null (use empty list)
 * @param includeMulti    include {@code "multi"} entries
 * @param showAllLanguages bypass the filter
 */
public record LanguageFilter(
        List<String> languages,
        boolean includeMulti,
        boolean showAllLanguages
) {

    /** Filter that lets every entry through (anonymous users, discovery mode). */
    public static final LanguageFilter NONE = new LanguageFilter(List.of(), true, true);

    public LanguageFilter {
        languages = languages == null ? List.of() : List.copyOf(languages);
    }

    /**
     * @return {@code true} when the filter should be applied to the query.
     *         Returns {@code false} for {@link #NONE}, for users that
     *         explicitly chose "show all languages", and for users with no
     *         preferred languages AND no {@code multi} fallback (which
     *         would yield an empty feed — the caller should treat this as
     *         a no-op rather than match nothing).
     */
    public boolean applies() {
        if (showAllLanguages) {
            return false;
        }
        // Empty list + no multi => filter would match nothing. Treat as no-op
        // so the user still sees content (the UI guards against this state).
        return !languages.isEmpty() || includeMulti;
    }
}
