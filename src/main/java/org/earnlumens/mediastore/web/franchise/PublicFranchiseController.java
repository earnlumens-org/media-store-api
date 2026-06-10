package org.earnlumens.mediastore.web.franchise;

import org.earnlumens.mediastore.infrastructure.franchise.read.FranchiseReadModel;
import org.earnlumens.mediastore.infrastructure.franchise.read.FranchiseReadRepository;
import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public (no auth) read of a tenant's franchises, used by the storefront to
 * render a franchise page at {@code <subdomain>.earnlumens.org/f/<slug>} with
 * its branding override, and to list the franchises operating under a tenant.
 *
 * <p>The tenant is taken from {@link TenantContext} (set per-request from the
 * host by the tenant filter), so a franchise is always resolved within its
 * franchisor and can never be addressed across tenants. Only ACTIVE franchises
 * are exposed; a disabled franchise returns 404 so its storefront disappears.
 *
 * <p>Path is under {@code /public/**} which is permitAll in WebSecurityConfig.
 */
@RestController
@RequestMapping("/public/franchises")
public class PublicFranchiseController {

    private static final String ACTIVE = "ACTIVE";

    private final FranchiseReadRepository repository;

    public PublicFranchiseController(FranchiseReadRepository repository) {
        this.repository = repository;
    }

    /** All ACTIVE franchises operating under the current tenant. */
    @GetMapping
    public List<PublicFranchiseResponse> list() {
        String tenantId = TenantContext.require();
        return repository.findByTenantIdAndStatus(tenantId, ACTIVE).stream()
                .map(PublicFranchiseResponse::of)
                .toList();
    }

    /** A single ACTIVE franchise by slug, or 404 if missing / disabled. */
    @GetMapping("/{slug}")
    public ResponseEntity<PublicFranchiseResponse> getBySlug(@PathVariable String slug) {
        String tenantId = TenantContext.require();
        return repository.findByTenantIdAndSlug(tenantId, slug == null ? null : slug.trim().toLowerCase())
                .filter(FranchiseReadModel::isActive)
                .map(PublicFranchiseResponse::of)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
