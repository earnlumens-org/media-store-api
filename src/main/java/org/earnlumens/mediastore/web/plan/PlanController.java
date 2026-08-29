package org.earnlumens.mediastore.web.plan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.earnlumens.mediastore.application.plan.PlanOrderService;
import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Two-phase Pro-plan purchase flow (custom-domain-upgrade 1B.2):
 *   POST /api/plan/prepare       — builds unsigned XDR (owner-only)
 *   POST /api/plan/submit        — accepts signed XDR, confirms on-chain (sync)
 *   GET  /api/plan/order/{id}    — owner-scoped order status
 *
 * All endpoints require authentication; ownership of the tenant is verified
 * server-side against the tenants read model. Rate-limited in the AUTH tier
 * (see RateLimitFilter).
 */
@RestController
@RequestMapping("/api/plan")
public class PlanController {

    private static final Logger logger = LoggerFactory.getLogger(PlanController.class);

    public record PreparePlanRequest(@NotBlank String period, @NotBlank String buyerWallet) {}
    public record SubmitPlanRequest(@NotBlank String orderId, @NotBlank String signedXdr) {}

    private final PlanOrderService planOrderService;

    public PlanController(PlanOrderService planOrderService) {
        this.planOrderService = planOrderService;
    }

    @PostMapping("/prepare")
    public ResponseEntity<?> prepare(@Valid @RequestBody PreparePlanRequest request) {
        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        String tenantId = TenantContext.require();
        try {
            return ResponseEntity.ok(planOrderService.prepare(
                    tenantId, userId, request.period(), request.buyerWallet()));
        } catch (IllegalArgumentException e) {
            logger.warn("Prepare plan failed (400): {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            logger.warn("Prepare plan failed (409): {}", e.getMessage());
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Prepare plan failed (500)", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal error preparing plan order"));
        }
    }

    @PostMapping("/submit")
    public ResponseEntity<?> submit(@Valid @RequestBody SubmitPlanRequest request) {
        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        String tenantId = TenantContext.require();
        try {
            return ResponseEntity.ok(planOrderService.submit(
                    tenantId, userId, request.orderId(), request.signedXdr()));
        } catch (IllegalArgumentException e) {
            logger.warn("Submit plan failed (400): {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            logger.warn("Submit plan failed (409): {}", e.getMessage());
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            logger.error("Submit plan failed (500): {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Plan payment submission failed"));
        }
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<?> orderStatus(@PathVariable String orderId) {
        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        String tenantId = TenantContext.require();
        try {
            return ResponseEntity.ok()
                    .header("Cache-Control", "private, no-store")
                    .body(planOrderService.getOrderStatus(tenantId, userId, orderId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", "Order not found"));
        }
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
