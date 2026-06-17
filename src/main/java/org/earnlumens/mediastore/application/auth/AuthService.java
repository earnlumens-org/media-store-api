package org.earnlumens.mediastore.application.auth;

import org.earnlumens.mediastore.application.user.UserService;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.user.model.User;
import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Duration TEMP_UUID_TTL = Duration.ofMinutes(2);

    private final UserService userService;
    private final EntryRepository entryRepository;
    private final CollectionRepository collectionRepository;

    public AuthService(UserService userService, EntryRepository entryRepository,
                       CollectionRepository collectionRepository) {
        this.userService = userService;
        this.entryRepository = entryRepository;
        this.collectionRepository = collectionRepository;
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

            // Detect if username or avatar changed so we can update denormalized entry data
            boolean usernameChanged = !username.equals(user.getUsername());
            boolean avatarChanged = !profileImageUrl.equals(user.getProfileImageUrl());

            user.setDisplayName(displayName);
            user.setUsername(username);
            user.setProfileImageUrl(profileImageUrl);
            user.setFollowersCount(followersCount);
            user.setLastLoginAt(LocalDateTime.now());
            user.setTempUUID(tempUUID);
            user.setTempUUIDCreatedAt(Instant.now());
            userService.save(user);

            // Sync denormalized author info on all entries and collections when
            // username or avatar changes (collections are conceptually bound to
            // entries, so they share the same denormalized author card).
            //
            // ⚠️ MAINTAINER WARNING — denormalized author identity (username + avatar)
            // is the snapshot pattern used across the store so author cards render
            // without an extra User lookup. The User document is the source of truth;
            // every place that stores a COPY of the author's username/avatar must be
            // re-synced HERE on login, or it will go stale after a user renames /
            // changes their X profile photo.
            // Current denormalization sites (keep this list + the calls below in sync):
            //   - EntryEntity.authorUsername / authorUsernameLower / authorAvatarUrl
            //       → EntryUploadService.createEntry()  (write)
            //       → EntryRepository.updateAuthorInfoByUserId()  (backfill, below)
            //   - CollectionEntity.authorUsername / authorUsernameLower / authorAvatarUrl
            //       → CollectionService.createCollection()  (write)
            //       → CollectionRepository.updateAuthorInfoByUserId()  (backfill, below)
            // FUTURE FEATURES (comments/feedbacks, reviews, any user-generated card
            // that embeds the author's name/avatar): if you denormalize the author
            // there too, add a matching updateAuthorInfoByUserId() + a call below.
            // Prefer reading the live User instead of denormalizing when an extra
            // lookup is acceptable — that avoids this whole class of staleness.
            if (usernameChanged || avatarChanged) {
                String tenantId = TenantContext.require();
                long updatedEntries = entryRepository.updateAuthorInfoByUserId(tenantId, oauthUserId, username, profileImageUrl);
                long updatedCollections = collectionRepository.updateAuthorInfoByUserId(tenantId, oauthUserId, username, profileImageUrl);
                log.info("User {} changed profile info (username={}, avatar={}). Updated {} entries and {} collections in tenant={}.",
                        oauthUserId, usernameChanged, avatarChanged, updatedEntries, updatedCollections, tenantId);
            }
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

    /**
     * Lazily clears an expired temporary ban. Called from the login path so
     * a 7-day strike disappears on the user's first attempt after the window
     * elapses, without requiring a scheduled job. The banReason / banType /
     * strikeCount fields are intentionally retained for audit — only the
     * gating state is reset.
     */
    public void lazyUnblockExpired(User user) {
        if (!user.isBlocked() || user.getBanExpiresAt() == null) return;
        if (!user.getBanExpiresAt().isBefore(LocalDateTime.now())) return;
        user.setBlocked(false);
        user.setBanExpiresAt(null);
        user.setBlockedAt(null);
        user.setBlockedByRequestId(null);
        userService.save(user);
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
