package org.earnlumens.mediastore.web.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.earnlumens.mediastore.application.plan.PlanOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Internal Pro-plan purchase surface for admin-api (custom-domain-upgrade 1D).
 *
 * <p>The owner buys the plan from admin-ui, which authenticates against
 * admin-api — not against this service. admin-api verifies tenant ownership
 * against its own database and forwards the request here server-to-server,
 * asserting the owner's OAuth user id. This controller re-verifies that
 * assertion against the tenants read model inside {@link PlanOrderService}
 * (defence in depth), so a forged {@code ownerOauthUserId} still cannot buy
 * a plan for a tenant it does not own.
 *
 * <p><b>Security:</b> mounted under {@code /api/internal/**} (permitAll,
 * exempt from edge auth — reached directly on {@code .run.app}); gated by the
 * shared {@code X-Plan-Secret} ({@code plan-apply-secret} in Secret Manager,
 * the same trust pair used for the reverse apply callback), compared in
 * constant time and fail-closed while unconfigured.
 */
@RestController
@RequestMapping("/api/internal/plan")
public class PlanInternalController {

    private static final Logger logger = LoggerFactory.getLogger(PlanInternalController.class);
    private static final String SECRET_PLACEHOLDER = "CHANGE_ME_IN_ENV";

    public record InternalPrepareRequest(@NotBlank String tenantId, @NotBlank String ownerOauthUserId,
                                         @NotBlank String period, @NotBlank String buyerWallet) {}
    public record InternalSubmitRequest(@NotBlank String tenantId, @NotBlank String ownerOauthUserId,
                                        @NotBlank String orderId, @NotBlank String signedXdr) {}
    public record InternalOrderStatusRequest(@NotBlank String tenantId, @NotBlank String ownerOauthUserId,
                                             @NotBlank String orderId) {}

    private final PlanOrderService planOrderService;
    private final String sharedSecret;

    public PlanInternalController(PlanOrderService planOrderService,
                                  @Value("${mediastore.internal.planSecret:}") String sharedSecret) {
        this.planOrderService = planOrderService;
        this.sharedSecret = sharedSecret == null ? "" : sharedSecret;
    }

    @PostMapping("/prepare")
    public ResponseEntity<?> prepare(
            @RequestHeader(value = "X-Plan-Secret", required = false) String presented,
            @Valid @RequestBody InternalPrepareRequest req) {
        ResponseEntity<?> rejection = authorize(presented);
        if (rejection != null) return rejection;
        try {
            return ResponseEntity.ok(planOrderService.prepare(
                    req.tenantId(), req.ownerOauthUserId(), req.period(), req.buyerWallet()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Internal plan prepare failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal error preparing plan order"));
        }
    }

    @PostMapping("/submit")
    public ResponseEntity<?> submit(
            @RequestHeader(value = "X-Plan-Secret", required = false) String presented,
            @Valid @RequestBody InternalSubmitRequest req) {
        ResponseEntity<?> rejection = authorize(presented);
        if (rejection != null) return rejection;
        try {
            return ResponseEntity.ok(planOrderService.submit(
                    req.tenantId(), req.ownerOauthUserId(), req.orderId(), req.signedXdr()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            logger.error("Internal plan submit failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Plan payment submission failed"));
        }
    }

    /** POST (not GET) so the owner assertion travels in the body like its siblings. */
    @PostMapping("/order-status")
    public ResponseEntity<?> orderStatus(
            @RequestHeader(value = "X-Plan-Secret", required = false) String presented,
            @Valid @RequestBody InternalOrderStatusRequest req) {
        ResponseEntity<?> rejection = authorize(presented);
        if (rejection != null) return rejection;
        try {
            return ResponseEntity.ok(planOrderService.getOrderStatus(
                    req.tenantId(), req.ownerOauthUserId(), req.orderId()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", "Order not found"));
        }
    }

    private ResponseEntity<?> authorize(String presented) {
        if (sharedSecret.isBlank() || SECRET_PLACEHOLDER.equals(sharedSecret)) {
            logger.error("Internal plan endpoint: refusing — mediastore.internal.planSecret is not configured");
            return ResponseEntity.status(503).body(Map.of("error", "plan_purchase_disabled"));
        }
        if (presented == null || !MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                sharedSecret.getBytes(StandardCharsets.UTF_8))) {
            logger.warn("Internal plan endpoint: rejected — invalid or missing X-Plan-Secret");
            return ResponseEntity.status(403).body(Map.of("error", "forbidden"));
        }
        return null;
    }
}
