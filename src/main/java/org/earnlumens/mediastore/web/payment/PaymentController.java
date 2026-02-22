package org.earnlumens.mediastore.web.payment;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.earnlumens.mediastore.application.payment.PaymentService;
import org.earnlumens.mediastore.domain.media.dto.request.PreparePaymentRequest;
import org.earnlumens.mediastore.domain.media.dto.request.SubmitPaymentRequest;
import org.earnlumens.mediastore.domain.media.dto.response.PreparePaymentResponse;
import org.earnlumens.mediastore.domain.media.dto.response.SubmitPaymentResponse;
import org.earnlumens.mediastore.infrastructure.tenant.TenantResolver;
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
 *   POST /api/payments/prepare  — builds unsigned XDR, returns orderId + XDR
 *   POST /api/payments/submit   — accepts signed XDR, submits to Stellar, creates entitlement
 *
 * Both endpoints require authentication (refresh cookie / JWT).
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private static final Logger logger = LoggerFactory.getLogger(PaymentController.class);

    private final TenantResolver tenantResolver;
    private final PaymentService paymentService;

    public PaymentController(TenantResolver tenantResolver, PaymentService paymentService) {
        this.tenantResolver = tenantResolver;
        this.paymentService = paymentService;
    }

    @PostMapping("/prepare")
    public ResponseEntity<?> prepare(
            @Valid @RequestBody PreparePaymentRequest request,
            HttpServletRequest httpRequest
    ) {
        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = tenantResolver.resolve(httpRequest);

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

    @PostMapping("/submit")
    public ResponseEntity<?> submit(
            @Valid @RequestBody SubmitPaymentRequest request,
            HttpServletRequest httpRequest
    ) {
        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = tenantResolver.resolve(httpRequest);

        try {
            SubmitPaymentResponse response = paymentService.submit(tenantId, userId, request);
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
