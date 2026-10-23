package org.earnlumens.mediastore.web.media;

import jakarta.servlet.http.HttpServletRequest;
import org.earnlumens.mediastore.application.media.MediaEntitlementService;
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

/**
 * Entitlement check endpoint called by the Cloudflare CDN Worker.
 * <p>
 * Authentication is handled by {@link org.earnlumens.mediastore.infrastructure.security.jwt.RefreshCookieAuthFilter}
 * which validates the refresh-cookie session and populates the {@link SecurityContextHolder}.
 * <p>
 * This endpoint is accessible without authentication (permitAll) so that free
 * content can be served to unauthenticated users. The service layer enforces
 * that paid content still requires a valid session.
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
            @PathVariable("entryId") String entryId,
            HttpServletRequest request
    ) {
        // 1. Extract userId from SecurityContext (set by RefreshCookieAuthFilter)
        //    userId is null for unauthenticated requests — free content is still accessible.
        String userId = null;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof OAuth2User principal) {
            Object idAttr = principal.getAttribute("id");
            if (idAttr != null) {
                userId = idAttr.toString();
            }
        }

        // 2. Resolve tenant from Host header
        String tenantId = tenantResolver.resolve(request);

        // 3. Check entitlement (source of truth: ContentAccessPolicy, shared
        //    with the public detail page — H3). Every denial is a 403 for the
        //    edge; the reason is exposed so the worker/UI can tell a takedown
        //    (BLOCKED) from a paywall (LOCKED) or a hidden entry (HIDDEN).
        MediaEntitlementService.Check check =
                mediaEntitlementService.check(tenantId, userId, entryId);

        if (check.response().isEmpty()) {
            logger.debug("Media entitlement denied ({}): tenantId={}, userId={}, entryId={}",
                    check.decision(), tenantId, userId, entryId);
            return ResponseEntity.status(403).body(Map.of(
                    "error", "Forbidden",
                    "reason", check.decision().name()));
        }

        return ResponseEntity.ok(check.response().get());
    }
}
