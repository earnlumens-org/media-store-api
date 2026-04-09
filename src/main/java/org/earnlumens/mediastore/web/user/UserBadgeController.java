package org.earnlumens.mediastore.web.user;

import jakarta.servlet.http.HttpServletRequest;
import org.earnlumens.mediastore.application.user.UserBadgeService;
import org.earnlumens.mediastore.domain.user.model.UserBadgeAssignment;
import org.earnlumens.mediastore.infrastructure.tenant.TenantResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller for badge operations (claim, check status).
 * All endpoints require authentication (under /api/** which is authenticated by default).
 */
@RestController
@RequestMapping("/api/user/badges")
public class UserBadgeController {

    private static final int COMMUNITY_BADGE_DURATION_YEARS = 1;

    private final TenantResolver tenantResolver;
    private final UserBadgeService userBadgeService;

    public UserBadgeController(TenantResolver tenantResolver, UserBadgeService userBadgeService) {
        this.tenantResolver = tenantResolver;
        this.userBadgeService = userBadgeService;
    }

    /**
     * POST /api/user/badges/claim — Claim the free community badge (U1) for 1 year.
     * Returns 200 with the assignment if granted, or 409 if the user already has one.
     */
    @PostMapping("/claim")
    public ResponseEntity<?> claimCommunityBadge(HttpServletRequest httpRequest) {
        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = tenantResolver.resolve(httpRequest);

        Optional<UserBadgeAssignment> result = userBadgeService.claimCommunityBadge(
                tenantId, userId, COMMUNITY_BADGE_DURATION_YEARS);

        if (result.isEmpty()) {
            return ResponseEntity.status(409).body(Map.of("error", "Badge already claimed"));
        }

        return ResponseEntity.status(201).body(toResponse(result.get()));
    }

    /**
     * GET /api/user/badges/me — Returns the authenticated user's active badges.
     */
    @GetMapping("/me")
    public ResponseEntity<?> getMyBadges(HttpServletRequest httpRequest) {
        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = tenantResolver.resolve(httpRequest);

        List<UserBadgeAssignment> assignments = userBadgeService.getActiveAssignments(tenantId, userId);
        Optional<String> activeBadge = userBadgeService.getActiveBadgeKey(tenantId, userId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("activeBadge", activeBadge.orElse(null));
        response.put("assignments", assignments.stream().map(this::toResponse).toList());

        return ResponseEntity.ok(response);
    }

    private Map<String, Object> toResponse(UserBadgeAssignment assignment) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", assignment.getId());
        map.put("badgeType", assignment.getBadgeType().name().toLowerCase());
        map.put("status", assignment.getStatus().name().toLowerCase());
        map.put("assignedBy", assignment.getAssignedBy().name().toLowerCase());
        map.put("startedAt", assignment.getStartedAt() != null
                ? assignment.getStartedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
        map.put("expiresAt", assignment.getExpiresAt() != null
                ? assignment.getExpiresAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
        return map;
    }

    private String extractUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof OAuth2User principal)) {
            return null;
        }
        Object idAttr = principal.getAttribute("id");
        return idAttr != null ? idAttr.toString() : null;
    }
}
