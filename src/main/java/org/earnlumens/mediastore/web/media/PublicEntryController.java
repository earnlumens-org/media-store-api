package org.earnlumens.mediastore.web.media;

import jakarta.servlet.http.HttpServletRequest;
import org.earnlumens.mediastore.application.media.PublicEntryService;
import org.earnlumens.mediastore.domain.media.dto.response.PublicEntryPageResponse;
import org.earnlumens.mediastore.infrastructure.tenant.TenantResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
}
