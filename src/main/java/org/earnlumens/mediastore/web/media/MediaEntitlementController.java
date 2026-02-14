package org.earnlumens.mediastore.web.media;

import jakarta.servlet.http.HttpServletRequest;
import org.earnlumens.mediastore.application.media.MediaEntitlementService;
import org.earnlumens.mediastore.domain.media.dto.response.MediaEntitlementResponse;
import org.earnlumens.mediastore.infrastructure.tenant.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * Entitlement check endpoint called by the Cloudflare CDN Worker.
 * <p>
 * Authentication is handled by {@link org.earnlumens.mediastore.infrastructure.security.jwt.RefreshCookieAuthFilter}
 * which validates the refresh-cookie session and populates the {@link SecurityContextHolder}.
 * Spring Security's {@code .anyRequest().authenticated()} rule ensures unauthenticated
 * requests are rejected with 401 before reaching this controller.
 * <p>
 * The defensive null-check on the principal below is a safety net only.
 */
@RestController
@RequestMapping("/api/media")
public class MediaEntitlementController {

    private static final Logger logger = LoggerFactory.getLogger(MediaEntitlementController.class);

    private final TenantResolver tenantResolver;
    private final MediaEntitlementService mediaEntitlementService;

    public MediaEntitlementController(
            TenantResolver tenantResolver,
            MediaEntitlementService mediaEntitlementService
    ) {
        this.tenantResolver = tenantResolver;
        this.mediaEntitlementService = mediaEntitlementService;
    }

    @GetMapping("/entitlements/{entryId}")
    public ResponseEntity<?> checkEntitlement(
            @PathVariable String entryId,
            HttpServletRequest request
    ) {
        // 1. Extract userId from SecurityContext (set by RefreshCookieAuthFilter)
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof OAuth2User principal)) {
            // Defence-in-depth: Spring Security should have blocked this already
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        Object idAttr = principal.getAttribute("id");
        if (idAttr == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        String userId = idAttr.toString();

        // 2. Resolve tenant from Host header
        String tenantId = tenantResolver.resolve(request);

        // 3. Check entitlement (source of truth)
        //    allowed = (entry.tenantId == tenantId) AND (entry.ownerId == userId OR Entitlement ACTIVE)
        Optional<MediaEntitlementResponse> result =
                mediaEntitlementService.checkEntitlement(tenantId, userId, entryId);

        if (result.isEmpty()) {
            logger.debug("Media entitlement denied: tenantId={}, userId={}, entryId={}", tenantId, userId, entryId);
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        return ResponseEntity.ok(result.get());
    }
}
