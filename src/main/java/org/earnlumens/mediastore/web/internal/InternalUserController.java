package org.earnlumens.mediastore.web.internal;

import org.earnlumens.mediastore.application.user.UserBadgeService;
import org.earnlumens.mediastore.domain.user.model.BadgeType;
import org.earnlumens.mediastore.domain.user.model.User;
import org.earnlumens.mediastore.domain.user.repository.UserRepository;
import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;

/**
 * Internal endpoints consumed by {@code admin-api} (service-to-service).
 * <p>
 * Protected by the shared secret header {@code X-Internal-Key} and a
 * constant-time compare to thwart timing oracles.
 *
 * <h3>Blue Credential check</h3>
 * {@code GET /api/internal/users/{oauthUserId}/blue-credential}
 * <p>
 * Returns whether the given OAuth-identified user currently holds an ACTIVE
 * {@link BadgeType#U1} badge in the resolved tenant. Admin-api calls this
 * endpoint to gate reward payouts (Claim Free Verification flow).
 * <p>
 * Fail-safe semantics: if the user has never signed in to media-store-api
 * (no {@code users} document), the response is {@code {"active": false}}
 * instead of 404 — this avoids leaking account existence and keeps the
 * admin-api flow simple.
 */
@RestController
@RequestMapping("/api/internal")
public class InternalUserController {

    private static final Logger logger = LoggerFactory.getLogger(InternalUserController.class);

    private final UserRepository userRepository;
    private final UserBadgeService userBadgeService;
    private final byte[] adminApiKey;

    public InternalUserController(
            UserRepository userRepository,
            UserBadgeService userBadgeService,
            @Value("${mediastore.internal.adminApiKey}") String adminApiKey
    ) {
        this.userRepository = userRepository;
        this.userBadgeService = userBadgeService;
        this.adminApiKey = adminApiKey == null ? new byte[0] : adminApiKey.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/users/{oauthUserId}/blue-credential")
    public ResponseEntity<?> blueCredential(
            @RequestHeader(value = "X-Internal-Key", required = false) String secret,
            @PathVariable String oauthUserId,
            @RequestParam(value = "tenantId", required = false) String tenantIdOverride
    ) {
        if (!isValidSecret(secret)) {
            logger.warn("InternalUserController: blueCredential rejected — invalid X-Internal-Key");
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        if (oauthUserId == null || oauthUserId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "oauthUserId is required"));
        }

        String tenantId = (tenantIdOverride != null && !tenantIdOverride.isBlank())
                ? tenantIdOverride
                : TenantContext.require();

        Optional<User> user = userRepository.findByOauthUserId(oauthUserId);
        if (user.isEmpty()) {
            // Fail-safe: user has never onboarded in media-store-api yet.
            return ResponseEntity.ok(Map.of("active", false));
        }

        boolean active = userBadgeService.hasActiveBadge(tenantId, user.get().getId(), BadgeType.U1);
        return ResponseEntity.ok(Map.of("active", active));
    }

    /** Constant-time comparison — avoids side-channel leaks on secret length / prefix. */
    private boolean isValidSecret(String provided) {
        if (provided == null || adminApiKey.length == 0) return false;
        byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(providedBytes, adminApiKey);
    }
}
