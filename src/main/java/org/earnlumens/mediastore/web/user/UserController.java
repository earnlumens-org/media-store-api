package org.earnlumens.mediastore.web.user;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.earnlumens.mediastore.application.user.UserBadgeService;
import org.earnlumens.mediastore.application.user.UserService;
import org.earnlumens.mediastore.domain.user.dto.request.UpdateContentLanguagePreferencesRequest;
import org.earnlumens.mediastore.domain.user.model.User;
import org.earnlumens.mediastore.infrastructure.security.jwt.JwtUtils;
import org.earnlumens.mediastore.infrastructure.tenant.TenantResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;
    private final UserBadgeService userBadgeService;
    private final TenantResolver tenantResolver;
    private final JwtUtils jwtUtils;

    public UserController(UserService userService, UserBadgeService userBadgeService,
                          TenantResolver tenantResolver, JwtUtils jwtUtils) {
        this.userService = userService;
        this.userBadgeService = userBadgeService;
        this.tenantResolver = tenantResolver;
        this.jwtUtils = jwtUtils;
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
            String oauthUserId = idAttr.toString();
            userBadgeService.getActiveBadgeKey(tenantId, oauthUserId)
                    .ifPresent(badge -> response.put("profileBadge", badge));
            // Inject persisted content-language preferences (Phase 4).
            userService.findByOauthUserId(oauthUserId)
                    .ifPresent(user -> {
                        response.put("contentLanguagePreferences", toPreferencesMap(user));
                        // Self-status disclosure: a user must always be able
                        // to see their own sanction state. Only populated when
                        // the user has an active warning, strike count or ban
                        // window so we do not bloat the response for the 99%
                        // case of clean accounts.
                        Map<String, Object> sanction = toSelfSanctionMap(user);
                        if (!sanction.isEmpty()) {
                            response.put("sanctionStatus", sanction);
                        }
                    });
        }

        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /api/user/me/preferences/content-languages — update consumer-side
     * content language preferences. Partial update: only fields present in
     * the JSON are changed.
     */
    @PatchMapping("/me/preferences/content-languages")
    public ResponseEntity<?> updateContentLanguagePreferences(
            @Valid @RequestBody UpdateContentLanguagePreferencesRequest request
    ) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof OAuth2User oauth2User)) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        Object idAttr = oauth2User.getAttribute("id");
        if (idAttr == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        return userService.updateContentLanguagePreferences(idAttr.toString(), request)
                .<ResponseEntity<?>>map(user -> {
                    Map<String, Object> body = toPreferencesMap(user);
                    // Language prefs live as access-token claims (P1-1), so a
                    // stale token would keep filtering feeds by the OLD prefs
                    // until it expires. Mint a fresh token carrying the new
                    // claims; the UI swaps it in before refetching feeds.
                    body.put("accessToken", jwtUtils.generateJwtToken(user));
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "user not found")));
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

    /**
     * Serialize content-language preferences with safe defaults so the UI
     * always receives a fully-populated object (never null fields).
     */
    private Map<String, Object> toPreferencesMap(User user) {
        Map<String, Object> prefs = new LinkedHashMap<>();
        List<String> langs = user.getContentLanguages();
        prefs.put("contentLanguages", langs != null ? langs : List.of());
        prefs.put("includeMulti", user.getIncludeMulti() == null ? Boolean.TRUE : user.getIncludeMulti());
        prefs.put("showAllLanguages", user.getShowAllLanguages() == null ? Boolean.FALSE : user.getShowAllLanguages());
        return prefs;
    }

    /**
     * Self-disclosure of moderation status. Returned to the user under
     * {@code sanctionStatus} on {@code GET /api/user/me} so the storefront
     * can render a banner explaining what is going on. Empty when the user
     * has no warnings, strikes or active ban.
     *
     * <p>Intentionally omits the moderator's identity ({@code banIssuedBy}):
     * the affected user sees the platform decision, not who personally
     * pressed the button.
     */
    private Map<String, Object> toSelfSanctionMap(User user) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (user.isBlocked()) {
            out.put("blocked", true);
            out.put("banType", user.getBanType() != null ? user.getBanType() : "PERMA_BAN");
            if (user.getBanReason() != null) out.put("reason", user.getBanReason());
            if (user.getBanExpiresAt() != null) out.put("expiresAt", user.getBanExpiresAt().toString());
            if (user.getBlockedAt() != null) out.put("issuedAt", user.getBlockedAt().toString());
        }
        Integer strikes = user.getStrikeCount();
        if (strikes != null && strikes > 0) {
            out.put("strikeCount", strikes);
            if (user.getLastStrikeAt() != null) {
                out.put("lastStrikeAt", user.getLastStrikeAt().toString());
            }
        }
        return out;
    }
}
