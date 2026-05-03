package org.earnlumens.mediastore.web.media;

import org.earnlumens.mediastore.application.media.PublicEntryService;
import org.earnlumens.mediastore.domain.media.dto.response.PublicEntryPageResponse;
import org.earnlumens.mediastore.domain.media.dto.response.PublicEntryResponse;
import org.earnlumens.mediastore.domain.media.dto.response.PublicFeedPageResponse;
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

    public PublicEntryController(PublicEntryService publicEntryService) {
        this.publicEntryService = publicEntryService;
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
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "48") int size
    ) {
        String tenantId = TenantContext.require();
        PublicFeedPageResponse response = publicEntryService.getCommunityFeed(tenantId, type, pricing, sort, page, size);
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
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "48") int size
    ) {
        String tenantId = TenantContext.require();
        PublicFeedPageResponse response = publicEntryService.getExploreFeed(tenantId, type, pricing, sort, page, size);
        return ResponseEntity.ok(response);
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
