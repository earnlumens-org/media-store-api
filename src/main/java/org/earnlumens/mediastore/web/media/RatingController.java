package org.earnlumens.mediastore.web.media;

import jakarta.servlet.http.HttpServletRequest;
import org.earnlumens.mediastore.application.media.RatingService;
import org.earnlumens.mediastore.domain.media.dto.response.RatingResponse;
import org.earnlumens.mediastore.domain.media.model.Rating;
import org.earnlumens.mediastore.domain.media.model.TargetType;
import org.earnlumens.mediastore.infrastructure.tenant.TenantResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Authenticated rating API for a target (entry or collection).
 *
 * <ul>
 *   <li>{@code POST   /api/ratings/{targetType}/{targetId}} — create/update my rating</li>
 *   <li>{@code DELETE /api/ratings/{targetType}/{targetId}} — remove my rating</li>
 *   <li>{@code GET    /api/ratings/{targetType}/{targetId}/me} — fetch my rating</li>
 * </ul>
 *
 * <p>{@code targetType} is {@code entry} or {@code collection} (case-insensitive).
 * Auth is enforced by {@code anyRequest().authenticated()} in
 * {@code WebSecurityConfig} ({@code /api/**}).</p>
 */
@RestController
@RequestMapping("/api/ratings")
public class RatingController {

    private final TenantResolver tenantResolver;
    private final RatingService ratingService;

    public RatingController(TenantResolver tenantResolver, RatingService ratingService) {
        this.tenantResolver = tenantResolver;
        this.ratingService = ratingService;
    }

    /** Body: { "liked": true|false, "comment": "optional plain text" }. */
    @PostMapping("/{targetType}/{targetId}")
    public ResponseEntity<?> submit(
            @PathVariable("targetType") String targetTypeRaw,
            @PathVariable("targetId") String targetId,
            @RequestBody SubmitRatingRequest body,
            HttpServletRequest request) {

        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        TargetType targetType = parseTargetType(targetTypeRaw);
        if (targetType == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_TARGET_TYPE"));
        }
        if (body == null || body.liked() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_VOTE"));
        }
        String tenantId = tenantResolver.resolve(request);
        String username = extractUsername();

        try {
            Rating rating = ratingService.submitRating(
                    tenantId, userId, username, targetType, targetId, body.liked(), body.comment());
            return ResponseEntity.ok(Map.of(
                    "rating", ratingService.toRatingResponse(rating),
                    "aggregate", ratingService.getAggregateResponse(tenantId, targetType, targetId)
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            String msg = e.getMessage();
            if ("PURCHASE_REQUIRED".equals(msg)) {
                return ResponseEntity.status(403).body(Map.of("error", msg));
            }
            if ("DAILY_RATING_LIMIT_REACHED".equals(msg)) {
                return ResponseEntity.status(429).body(Map.of("error", msg));
            }
            return ResponseEntity.status(409).body(Map.of("error", msg));
        }
    }

    @DeleteMapping("/{targetType}/{targetId}")
    public ResponseEntity<?> delete(
            @PathVariable("targetType") String targetTypeRaw,
            @PathVariable("targetId") String targetId,
            HttpServletRequest request) {

        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        TargetType targetType = parseTargetType(targetTypeRaw);
        if (targetType == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_TARGET_TYPE"));
        }
        String tenantId = tenantResolver.resolve(request);
        ratingService.deleteRating(tenantId, userId, targetType, targetId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{targetType}/{targetId}/me")
    public ResponseEntity<?> myRating(
            @PathVariable("targetType") String targetTypeRaw,
            @PathVariable("targetId") String targetId,
            HttpServletRequest request) {

        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        TargetType targetType = parseTargetType(targetTypeRaw);
        if (targetType == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_TARGET_TYPE"));
        }
        String tenantId = tenantResolver.resolve(request);
        RatingResponse mine = ratingService.toRatingResponse(
                ratingService.getMyRating(tenantId, userId, targetType, targetId));
        if (mine == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(mine);
    }

    private TargetType parseTargetType(String raw) {
        if (raw == null) return null;
        try {
            return TargetType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String extractUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof OAuth2User principal)) return null;
        Object idAttr = principal.getAttribute("id");
        return idAttr != null ? idAttr.toString() : null;
    }

    private String extractUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof OAuth2User principal)) return null;
        Object attr = principal.getAttribute("username");
        return attr != null ? attr.toString() : null;
    }

    public record SubmitRatingRequest(Boolean liked, String comment) {}
}
