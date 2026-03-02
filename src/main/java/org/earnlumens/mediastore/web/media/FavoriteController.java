package org.earnlumens.mediastore.web.media;

import jakarta.servlet.http.HttpServletRequest;
import org.earnlumens.mediastore.application.media.FavoriteService;
import org.earnlumens.mediastore.domain.media.dto.response.FavoritePageResponse;
import org.earnlumens.mediastore.domain.media.model.FavoriteItemType;
import org.earnlumens.mediastore.infrastructure.tenant.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for user favorites.
 *
 * <ul>
 *   <li>{@code GET    /api/favorites}              — paginated list</li>
 *   <li>{@code POST   /api/favorites/{itemId}}      — toggle (add/remove)</li>
 *   <li>{@code GET    /api/favorites/check/{itemId}} — check if favorited</li>
 *   <li>{@code DELETE /api/favorites/{itemId}}       — explicit remove</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/favorites")
public class FavoriteController {

    private static final Logger logger = LoggerFactory.getLogger(FavoriteController.class);

    private final TenantResolver tenantResolver;
    private final FavoriteService favoriteService;

    public FavoriteController(TenantResolver tenantResolver,
                              FavoriteService favoriteService) {
        this.tenantResolver = tenantResolver;
        this.favoriteService = favoriteService;
    }

    /**
     * List the user's favorites (paginated, newest first).
     */
    @GetMapping
    public ResponseEntity<?> listFavorites(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "24") int size,
            HttpServletRequest request) {

        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        String tenantId = tenantResolver.resolve(request);

        try {
            FavoritePageResponse response = favoriteService.listFavorites(tenantId, userId, page, size);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error listing favorites for userId={}: {}", userId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to list favorites"));
        }
    }

    /**
     * Toggle a favorite (add if not present, remove if already favorited).
     * Body: {@code { "itemType": "ENTRY" | "COLLECTION" }}
     */
    @PostMapping("/{itemId}")
    public ResponseEntity<?> toggleFavorite(
            @PathVariable("itemId") String itemId,
            @RequestBody ToggleFavoriteRequest body,
            HttpServletRequest request) {

        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        String tenantId = tenantResolver.resolve(request);

        FavoriteItemType itemType;
        try {
            itemType = FavoriteItemType.valueOf(body.itemType().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid itemType. Must be ENTRY or COLLECTION"));
        }

        try {
            boolean added = favoriteService.toggleFavorite(tenantId, userId, itemId, itemType);
            return ResponseEntity.ok(Map.of("favorited", added));
        } catch (Exception e) {
            logger.error("Error toggling favorite itemId={} for userId={}: {}", itemId, userId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to toggle favorite"));
        }
    }

    /**
     * Check if a specific item is favorited by the current user.
     */
    @GetMapping("/check/{itemId}")
    public ResponseEntity<?> checkFavorite(
            @PathVariable("itemId") String itemId,
            HttpServletRequest request) {

        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        String tenantId = tenantResolver.resolve(request);

        try {
            boolean favorited = favoriteService.isFavorite(tenantId, userId, itemId);
            return ResponseEntity.ok(Map.of("favorited", favorited));
        } catch (Exception e) {
            logger.error("Error checking favorite itemId={} for userId={}: {}", itemId, userId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to check favorite"));
        }
    }

    /**
     * Explicit remove (same as toggle when already favorited, but idempotent).
     */
    @DeleteMapping("/{itemId}")
    public ResponseEntity<?> removeFavorite(
            @PathVariable("itemId") String itemId,
            HttpServletRequest request) {

        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        String tenantId = tenantResolver.resolve(request);

        try {
            // Use toggle logic: if it exists, remove it; if not, no-op
            boolean wasFavorited = favoriteService.isFavorite(tenantId, userId, itemId);
            if (wasFavorited) {
                favoriteService.toggleFavorite(tenantId, userId, itemId, FavoriteItemType.ENTRY); // itemType irrelevant for removal
            }
            return ResponseEntity.ok(Map.of("favorited", false));
        } catch (Exception e) {
            logger.error("Error removing favorite itemId={} for userId={}: {}", itemId, userId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to remove favorite"));
        }
    }

    private String extractUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof OAuth2User principal)) return null;
        Object idAttr = principal.getAttribute("id");
        return idAttr != null ? idAttr.toString() : null;
    }

    /**
     * Request body for the toggle endpoint.
     */
    public record ToggleFavoriteRequest(String itemType) {}
}
