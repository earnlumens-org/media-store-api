package org.earnlumens.mediastore.web.subscription;

import jakarta.servlet.http.HttpServletRequest;
import org.earnlumens.mediastore.application.subscription.SubscriptionService;
import org.earnlumens.mediastore.application.subscription.SubscriptionService.SubscriptionPageResponse;
import org.earnlumens.mediastore.domain.user.model.User;
import org.earnlumens.mediastore.domain.user.repository.UserRepository;
import org.earnlumens.mediastore.infrastructure.tenant.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * REST controller for subscription operations.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /api/subscriptions/{targetUserId} — subscribe to a user</li>
 *   <li>DELETE /api/subscriptions/{targetUserId} — unsubscribe from a user</li>
 *   <li>GET /api/subscriptions/check/{targetUserId} — check if subscribed</li>
 *   <li>GET /api/subscriptions/mine — list my subscriptions (who I follow)</li>
 *   <li>GET /api/subscriptions/subscribers — list my subscribers (who follows me, private)</li>
 *   <li>GET /api/subscriptions/subscribers/count — my subscriber count</li>
 *   <li>GET /public/subscriptions/count/{username} — public subscriber count for any user</li>
 * </ul>
 * </p>
 */
@RestController
public class SubscriptionController {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionController.class);

    private final SubscriptionService subscriptionService;
    private final UserRepository userRepository;
    private final TenantResolver tenantResolver;

    public SubscriptionController(SubscriptionService subscriptionService,
                                  UserRepository userRepository,
                                  TenantResolver tenantResolver) {
        this.subscriptionService = subscriptionService;
        this.userRepository = userRepository;
        this.tenantResolver = tenantResolver;
    }

    // ── Subscribe ───────────────────────────────────────────

    @PostMapping("/api/subscriptions/{targetUserId}")
    public ResponseEntity<?> subscribe(
            @PathVariable("targetUserId") String targetUserId,
            HttpServletRequest request) {
        String userId = extractUserId();
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        String tenantId = tenantResolver.resolve(request);

        try {
            boolean created = subscriptionService.subscribe(tenantId, userId, targetUserId);
            return ResponseEntity.ok(Map.of("subscribed", true, "created", created));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error subscribing userId={} to targetUserId={}: {}", userId, targetUserId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to subscribe"));
        }
    }

    // ── Unsubscribe ─────────────────────────────────────────

    @DeleteMapping("/api/subscriptions/{targetUserId}")
    public ResponseEntity<?> unsubscribe(
            @PathVariable("targetUserId") String targetUserId,
            HttpServletRequest request) {
        String userId = extractUserId();
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        String tenantId = tenantResolver.resolve(request);

        try {
            subscriptionService.unsubscribe(tenantId, userId, targetUserId);
            return ResponseEntity.ok(Map.of("subscribed", false));
        } catch (Exception e) {
            logger.error("Error unsubscribing userId={} from targetUserId={}: {}", userId, targetUserId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to unsubscribe"));
        }
    }

    // ── Check subscription status ───────────────────────────

    @GetMapping("/api/subscriptions/check/{targetUserId}")
    public ResponseEntity<?> checkSubscription(
            @PathVariable("targetUserId") String targetUserId,
            HttpServletRequest request) {
        String userId = extractUserId();
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        String tenantId = tenantResolver.resolve(request);

        try {
            boolean subscribed = subscriptionService.isSubscribed(tenantId, userId, targetUserId);
            return ResponseEntity.ok(Map.of("subscribed", subscribed));
        } catch (Exception e) {
            logger.error("Error checking subscription userId={} targetUserId={}: {}", userId, targetUserId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to check subscription"));
        }
    }

    // ── My subscriptions (who I follow) ─────────────────────

    @GetMapping("/api/subscriptions/mine")
    public ResponseEntity<?> listMySubscriptions(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "24") int size,
            HttpServletRequest request) {
        String userId = extractUserId();
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        String tenantId = tenantResolver.resolve(request);

        try {
            SubscriptionPageResponse response = subscriptionService.listMySubscriptions(tenantId, userId, page, size);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error listing subscriptions for userId={}: {}", userId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to list subscriptions"));
        }
    }

    // ── My subscribers (who follows me — PRIVATE) ───────────

    @GetMapping("/api/subscriptions/subscribers")
    public ResponseEntity<?> listMySubscribers(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "24") int size,
            HttpServletRequest request) {
        String userId = extractUserId();
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        String tenantId = tenantResolver.resolve(request);

        try {
            SubscriptionPageResponse response = subscriptionService.listMySubscribers(tenantId, userId, page, size);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error listing subscribers for userId={}: {}", userId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to list subscribers"));
        }
    }

    // ── My subscriber count (private) ───────────────────────

    @GetMapping("/api/subscriptions/subscribers/count")
    public ResponseEntity<?> mySubscriberCount(HttpServletRequest request) {
        String userId = extractUserId();
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        String tenantId = tenantResolver.resolve(request);

        try {
            long count = subscriptionService.getSubscriberCount(tenantId, userId);
            return ResponseEntity.ok(Map.of("count", count));
        } catch (Exception e) {
            logger.error("Error counting subscribers for userId={}: {}", userId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to count subscribers"));
        }
    }

    // ── Public subscriber count by username ─────────────────

    @GetMapping("/public/subscriptions/count/{username}")
    public ResponseEntity<?> publicSubscriberCount(
            @PathVariable("username") String username,
            HttpServletRequest request) {
        if (!StringUtils.hasText(username)) {
            return ResponseEntity.badRequest().body(Map.of("error", "username is required"));
        }
        String tenantId = tenantResolver.resolve(request);

        try {
            Optional<User> userOpt = userRepository.findByUsername(username);
            if (userOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("error", "User not found"));
            }
            long count = subscriptionService.getSubscriberCount(tenantId, userOpt.get().getId());
            return ResponseEntity.ok(Map.of("username", username, "count", count));
        } catch (Exception e) {
            logger.error("Error getting public subscriber count for username={}: {}", username, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to get subscriber count"));
        }
    }

    // ── Helper ──────────────────────────────────────────────

    private String extractUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof OAuth2User principal)) return null;
        Object idAttr = principal.getAttribute("id");
        return idAttr != null ? idAttr.toString() : null;
    }
}
