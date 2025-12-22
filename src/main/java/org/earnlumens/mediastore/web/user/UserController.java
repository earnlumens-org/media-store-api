package org.earnlumens.mediastore.web.user;

import org.earnlumens.mediastore.application.user.UserService;
import org.earnlumens.mediastore.domain.user.model.User;
import org.springframework.http.ResponseEntity;
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

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/by-username/{username}")
    public ResponseEntity<?> getByUsername(@PathVariable("username") String username) {
        if (!StringUtils.hasText(username)) {
            return ResponseEntity.badRequest().body(Map.of("error", "username is required"));
        }

        return userService.findByUsername(username)
                .<ResponseEntity<?>>map(user -> ResponseEntity.ok(toResponse(user)))
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
        response.put("id", user.getId());
        response.put("username", user.getUsername());
        response.put("displayName", user.getDisplayName());
        response.put("profileImageUrl", user.getProfileImageUrl());
        response.put("followersCount", user.getFollowersCount());
        return response;
    }
}
