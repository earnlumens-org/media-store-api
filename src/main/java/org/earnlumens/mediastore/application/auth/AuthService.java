package org.earnlumens.mediastore.application.auth;

import org.earnlumens.mediastore.application.user.UserService;
import org.earnlumens.mediastore.domain.user.model.User;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private static final Duration TEMP_UUID_TTL = Duration.ofMinutes(2);

    private final UserService userService;

    public AuthService(UserService userService) {
        this.userService = userService;
    }

    public String generateTempUUID(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof OAuth2User oauth2User)) {
            throw new IllegalArgumentException("Authentication principal must be an OAuth2User");
        }

        String oauthUserId = Objects.requireNonNull(oauth2User.getAttribute("id")).toString();
        String displayName = Optional.ofNullable(oauth2User.getAttribute("name")).map(Object::toString).orElse("Unknown");
        String username = Optional.ofNullable(oauth2User.getAttribute("username")).map(Object::toString).orElse("Unknown");
        String oauthProvider = Optional.ofNullable(oauth2User.getAttribute("oauth_provider")).map(Object::toString).orElse("unknown");
        String profileImageUrl = Optional.ofNullable(oauth2User.getAttribute("profile_image_url"))
                .map(Object::toString)
                .map(url -> url.replace("_normal", "_400x400"))
                .orElse("");

        Integer followersCount = Optional.ofNullable(oauth2User.getAttribute("public_metrics"))
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(metrics -> metrics.get("followers_count"))
                .map(Object::toString)
                .map(Integer::valueOf)
                .orElse(0);

        String tempUUID = UUID.randomUUID().toString();

        Optional<User> optionalUser = userService.findByOauthUserId(oauthUserId);

        if (optionalUser.isPresent()) {
            User user = optionalUser.get();
            user.setDisplayName(displayName);
            user.setUsername(username);
            user.setProfileImageUrl(profileImageUrl);
            user.setFollowersCount(followersCount);
            user.setLastLoginAt(LocalDateTime.now());
            user.setTempUUID(tempUUID);
            user.setTempUUIDCreatedAt(Instant.now());
            userService.save(user);
        } else {
            User newUser = new User();
            newUser.setOauthUserId(oauthUserId);
            newUser.setOauthProvider(oauthProvider);
            newUser.setUsername(username);
            newUser.setDisplayName(displayName);
            newUser.setProfileImageUrl(profileImageUrl);
            newUser.setFollowersCount(followersCount);
            newUser.setCreatedAt(LocalDateTime.now());
            newUser.setLastLoginAt(LocalDateTime.now());
            newUser.setTempUUID(tempUUID);
            newUser.setTempUUIDCreatedAt(Instant.now());
            userService.save(newUser);
        }

        return tempUUID;
    }

    public Optional<User> findUserByTempUUID(String tempUUID) {
        Optional<User> optionalUser = userService.findByTempUUID(tempUUID);
        if (optionalUser.isPresent()) {
            User user = optionalUser.get();

            // Validar expiración (2 minutos)
            if (isExpired(user.getTempUUIDCreatedAt())) {
                invalidateTempUUID(user);
                return Optional.empty();
            }

            invalidateTempUUID(user);
            return Optional.of(user);
        }
        return Optional.empty();
    }

    private boolean isExpired(Instant createdAt) {
        if (createdAt == null) {
            return true; // Sin fecha = expirado por seguridad
        }
        return Instant.now().isAfter(createdAt.plus(TEMP_UUID_TTL));
    }

    private void invalidateTempUUID(User user) {
        user.setTempUUID(null);
        user.setTempUUIDCreatedAt(null);
        userService.save(user);
    }
}
