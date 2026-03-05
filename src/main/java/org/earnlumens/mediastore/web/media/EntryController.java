package org.earnlumens.mediastore.web.media;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.earnlumens.mediastore.application.media.EntryUploadService;
import org.earnlumens.mediastore.domain.media.dto.request.CreateEntryRequest;
import org.earnlumens.mediastore.domain.media.dto.request.UpdateEntryMetadataRequest;
import org.earnlumens.mediastore.domain.media.dto.request.UpdateEntryStatusRequest;
import org.earnlumens.mediastore.domain.media.dto.response.CreateEntryResponse;
import org.earnlumens.mediastore.domain.media.dto.response.OwnerEntryPageResponse;
import org.earnlumens.mediastore.domain.media.dto.response.OwnerStatsResponse;
import org.earnlumens.mediastore.infrastructure.tenant.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.util.Map;

/**
 * REST controller for entry lifecycle management.
 * <p>
 * Authenticated via Bearer JWT (AuthTokenFilter).
 */
@RestController
@RequestMapping("/api/entries")
public class EntryController {

    private static final Logger logger = LoggerFactory.getLogger(EntryController.class);

    private final TenantResolver tenantResolver;
    private final EntryUploadService entryUploadService;

    public EntryController(TenantResolver tenantResolver, EntryUploadService entryUploadService) {
        this.tenantResolver = tenantResolver;
        this.entryUploadService = entryUploadService;
    }

    /**
     * POST /api/entries — Create a new DRAFT entry.
     */
    @PostMapping
    public ResponseEntity<?> createEntry(
            @Valid @RequestBody CreateEntryRequest request,
            HttpServletRequest httpRequest
    ) {
        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = tenantResolver.resolve(httpRequest);

        try {
            CreateEntryResponse response = entryUploadService.createEntry(tenantId, userId, request);
            return ResponseEntity.status(201).body(response);
        } catch (IllegalArgumentException e) {
            logger.warn("createEntry: invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * PUT /api/entries/{id} — Update entry metadata (title, description, price).
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateEntryMetadata(
            @PathVariable("id") String id,
            @RequestBody UpdateEntryMetadataRequest request,
            HttpServletRequest httpRequest
    ) {
        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = tenantResolver.resolve(httpRequest);
        boolean updated = entryUploadService.updateEntryMetadata(tenantId, userId, id, request);
        if (!updated) {
            return ResponseEntity.status(404).body(Map.of("error", "Entry not found or not owned"));
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * PATCH /api/entries/{id}/status — Update entry status (e.g. DRAFT → IN_REVIEW).
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateEntryStatus(
            @PathVariable("id") String id,
            @Valid @RequestBody UpdateEntryStatusRequest request,
            HttpServletRequest httpRequest
    ) {
        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = tenantResolver.resolve(httpRequest);

        try {
            boolean updated = entryUploadService.updateEntryStatus(tenantId, userId, id, request);
            if (!updated) {
                return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
            }
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            logger.warn("updateEntryStatus: invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * PATCH /api/entries/{id}/unarchive — Restore an archived entry to its previous status.
     */
    @PatchMapping("/{id}/unarchive")
    public ResponseEntity<?> unarchiveEntry(
            @PathVariable("id") String id,
            HttpServletRequest httpRequest
    ) {
        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = tenantResolver.resolve(httpRequest);
        boolean unarchived = entryUploadService.unarchiveEntry(tenantId, userId, id);
        if (!unarchived) {
            return ResponseEntity.status(404).body(Map.of("error", "Entry not found, not owned, or not archived"));
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /api/entries/mine/stats — Aggregated dashboard stats for the authenticated creator.
     */
    @GetMapping("/mine/stats")
    public ResponseEntity<?> getMyStats(HttpServletRequest httpRequest) {
        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = tenantResolver.resolve(httpRequest);
        OwnerStatsResponse stats = entryUploadService.getOwnerStats(tenantId, userId);
        return ResponseEntity.ok(stats);
    }

    /**
     * GET /api/entries/mine — List entries owned by the authenticated user.
     * Supports optional status and type filters.
     */
    @GetMapping("/mine")
    public ResponseEntity<?> getMyEntries(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            HttpServletRequest httpRequest
    ) {
        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = tenantResolver.resolve(httpRequest);
        OwnerEntryPageResponse response = entryUploadService.getEntriesByOwner(
                tenantId, userId, status, type, page, size);
        return ResponseEntity.ok(response);
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
