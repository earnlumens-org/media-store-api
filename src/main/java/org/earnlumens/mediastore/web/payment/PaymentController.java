package org.earnlumens.mediastore.web.payment;

import jakarta.validation.Valid;
import org.earnlumens.mediastore.application.payment.PaymentService;
import org.earnlumens.mediastore.application.payment.PaymentSubmissionCoordinator;
import org.earnlumens.mediastore.domain.media.dto.request.PreparePaymentRequest;
import org.earnlumens.mediastore.domain.media.dto.request.PrepareTipRequest;
import org.earnlumens.mediastore.domain.media.dto.request.SubmitPaymentRequest;
import org.earnlumens.mediastore.domain.media.dto.response.PreparePaymentResponse;
import org.earnlumens.mediastore.domain.media.dto.response.SubmitPaymentResponse;
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
 * REST controller for the two-phase payment flow:
 *   POST /api/payments/prepare          — builds unsigned XDR, returns orderId + XDR
 *   POST /api/payments/submit           — accepts signed XDR; verifies and locks the order
 *                                         inline, then confirms on-chain asynchronously
 *                                         (202 + PROCESSING; poll the order status)
 *   GET  /api/payments/orders/{orderId} — buyer-owned order status (poll until final)
 *
 * All endpoints require authentication (refresh cookie / JWT).
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private static final Logger logger = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentService paymentService;
    private final PaymentSubmissionCoordinator submissionCoordinator;

    public PaymentController(PaymentService paymentService,
                             PaymentSubmissionCoordinator submissionCoordinator) {
        this.paymentService = paymentService;
        this.submissionCoordinator = submissionCoordinator;
    }

    @PostMapping("/prepare")
    public ResponseEntity<?> prepare(
            @Valid @RequestBody PreparePaymentRequest request
    ) {
        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = TenantContext.require();

        try {
            PreparePaymentResponse response = paymentService.prepare(tenantId, userId, request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            logger.warn("Prepare payment failed (400): {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            logger.warn("Prepare payment failed (409): {}", e.getMessage());
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Prepare payment failed (500)", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal error preparing payment"));
        }
    }

    /**
     * Prepare a TIP (voluntary creator support) payment. Same audited two-phase
     * flow as a purchase — the returned order is signed by the buyer's wallet and
     * settled via {@code POST /submit}. A tip grants no entitlement.
     */
    @PostMapping("/tip/prepare")
    public ResponseEntity<?> prepareTip(
            @Valid @RequestBody PrepareTipRequest request
    ) {
        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = TenantContext.require();

        try {
            PreparePaymentResponse response = paymentService.prepareTip(tenantId, userId, request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            logger.warn("Prepare tip failed (400): {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            logger.warn("Prepare tip failed (409): {}", e.getMessage());
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Prepare tip failed (500)", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal error preparing tip"));
        }
    }

    @PostMapping("/submit")
    public ResponseEntity<?> submit(
            @Valid @RequestBody SubmitPaymentRequest request
    ) {
        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = TenantContext.require();

        try {
            SubmitPaymentResponse response = submissionCoordinator.submit(tenantId, userId, request);
            // Async mode: the order is locked and verified, but the on-chain
            // confirmation continues in background — the client polls the order.
            if ("PROCESSING".equals(response.status())) {
                return ResponseEntity.accepted().body(response);
            }
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            logger.warn("Submit payment failed (400): {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            logger.warn("Submit payment failed (409): {}", e.getMessage());
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            logger.error("Submit payment failed (500): {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Payment submission failed"));
        }
    }

    /**
     * Status of the buyer's own order — polled by the frontend after a 202
     * submit until the order reaches a final state (COMPLETED / FAILED /
     * EXPIRED). Never cacheable: the whole point is freshness.
     */
    @GetMapping("/orders/{orderId}")
    public ResponseEntity<?> orderStatus(@PathVariable String orderId) {
        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = TenantContext.require();

        try {
            SubmitPaymentResponse response = paymentService.getOrderStatus(tenantId, userId, orderId);
            return ResponseEntity.ok()
                    .header("Cache-Control", "private, no-store")
                    .body(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", "Order not found"));
        }
    }

    /**
     * Extracts the user ID from the SecurityContext.
     */
    private String extractUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof OAuth2User principal)) {
            return null;
        }
        Object idAttr = principal.getAttribute("id");
        return idAttr != null ? idAttr.toString() : null;
    }
}
