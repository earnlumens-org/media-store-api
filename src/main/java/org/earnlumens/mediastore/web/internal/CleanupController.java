package org.earnlumens.mediastore.web.internal;

import org.earnlumens.mediastore.application.media.DraftCleanupService;
import org.earnlumens.mediastore.domain.media.dto.response.CleanupResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Internal endpoint for scheduled maintenance tasks.
 * Protected by a shared secret header — intended to be called by Cloud Scheduler only.
 */
@RestController
@RequestMapping("/api/internal")
public class CleanupController {

    private static final Logger logger = LoggerFactory.getLogger(CleanupController.class);

    private final DraftCleanupService draftCleanupService;
    private final String cleanupSecret;

    public CleanupController(
            DraftCleanupService draftCleanupService,
            @Value("${mediastore.internal.cleanupSecret}") String cleanupSecret
    ) {
        this.draftCleanupService = draftCleanupService;
        this.cleanupSecret = cleanupSecret;
    }

    /**
     * POST /api/internal/cleanup — Delete orphaned DRAFT entries with no assets.
     * Requires header: X-Cleanup-Secret matching the configured secret.
     */
    @PostMapping("/cleanup")
    public ResponseEntity<?> cleanup(
            @RequestHeader(value = "X-Cleanup-Secret", required = false) String secret
    ) {
        if (secret == null || !cleanupSecret.equals(secret)) {
            logger.warn("Cleanup: rejected — invalid or missing X-Cleanup-Secret");
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        CleanupResult result = draftCleanupService.cleanOrphanedDrafts();
        return ResponseEntity.ok(result);
    }
}
