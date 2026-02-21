package org.earnlumens.mediastore.web.media;

import jakarta.servlet.http.HttpServletRequest;
import org.earnlumens.mediastore.application.media.PublicEntryService;
import org.earnlumens.mediastore.domain.media.dto.response.PublicEntryPageResponse;
import org.earnlumens.mediastore.domain.media.dto.response.PublicEntryResponse;
import org.earnlumens.mediastore.infrastructure.tenant.TenantResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public (no auth) controller for browsing published entries.
 * Path is under /public/** which is permitAll in WebSecurityConfig.
 */
@RestController
@RequestMapping("/public/entries")
public class PublicEntryController {

    private final TenantResolver tenantResolver;
    private final PublicEntryService publicEntryService;

    public PublicEntryController(TenantResolver tenantResolver, PublicEntryService publicEntryService) {
        this.tenantResolver = tenantResolver;
        this.publicEntryService = publicEntryService;
    }

    /**
     * GET /public/entries?page=0&size=48
     * Returns paginated PUBLISHED entries for the resolved tenant,
     * ordered by publishedAt descending.
     */
    @GetMapping
    public ResponseEntity<PublicEntryPageResponse> getPublishedEntries(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "48") int size,
            HttpServletRequest request
    ) {
        String tenantId = tenantResolver.resolve(request);
        PublicEntryPageResponse response = publicEntryService.getPublishedEntries(tenantId, page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /public/entries/{id}
     * Returns a single PUBLISHED entry by ID.
     * Returns 404 if the entry doesn't exist or is not published.
     */
    @GetMapping("/{id}")
    public ResponseEntity<PublicEntryResponse> getPublishedEntryById(
            @PathVariable String id,
            HttpServletRequest request
    ) {
        String tenantId = tenantResolver.resolve(request);
        return publicEntryService.getPublishedEntryById(tenantId, id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /public/entries/by-user/{username}?type=video&page=0&size=48
     * Returns paginated PUBLISHED entries for a specific author,
     * optionally filtered by type (video, audio, image, entry, file).
     */
    @GetMapping("/by-user/{username}")
    public ResponseEntity<PublicEntryPageResponse> getPublishedEntriesByUser(
            @PathVariable String username,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "48") int size,
            HttpServletRequest request
    ) {
        String tenantId = tenantResolver.resolve(request);
        PublicEntryPageResponse response = publicEntryService.getPublishedEntriesByUser(tenantId, username, type, page, size);
        return ResponseEntity.ok(response);
    }
}
