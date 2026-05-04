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
     *         <p>
     *         Returns {@code false} for {@link #NONE}, for users that
     *         explicitly chose "show all languages", and for users that
     *         have no preferred languages configured \u2014 we treat the
     *         empty-prefs case as "user has not opted in yet" and let the
     *         feed return everything (otherwise first-time logins would
     *         see an almost empty feed filtered to {@code "multi"} only).
     *         The dialog still lets the user explicitly enable
     *         {@code includeMulti} with an empty language list, but that
     *         configuration is footgun-guarded in the UI.
     */
    public boolean applies() {
        if (showAllLanguages) {
            return false;
        }
        return !languages.isEmpty();
    }
}
