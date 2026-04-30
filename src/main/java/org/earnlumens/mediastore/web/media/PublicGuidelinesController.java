package org.earnlumens.mediastore.web.media;

import jakarta.servlet.http.HttpServletRequest;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.ModerationConfigMongoRepository;
import org.earnlumens.mediastore.infrastructure.tenant.TenantResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public (no auth) read of tenant-specific guideline notes.
 *
 * <p>Resolves the tenant from the host (subdomain) via {@link TenantResolver}
 * — the same mechanism the storefront uses everywhere — and returns the
 * tenant's English-only publishing notes if any. Empty / null when the
 * tenant has not configured them, in which case the storefront shows the
 * platform-wide rules only.
 *
 * <p>Path is under {@code /public/**} which is permitAll in WebSecurityConfig.
 */
@RestController
@RequestMapping("/public/guidelines")
public class PublicGuidelinesController {

    private final TenantResolver tenantResolver;
    private final ModerationConfigMongoRepository configRepository;

    public PublicGuidelinesController(TenantResolver tenantResolver,
                                      ModerationConfigMongoRepository configRepository) {
        this.tenantResolver = tenantResolver;
        this.configRepository = configRepository;
    }

    /**
     * GET /public/guidelines/tenant-notes
     * Returns {@code { tenantId, notes }} where notes is the tenant's
     * publishing notes string or null when unset.
     */
    @GetMapping("/tenant-notes")
    public ResponseEntity<Map<String, Object>> getTenantNotes(HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String notes = configRepository.findByTenantId(tenantId)
                .map(c -> c.getTenantPublishingNotes())
                .filter(s -> s != null && !s.isBlank())
                .orElse(null);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("tenantId", tenantId);
        body.put("notes", notes);
        return ResponseEntity.ok(body);
    }
}
