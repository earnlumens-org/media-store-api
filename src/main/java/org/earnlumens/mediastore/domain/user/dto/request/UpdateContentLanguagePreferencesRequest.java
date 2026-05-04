package org.earnlumens.mediastore.domain.user.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for {@code PATCH /api/user/me/preferences/content-languages}.
 * <p>
 * All fields are optional; only the ones present in the JSON are updated
 * (null means "not provided" — the existing value is preserved). To clear
 * the language list the client sends an empty array {@code []}.
 * <p>
 * The reserved value {@code "multi"} is intentionally rejected here: users
 * pick real languages, and the {@code includeMulti} toggle controls whether
 * language-free content is added to the feed.
 */
public record UpdateContentLanguagePreferencesRequest(
        @Size(max = 30, message = "contentLanguages cannot contain more than 30 entries")
        List<@Pattern(
                regexp = "^[a-z]{2}(-[a-z]{2})?$",
                message = "contentLanguages must contain lowercase ISO 639-1 codes (e.g. 'en', 'es', 'zh-cn'); 'multi' is reserved for the moderation pipeline"
        ) String> contentLanguages,

        Boolean includeMulti,

        Boolean showAllLanguages
) {
}
