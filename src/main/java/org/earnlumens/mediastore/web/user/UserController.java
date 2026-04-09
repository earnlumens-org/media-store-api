package org.earnlumens.mediastore.web.user;

import jakarta.servlet.http.HttpServletRequest;
import org.earnlumens.mediastore.application.user.UserBadgeService;
import org.earnlumens.mediastore.application.user.UserService;
import org.earnlumens.mediastore.domain.user.model.User;
import org.earnlumens.mediastore.infrastructure.tenant.TenantResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;
    private final UserBadgeService userBadgeService;
    private final TenantResolver tenantResolver;

    public UserController(UserService userService, UserBadgeService userBadgeService,
                          TenantResolver tenantResolver) {
        this.userService = userService;
        this.userBadgeService = userBadgeService;
        this.tenantResolver = tenantResolver;
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest httpRequest) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof OAuth2User oauth2User)) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = tenantResolver.resolve(httpRequest);
        Map<String, Object> response = toResponse(oauth2User);

        Object idAttr = oauth2User.getAttribute("id");
        if (idAttr != null) {
            userBadgeService.getActiveBadgeKey(tenantId, idAttr.toString())
                    .ifPresent(badge -> response.put("profileBadge", badge));
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/by-username/{username}")
    public ResponseEntity<?> getByUsername(@PathVariable("username") String username,
                                           HttpServletRequest httpRequest) {
        if (!StringUtils.hasText(username)) {
            return ResponseEntity.badRequest().body(Map.of("error", "username is required"));
        }

        String tenantId = tenantResolver.resolve(httpRequest);
        return userService.findByUsername(username)
                .<ResponseEntity<?>>map(user -> {
                    Map<String, Object> response = toResponse(user);
                    userBadgeService.getActiveBadgeKey(tenantId, user.getOauthUserId())
                            .ifPresent(badge -> response.put("profileBadge", badge));
                    return ResponseEntity.ok(response);
                })
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "user not found")));
    }

    @GetMapping("/exists/{username}")
    public ResponseEntity<?> existsByUsername(@PathVariable("username") String username) {
        if (!StringUtils.hasText(username)) {
            return ResponseEntity.badRequest().body(Map.of("error", "username is required"));
        }

        boolean exists = userService.existsByUsername(username);
        return ResponseEntity.ok(Map.of("username", username, "exists", exists));
    }

    private Map<String, Object> toResponse(User user) {
        Map<String, Object> response = new LinkedHashMap<>();
        // Expose the OAuth provider ID (same identifier used by JWT / extractUserId everywhere)
        // rather than the internal MongoDB _id, so subscription and other features stay consistent.
        response.put("id", user.getOauthUserId());
        response.put("username", user.getUsername());
        response.put("displayName", user.getDisplayName());
        response.put("profileImageUrl", user.getProfileImageUrl());
        response.put("followersCount", user.getFollowersCount());
        return response;
    }

    private Map<String, Object> toResponse(OAuth2User oauth2User) {
        Map<String, Object> attributes = oauth2User.getAttributes();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", attributes.get("id"));
        response.put("username", attributes.get("username"));
        response.put("displayName", attributes.get("name"));
        response.put("profileImageUrl", attributes.get("profile_image_url"));
        response.put("followersCount", attributes.get("followers_count"));
        response.put("oauthProvider", attributes.get("oauth_provider"));
        return response;
    }
}
