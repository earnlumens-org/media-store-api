package org.earnlumens.mediastore.web.publishing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.earnlumens.mediastore.application.publishing.PublishingQueueService;
import org.earnlumens.mediastore.application.publishing.dto.QueueItemStatusView;
import org.earnlumens.mediastore.application.publishing.dto.SpaceQueuePreview;
import org.earnlumens.mediastore.domain.publishing.model.PublishingEntityType;
import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Publishing Block queue endpoints (authenticated, tenant-scoped):
 * <ul>
 *   <li>GET  /api/publishing/spaces — pre-enqueue preview per candidate space
 *       (which block, when it publishes, slots, waiting count, FastPass)</li>
 *   <li>POST /api/publishing/queue — enqueue an entity into 1..n space queues</li>
 *   <li>GET  /api/publishing/queue — live status of the entity's queue items</li>
 *   <li>DELETE /api/publishing/queue/{itemId} — cancel while the block is OPEN</li>
 * </ul>
 * All instants on the wire are epoch millis UTC.
 */
@RestController
@RequestMapping("/api/publishing")
public class PublishingQueueController {

    private static final Logger logger = LoggerFactory.getLogger(PublishingQueueController.class);

    private final PublishingQueueService queueService;

    public PublishingQueueController(PublishingQueueService queueService) {
        this.queueService = queueService;
    }

    public record EnqueueRequest(
            @NotBlank String entityType,
            @NotBlank String entityId,
            @NotEmpty List<String> spaceIds
    ) {}

    @GetMapping("/spaces")
    public ResponseEntity<?> previewSpaces(@RequestParam String entityType,
                                           @RequestParam String entityId) {
        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        String tenantId = TenantContext.require();
        try {
            List<SpaceQueuePreview> previews = queueService.previewSpaces(
                    tenantId, userId, parseType(entityType), entityId);
            return ResponseEntity.ok(Map.of("spaces", previews));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Publishing spaces preview failed (500)", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal error"));
        }
    }

    @PostMapping("/queue")
    public ResponseEntity<?> enqueue(@Valid @RequestBody EnqueueRequest request) {
        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        String tenantId = TenantContext.require();
        try {
            List<QueueItemStatusView> items = queueService.enqueue(
                    tenantId, userId, parseType(request.entityType()),
                    request.entityId(), request.spaceIds());
            return ResponseEntity.ok(Map.of("items", items));
        } catch (IllegalArgumentException e) {
            logger.warn("Publishing enqueue failed (400): {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            logger.warn("Publishing enqueue failed (409): {}", e.getMessage());
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Publishing enqueue failed (500)", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal error"));
        }
    }

    @GetMapping("/queue")
    public ResponseEntity<?> status(@RequestParam String entityType,
                                    @RequestParam String entityId) {
        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        String tenantId = TenantContext.require();
        try {
            List<QueueItemStatusView> items = queueService.getQueueStatus(
                    tenantId, userId, parseType(entityType), entityId);
            return ResponseEntity.ok(Map.of("items", items));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Publishing queue status failed (500)", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal error"));
        }
    }

    @DeleteMapping("/queue/{itemId}")
    public ResponseEntity<?> cancel(@PathVariable String itemId) {
        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        String tenantId = TenantContext.require();
        try {
            queueService.cancel(tenantId, userId, itemId);
            return ResponseEntity.ok(Map.of("cancelled", true));
        } catch (IllegalArgumentException e) {
            logger.warn("Publishing cancel failed (400): {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            logger.warn("Publishing cancel failed (409): {}", e.getMessage());
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Publishing cancel failed (500)", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal error"));
        }
    }

    private static PublishingEntityType parseType(String value) {
        try {
            return PublishingEntityType.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("ENTITY_TYPE_NOT_SUPPORTED");
        }
    }

    private String extractUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof OAuth2User oauth2User) {
            Object id = oauth2User.getAttribute("id");
            return id != null ? id.toString() : null;
        }
        return null;
    }
}
