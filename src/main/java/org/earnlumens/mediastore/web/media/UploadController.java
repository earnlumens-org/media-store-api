package org.earnlumens.mediastore.web.media;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.earnlumens.mediastore.application.media.EntryUploadService;
import org.earnlumens.mediastore.domain.media.dto.request.FinalizeUploadRequest;
import org.earnlumens.mediastore.domain.media.dto.request.InitUploadRequest;
import org.earnlumens.mediastore.domain.media.dto.response.FinalizeUploadResponse;
import org.earnlumens.mediastore.domain.media.dto.response.InitUploadResponse;
import org.earnlumens.mediastore.infrastructure.tenant.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * REST controller for upload operations (presigned URL generation &amp; finalization).
 * <p>
 * Authenticated via Bearer JWT (AuthTokenFilter).
 */
@RestController
@RequestMapping("/api/uploads")
public class UploadController {

    private static final Logger logger = LoggerFactory.getLogger(UploadController.class);

    private final TenantResolver tenantResolver;
    private final EntryUploadService entryUploadService;

    public UploadController(TenantResolver tenantResolver, EntryUploadService entryUploadService) {
        this.tenantResolver = tenantResolver;
        this.entryUploadService = entryUploadService;
    }

    /**
     * POST /api/uploads/init — Generate a presigned PUT URL for direct upload to R2.
     */
    @PostMapping("/init")
    public ResponseEntity<?> initUpload(
            @Valid @RequestBody InitUploadRequest request,
            HttpServletRequest httpRequest
    ) {
        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = tenantResolver.resolve(httpRequest);

        try {
            Optional<InitUploadResponse> result = entryUploadService.initUpload(tenantId, userId, request);
            if (result.isEmpty()) {
                return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
            }
            return ResponseEntity.ok(result.get());
        } catch (IllegalArgumentException e) {
            logger.warn("initUpload: invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/uploads/finalize — Persist asset record after upload completes.
     */
    @PostMapping("/finalize")
    public ResponseEntity<?> finalizeUpload(
            @Valid @RequestBody FinalizeUploadRequest request,
            HttpServletRequest httpRequest
    ) {
        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = tenantResolver.resolve(httpRequest);

        try {
            Optional<FinalizeUploadResponse> result =
                    entryUploadService.finalizeUpload(tenantId, userId, request);
            if (result.isEmpty()) {
                return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
            }
            return ResponseEntity.ok(result.get());
        } catch (IllegalArgumentException e) {
            logger.warn("finalizeUpload: invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
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
