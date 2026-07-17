package org.earnlumens.mediastore.web.media;

import jakarta.servlet.http.HttpServletRequest;
import org.earnlumens.mediastore.application.media.OriginalAttributionService;
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
 * REST API for Original First ownership claims.
 *
 * <ul>
 *   <li>{@code POST /api/originals/claim/{entryId}} — "Claim as Original":
 *       fully automatic ownership review for the fingerprint group the entry
 *       belongs to. No tickets, no manual moderation — a deterministic score
 *       decides, and the full breakdown is returned for transparency.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/originals")
public class OriginalsController {

    private static final Logger logger = LoggerFactory.getLogger(OriginalsController.class);

    private final TenantResolver tenantResolver;
    private final OriginalAttributionService originalAttributionService;

    public OriginalsController(TenantResolver tenantResolver,
                               OriginalAttributionService originalAttributionService) {
        this.tenantResolver = tenantResolver;
        this.originalAttributionService = originalAttributionService;
    }

    /**
     * File an ownership claim from any entry of a fingerprint group.
     * The claimant must own an entry with the same content fingerprint.
     */
    @PostMapping("/claim/{entryId}")
    public ResponseEntity<?> claimAsOriginal(
            @PathVariable("entryId") String entryId,
            HttpServletRequest request) {

        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        String tenantId = tenantResolver.resolve(request);

        try {
            OriginalAttributionService.ClaimResult result =
                    originalAttributionService.claimAsOriginal(tenantId, userId, entryId);

            logger.info("claimAsOriginal: tenant={}, entry={}, user={}, granted={}",
                    tenantId, entryId, userId, result.granted());
            return ResponseEntity.ok(Map.of(
                    "granted", result.granted(),
                    "claimantScore", result.claimantScore(),
                    "holderScore", result.holderScore(),
                    "scoreBreakdown", result.scoreBreakdown(),
                    "originalEntryId", result.newOriginalEntryId()
            ));
        } catch (IllegalArgumentException e) {
            // ENTRY_NOT_FOUND / NO_FINGERPRINT / NO_MATCHING_ENTRY / ALREADY_ORIGINAL
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            String msg = e.getMessage();
            if ("DAILY_CLAIM_LIMIT_REACHED".equals(msg)) {
                return ResponseEntity.status(429).body(Map.of("error", msg));
            }
            // ALREADY_CLAIMED
            return ResponseEntity.status(409).body(Map.of("error", msg));
        }
    }

    private String extractUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof OAuth2User principal)) return null;
        Object idAttr = principal.getAttribute("id");
        return idAttr != null ? idAttr.toString() : null;
    }
}
