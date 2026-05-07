package org.earnlumens.mediastore.web.space;

import org.earnlumens.mediastore.domain.space.Space;
import org.earnlumens.mediastore.domain.space.SpaceStatus;
import org.earnlumens.mediastore.domain.space.repository.SpaceRepository;
import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.earnlumens.mediastore.web.space.dto.PublicSpaceResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Public, unauthenticated controller for reading {@link Space} metadata.
 * Lives under {@code /public/**} (permitAll in WebSecurityConfig) and is
 * tenant-scoped via {@link TenantContext}.
 *
 * <p>Companion to {@code PublicSpaceFeedController}, which serves the
 * entries inside a space.
 */
@RestController
@RequestMapping("/public/spaces")
public class PublicSpaceController {

    private final SpaceRepository spaceRepository;

    public PublicSpaceController(SpaceRepository spaceRepository) {
        this.spaceRepository = spaceRepository;
    }

    /**
     * GET /public/spaces — sidebar listing for the current tenant.
     * Returns ACTIVE spaces with {@code showInSidebar=true} ordered by
     * {@code sortOrder} ascending. The system Explore space is included
     * here too (its {@code key="explore"} lets the UI render the
     * localised title from the global bundle).
     */
    @GetMapping
    public ResponseEntity<List<PublicSpaceResponse>> listSidebarSpaces() {
        String tenantId = TenantContext.require();
        List<Space> spaces = spaceRepository.findSidebarSpaces(tenantId);
        return ResponseEntity.ok(spaces.stream().map(PublicSpaceController::toResponse).toList());
    }

    /**
     * GET /public/spaces/{spaceId} — single space metadata. Returns 404 if
     * the space does not exist within the resolved tenant or has been
     * ARCHIVED.
     */
    @GetMapping("/{spaceId}")
    public ResponseEntity<PublicSpaceResponse> getById(@PathVariable("spaceId") String spaceId) {
        String tenantId = TenantContext.require();
        return spaceRepository.findByTenantIdAndId(tenantId, spaceId)
                .filter(s -> s.getStatus() != SpaceStatus.ARCHIVED)
                .map(PublicSpaceController::toResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    private static PublicSpaceResponse toResponse(Space s) {
        return new PublicSpaceResponse(
                s.getId(),
                s.getKey(),
                s.isSystemSpace(),
                s.getSortOrder(),
                s.getIcon(),
                s.getBaseName(),
                s.getTranslations() == null ? Map.of() : s.getTranslations()
        );
    }
}
