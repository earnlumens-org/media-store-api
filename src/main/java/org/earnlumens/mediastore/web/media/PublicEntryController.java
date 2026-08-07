package org.earnlumens.mediastore.web.media;

import jakarta.servlet.http.HttpServletRequest;
import org.earnlumens.mediastore.application.media.PublicEntryService;
import org.earnlumens.mediastore.application.user.UserService;
import org.earnlumens.mediastore.domain.media.dto.response.PublicEntryPageResponse;
import org.earnlumens.mediastore.domain.media.dto.response.PublicEntryResponse;
import org.earnlumens.mediastore.domain.media.dto.response.PublicFeedPageResponse;
import org.earnlumens.mediastore.domain.media.model.LanguageFilter;
import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public (no auth) controller for browsing published entries.
 * Path is under /public/** which is permitAll in WebSecurityConfig.
 */
@RestController
@RequestMapping("/public/entries")
public class PublicEntryController {

    private final PublicEntryService publicEntryService;
    private final UserService userService;

    public PublicEntryController(PublicEntryService publicEntryService, UserService userService) {
        this.publicEntryService = publicEntryService;
        this.userService = userService;
    }

    /**
     * GET /public/entries?page=0&size=48
     * Returns paginated PUBLISHED entries for the resolved tenant,
     * ordered by publishedAt descending.
     */
    @GetMapping
    public ResponseEntity<PublicEntryPageResponse> getPublishedEntries(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "48") int size
    ) {
        String tenantId = TenantContext.require();
        PublicEntryPageResponse response = publicEntryService.getPublishedEntries(tenantId, page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /public/entries/community/feed?type=&pricing=&sort=newest&page=0&size=48
     * Community feed: PUBLISHED entries + collections from users with an active badge.
     * Filters by authorBadge="u1". No auth required.
     */
    @GetMapping("/community/feed")
    public ResponseEntity<PublicFeedPageResponse> getCommunityFeed(
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "pricing", required = false) String pricing,
            @RequestParam(value = "sort", defaultValue = "newest") String sort,
            @RequestParam(value = "lang", required = false) String langOverride,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "48") int size,
            HttpServletRequest request
    ) {
        String tenantId = TenantContext.require();
        LanguageFilter languageFilter = resolveLanguageFilter(langOverride, request);
        PublicFeedPageResponse response = publicEntryService.getCommunityFeed(
                tenantId, type, pricing, sort, languageFilter, page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /public/entries/feed?type=&sort=newest&page=0&size=48
     * Unified explore feed: ALL published entries + collections merged via $unionWith.
     * No auth required. Locked/unlocked resolved client-side.
     */
    @GetMapping("/feed")
    public ResponseEntity<PublicFeedPageResponse> getExploreFeed(
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "pricing", required = false) String pricing,
            @RequestParam(value = "sort", defaultValue = "newest") String sort,
            @RequestParam(value = "lang", required = false) String langOverride,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "48") int size,
            HttpServletRequest request
    ) {
        String tenantId = TenantContext.require();
        LanguageFilter languageFilter = resolveLanguageFilter(langOverride, request);
        PublicFeedPageResponse response = publicEntryService.getExploreFeed(
                tenantId, type, pricing, sort, languageFilter, page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * Resolve the {@link LanguageFilter} to apply for this request.
     * <p>
     * Resolution order (explicit choice always beats defaults):
     * <ol>
     *   <li>If {@code langOverride == "all"}, return {@link LanguageFilter#NONE}
     *       (per-request escape hatch — "Show all languages" and the UI's
     *       pagination of a fallback feed).</li>
     *   <li>If {@code langOverride} is a CSV of language codes (optionally
     *       including the {@code multi} token), build the filter from it.
     *       This is how guests apply the preferences they configured in the
     *       dialog (persisted client-side in localStorage).</li>
     *   <li>If the access token carries language claims (all tokens minted
     *       after the P1-1 migration), build the filter from the principal —
     *       zero DB lookups on the feed hot path. An empty language list with
     *       {@code showAllLanguages=false} means the account never configured
     *       preferences → fall through to the browser-language default.
     *       Staleness is bounded by the short access-token expiry, and the
     *       preferences PATCH returns a freshly minted token so changes apply
     *       immediately.</li>
     *   <li>Legacy tokens read the persisted user preferences (one DB lookup,
     *       self-healing within a single access-token lifetime), with the same
     *       never-configured fallthrough.</li>
     *   <li>Default (anonymous, or authenticated but never configured):
     *       filter by the browser's {@code Accept-Language} languages plus
     *       language-free ({@code multi}) content. The feed service
     *       automatically falls back to all languages when this default
     *       matches nothing, so a sparse tenant never renders empty.</li>
     * </ol>
     */
    private LanguageFilter resolveLanguageFilter(String langOverride, HttpServletRequest request) {
        if ("all".equalsIgnoreCase(langOverride)) {
            return LanguageFilter.NONE;
        }
        LanguageFilter explicit = parseLangOverride(langOverride);
        if (explicit != null) {
            return explicit;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof OAuth2User principal)) {
            return browserLanguageDefault(request);
        }
        Object idAttr = principal.getAttribute("id");
        if (idAttr == null) {
            return browserLanguageDefault(request);
        }
        Object langsAttr = principal.getAttribute("content_languages");
        if (langsAttr instanceof java.util.List<?> languages) {
            Boolean includeMulti = principal.getAttribute("include_multi");
            Boolean showAllLanguages = principal.getAttribute("show_all_languages");
            if (showAllLanguages != null && showAllLanguages) {
                return LanguageFilter.NONE;
            }
            if (languages.isEmpty()) {
                // Account never configured preferences — browser default.
                return browserLanguageDefault(request);
            }
            return new LanguageFilter(
                    languages.stream().map(String::valueOf).toList(),
                    includeMulti == null || includeMulti,
                    false);
        }
        // Legacy tokens minted before the claims migration: one DB lookup,
        // self-healing within a single access-token lifetime.
        return userService.findByOauthUserId(idAttr.toString())
                .map(user -> {
                    if (user.getShowAllLanguages() != null && user.getShowAllLanguages()) {
                        return LanguageFilter.NONE;
                    }
                    java.util.List<String> langs = user.getContentLanguages();
                    if (langs == null || langs.isEmpty()) {
                        return browserLanguageDefault(request);
                    }
                    return new LanguageFilter(
                            langs,
                            user.getIncludeMulti() == null || user.getIncludeMulti(),
                            false);
                })
                .orElseGet(() -> browserLanguageDefault(request));
    }

    /**
     * Parse an explicit {@code lang} CSV override (e.g. {@code "es,en,multi"})
     * into a filter. The special token {@code multi} enables language-free
     * content. Codes are validated ({@code xx} / {@code xxx} optionally with a
     * region subtag like {@code zh-cn}), lowercased, deduped and capped at 10;
     * an override with no valid codes is treated as absent ({@code null}).
     */
    private LanguageFilter parseLangOverride(String langOverride) {
        if (langOverride == null || langOverride.isBlank()) {
            return null;
        }
        boolean includeMulti = false;
        java.util.List<String> langs = new java.util.ArrayList<>();
        for (String token : langOverride.split(",")) {
            String code = token.trim().toLowerCase(java.util.Locale.ROOT);
            if (code.equals("multi")) {
                includeMulti = true;
            } else if (code.matches("[a-z]{2,3}(-[a-z]{2,8})?")
                    && langs.size() < 10 && !langs.contains(code)) {
                langs.add(code);
            }
        }
        if (langs.isEmpty()) {
            return null;
        }
        return new LanguageFilter(langs, includeMulti, false);
    }

    /**
     * Default filter derived from the browser's {@code Accept-Language}
     * header: up to 3 primary languages (quality-ordered) plus language-free
     * content. Chinese keeps its script-bearing content codes
     * ({@code zh-cn} / {@code zh-tw}). No header → no filter.
     */
    private LanguageFilter browserLanguageDefault(HttpServletRequest request) {
        if (request == null || request.getHeader("Accept-Language") == null) {
            return LanguageFilter.NONE;
        }
        java.util.LinkedHashSet<String> langs = new java.util.LinkedHashSet<>();
        java.util.Enumeration<java.util.Locale> locales = request.getLocales();
        while (locales.hasMoreElements() && langs.size() < 3) {
            java.util.Locale locale = locales.nextElement();
            String lang = locale.getLanguage().toLowerCase(java.util.Locale.ROOT);
            if (lang.isBlank()) {
                continue;
            }
            if (lang.equals("zh")) {
                String region = locale.getCountry().toUpperCase(java.util.Locale.ROOT);
                String script = locale.getScript();
                boolean traditional = region.equals("TW") || region.equals("HK") || region.equals("MO")
                        || script.equalsIgnoreCase("Hant");
                langs.add(traditional ? "zh-tw" : "zh-cn");
            } else {
                langs.add(lang);
            }
        }
        if (langs.isEmpty()) {
            return LanguageFilter.NONE;
        }
        return new LanguageFilter(java.util.List.copyOf(langs), true, false);
    }

    /**
     * GET /public/entries/{id}
     * Returns a single PUBLISHED entry by ID.
     * Returns 404 if the entry doesn't exist or is not published.
     */
    @GetMapping("/{id}")
    public ResponseEntity<PublicEntryResponse> getPublishedEntryById(
            @PathVariable("id") String id
    ) {
        String tenantId = TenantContext.require();
        // Pass viewer userId so ARCHIVED entries remain visible to the
        // owner and to users who already paid for them (entry- or
        // collection-level entitlement). Anonymous viewers still get 404.
        String viewerUserId = extractOptionalUserId();
        return publicEntryService.getPublishedEntryById(tenantId, id, viewerUserId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /public/entries/by-user/{username}?type=video&page=0&size=48
     * Returns paginated PUBLISHED entries for a specific author,
     * optionally filtered by type (video, audio, image, entry, file).
     */
    @GetMapping("/by-user/{username}")
    public ResponseEntity<PublicEntryPageResponse> getPublishedEntriesByUser(
            @PathVariable("username") String username,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "48") int size
    ) {
        String tenantId = TenantContext.require();
        PublicEntryPageResponse response = publicEntryService.getPublishedEntriesByUser(tenantId, username, type, page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /public/entries/by-user/{username}/feed?type=&search=&sort=newest&page=0&size=24
     * Unified profile feed: entries + collections merged via $unionWith.
     * Optionally uses viewer's auth for locked/unlocked resolution.
     */
    @GetMapping("/by-user/{username}/feed")
    public ResponseEntity<PublicFeedPageResponse> getProfileFeed(
            @PathVariable("username") String username,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "sort", defaultValue = "newest") String sort,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "24") int size
    ) {
        String tenantId = TenantContext.require();
        String userId = extractOptionalUserId();
        String viewerUsername = extractOptionalUsername();
        PublicFeedPageResponse response = publicEntryService.getProfileFeed(
                tenantId, username, userId, viewerUsername, type, search, sort, page, size);
        return ResponseEntity.ok(response);
    }

    private String extractOptionalUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof OAuth2User principal)) return null;
        Object idAttr = principal.getAttribute("id");
        return idAttr != null ? idAttr.toString() : null;
    }

    private String extractOptionalUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof OAuth2User principal)) return null;
        Object attr = principal.getAttribute("username");
        return attr != null ? attr.toString() : null;
    }
}
