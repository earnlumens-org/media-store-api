package org.earnlumens.mediastore.web.space.dto;

import java.util.Map;

/**
 * Public-facing projection of a {@code Space} for the end-user UI.
 * Excludes administrative-only fields (publish rules, archived flag,
 * paid-publish toggle).
 *
 * <p><b>{@code key}</b> is included so the UI can recognise the system
 * Explore space ({@code key="explore"}) and render its title from the
 * global EarnLumens i18n bundle (the system space has no
 * {@code baseName}/translations on purpose).
 */
public record PublicSpaceResponse(
        String id,
        String key,
        boolean systemSpace,
        int sortOrder,
        String icon,
        String baseName,
        Map<String, String> translations
) {}
