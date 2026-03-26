package org.earnlumens.mediastore.web.media;

import jakarta.servlet.http.HttpServletRequest;
import org.earnlumens.mediastore.application.media.CollectionService;
import org.earnlumens.mediastore.domain.media.dto.response.CollectionDetailResponse;
import org.earnlumens.mediastore.domain.media.dto.response.CollectionPageResponse;
import org.earnlumens.mediastore.infrastructure.tenant.TenantResolver;
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
 * Public (no auth required) controller for browsing published collections.
 * Path is under /public/** which is permitAll in WebSecurityConfig.
 */
@RestController
@RequestMapping("/public/collections")
public class PublicCollectionController {

    private final TenantResolver tenantResolver;
    private final CollectionService collectionService;

    public PublicCollectionController(TenantResolver tenantResolver, CollectionService collectionService) {
        this.tenantResolver = tenantResolver;
        this.collectionService = collectionService;
    }

    /**
     * GET /public/collections?page=0&size=48
     * Returns paginated PUBLISHED + PUBLIC collections for the resolved tenant.
     * If ?username=xxx is provided, filters by that author.
     */
    @GetMapping
    public ResponseEntity<CollectionPageResponse> getPublishedCollections(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "48") int size,
            @RequestParam(value = "username", required = false) String username,
            HttpServletRequest request) {

        String tenantId = tenantResolver.resolve(request);
        String userId = extractUserIdOptional();
        CollectionPageResponse response;
        if (username != null && !username.isBlank()) {
            response = collectionService.getPublicCollectionsByUsername(tenantId, username.trim(), userId, page, size);
        } else {
            response = collectionService.getPublicCollections(tenantId, userId, page, size);
        }
        return ResponseEntity.ok(response);
    }

    /**
     * GET /public/collections/{id}
     * Returns a single collection with hydrated entries and per-item access status.
     * Optionally extracts userId from the auth context to compute locked/unlocked.
     */
    @GetMapping("/{id}")
    public ResponseEntity<CollectionDetailResponse> getCollectionDetail(
            @PathVariable("id") String id,
            HttpServletRequest request) {

        String tenantId = tenantResolver.resolve(request);
        String userId = extractUserIdOptional();

        return collectionService.getCollectionDetail(tenantId, id, userId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Best-effort userId extraction — returns null if not authenticated. */
    private String extractUserIdOptional() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !(auth.getPrincipal() instanceof OAuth2User principal)) return null;
            Object idAttr = principal.getAttribute("id");
            return idAttr != null ? idAttr.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
