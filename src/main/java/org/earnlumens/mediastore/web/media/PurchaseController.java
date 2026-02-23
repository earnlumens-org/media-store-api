package org.earnlumens.mediastore.web.media;

import jakarta.servlet.http.HttpServletRequest;
import org.earnlumens.mediastore.application.media.PurchaseListService;
import org.earnlumens.mediastore.domain.media.dto.response.PurchasedEntryPageResponse;
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
 * REST controller for listing a user's purchased content.
 * Requires authentication (Bearer token).
 */
@RestController
@RequestMapping("/api/purchases")
public class PurchaseController {

    private static final Logger logger = LoggerFactory.getLogger(PurchaseController.class);
    private final TenantResolver tenantResolver;
    private final PurchaseListService purchaseListService;

    public PurchaseController(TenantResolver tenantResolver,
                              PurchaseListService purchaseListService) {
        this.tenantResolver = tenantResolver;
        this.purchaseListService = purchaseListService;
    }

    /**
     * GET /api/purchases?page=0&size=24
     * Returns a paginated list of entries the authenticated user has purchased.
     */
    @GetMapping
    public ResponseEntity<?> listPurchases(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size,
            HttpServletRequest request) {

        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = tenantResolver.resolve(request);

        try {
            PurchasedEntryPageResponse response =
                    purchaseListService.listPurchases(tenantId, userId, page, size);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error listing purchases for userId={}: {}", userId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to list purchases"));
        }
    }

    private String extractUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof OAuth2User principal)) return null;
        Object idAttr = principal.getAttribute("id");
        return idAttr != null ? idAttr.toString() : null;
    }
}
