package org.earnlumens.mediastore.web.media;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.earnlumens.mediastore.application.media.CollectionService;
import org.earnlumens.mediastore.domain.media.dto.request.CreateCollectionRequest;
import org.earnlumens.mediastore.domain.media.dto.request.UpdateCollectionRequest;
import org.earnlumens.mediastore.domain.media.dto.response.CollectionPageResponse;
import org.earnlumens.mediastore.domain.media.dto.response.CollectionResponse;
import org.earnlumens.mediastore.infrastructure.tenant.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST controller for collection lifecycle management.
 * Authenticated via Bearer JWT.
 */
@RestController
@RequestMapping("/api/collections")
public class CollectionController {

    private static final Logger logger = LoggerFactory.getLogger(CollectionController.class);

    private final TenantResolver tenantResolver;
    private final CollectionService collectionService;

    public CollectionController(TenantResolver tenantResolver, CollectionService collectionService) {
        this.tenantResolver = tenantResolver;
        this.collectionService = collectionService;
    }

    /** POST /api/collections — Create a new DRAFT collection. */
    @PostMapping
    public ResponseEntity<?> createCollection(
            @Valid @RequestBody CreateCollectionRequest request,
            HttpServletRequest httpRequest) {

        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = tenantResolver.resolve(httpRequest);

        try {
            CollectionResponse response = collectionService.createCollection(tenantId, userId, request);
            return ResponseEntity.status(201).body(response);
        } catch (IllegalArgumentException e) {
            logger.warn("createCollection: invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** PUT /api/collections/{id} — Update collection metadata. */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateCollection(
            @PathVariable("id") String id,
            @Valid @RequestBody UpdateCollectionRequest request,
            HttpServletRequest httpRequest) {

        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = tenantResolver.resolve(httpRequest);

        boolean updated = collectionService.updateCollection(tenantId, userId, id, request);
        if (!updated) {
            return ResponseEntity.status(404).body(Map.of("error", "Collection not found"));
        }
        return ResponseEntity.ok(Map.of("message", "Collection updated"));
    }

    /** PATCH /api/collections/{id}/publish — Publish a DRAFT or ARCHIVED collection. */
    @PatchMapping("/{id}/publish")
    public ResponseEntity<?> publishCollection(
            @PathVariable("id") String id,
            HttpServletRequest httpRequest) {

        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = tenantResolver.resolve(httpRequest);

        try {
            boolean published = collectionService.publishCollection(tenantId, userId, id);
            if (!published) {
                return ResponseEntity.status(404).body(Map.of("error", "Collection not found"));
            }
            return ResponseEntity.ok(Map.of("message", "Collection published"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** PATCH /api/collections/{id}/archive — Archive a collection. */
    @PatchMapping("/{id}/archive")
    public ResponseEntity<?> archiveCollection(
            @PathVariable("id") String id,
            HttpServletRequest httpRequest) {

        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = tenantResolver.resolve(httpRequest);

        boolean archived = collectionService.archiveCollection(tenantId, userId, id);
        if (!archived) {
            return ResponseEntity.status(404).body(Map.of("error", "Collection not found"));
        }
        return ResponseEntity.ok(Map.of("message", "Collection archived"));
    }

    /** PATCH /api/collections/{id}/unarchive — Restore an archived collection to DRAFT. */
    @PatchMapping("/{id}/unarchive")
    public ResponseEntity<?> unarchiveCollection(
            @PathVariable("id") String id,
            HttpServletRequest httpRequest) {

        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = tenantResolver.resolve(httpRequest);

        try {
            boolean result = collectionService.unarchiveCollection(tenantId, userId, id);
            if (!result) {
                return ResponseEntity.status(404).body(Map.of("error", "Collection not found"));
            }
            return ResponseEntity.ok(Map.of("message", "Collection unarchived"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** DELETE /api/collections/{id} — Delete a DRAFT collection. */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteCollection(
            @PathVariable("id") String id,
            HttpServletRequest httpRequest) {

        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = tenantResolver.resolve(httpRequest);

        try {
            boolean deleted = collectionService.deleteCollection(tenantId, userId, id);
            if (!deleted) {
                return ResponseEntity.status(404).body(Map.of("error", "Collection not found"));
            }
            return ResponseEntity.ok(Map.of("message", "Collection deleted"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** POST /api/collections/{id}/items — Add an entry to a collection. */
    @PostMapping("/{id}/items")
    public ResponseEntity<?> addItem(
            @PathVariable("id") String id,
            @RequestBody Map<String, String> body,
            HttpServletRequest httpRequest) {

        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String entryId = body.get("entryId");
        if (entryId == null || entryId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "entryId is required"));
        }

        String tenantId = tenantResolver.resolve(httpRequest);

        try {
            boolean added = collectionService.addItem(tenantId, userId, id, entryId);
            if (!added) {
                return ResponseEntity.status(404).body(Map.of("error", "Collection not found"));
            }
            return ResponseEntity.ok(Map.of("message", "Item added"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** DELETE /api/collections/{id}/items/{entryId} — Remove an entry from a collection. */
    @DeleteMapping("/{id}/items/{entryId}")
    public ResponseEntity<?> removeItem(
            @PathVariable("id") String id,
            @PathVariable("entryId") String entryId,
            HttpServletRequest httpRequest) {

        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = tenantResolver.resolve(httpRequest);

        boolean removed = collectionService.removeItem(tenantId, userId, id, entryId);
        if (!removed) {
            return ResponseEntity.status(404).body(Map.of("error", "Collection or item not found"));
        }
        return ResponseEntity.ok(Map.of("message", "Item removed"));
    }

    /** PUT /api/collections/{id}/items/reorder — Reorder items in a collection. */
    @PutMapping("/{id}/items/reorder")
    public ResponseEntity<?> reorderItems(
            @PathVariable("id") String id,
            @RequestBody Map<String, List<String>> body,
            HttpServletRequest httpRequest) {

        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        List<String> entryIds = body.get("entryIds");
        if (entryIds == null || entryIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "entryIds is required"));
        }

        String tenantId = tenantResolver.resolve(httpRequest);

        try {
            boolean reordered = collectionService.reorderItems(tenantId, userId, id, entryIds);
            if (!reordered) {
                return ResponseEntity.status(404).body(Map.of("error", "Collection not found"));
            }
            return ResponseEntity.ok(Map.of("message", "Items reordered"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** GET /api/collections/mine?page=0&size=24 — List authenticated user's collections. */
    @GetMapping("/mine")
    public ResponseEntity<?> getMyCollections(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "24") int size,
            HttpServletRequest httpRequest) {

        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String tenantId = tenantResolver.resolve(httpRequest);

        CollectionPageResponse response = collectionService.getMyCollections(tenantId, userId, page, size);
        return ResponseEntity.ok(response);
    }

    private String extractUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof OAuth2User principal)) return null;
        Object idAttr = principal.getAttribute("id");
        return idAttr != null ? idAttr.toString() : null;
    }
}
