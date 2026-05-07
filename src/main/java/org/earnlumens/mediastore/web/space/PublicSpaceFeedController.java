package org.earnlumens.mediastore.web.space;

import org.earnlumens.mediastore.application.media.PublicEntryService;
import org.earnlumens.mediastore.domain.media.dto.response.PublicEntryPageResponse;
import org.earnlumens.mediastore.domain.space.Space;
import org.earnlumens.mediastore.domain.space.SpaceStatus;
import org.earnlumens.mediastore.domain.space.repository.SpaceRepository;
import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Public, unauthenticated controller for browsing the contents of a
 * {@link Space}. Path lives under {@code /public/**} which is permitAll
 * in {@code WebSecurityConfig}.
 *
 * <p>Resolves the tenant from the request via {@link TenantContext} and
 * looks up the space inside that tenant — no cross-tenant access.
 */
@RestController
@RequestMapping("/public/spaces")
public class PublicSpaceFeedController {

    private final SpaceRepository spaceRepository;
    private final PublicEntryService publicEntryService;

    public PublicSpaceFeedController(SpaceRepository spaceRepository, PublicEntryService publicEntryService) {
        this.spaceRepository = spaceRepository;
        this.publicEntryService = publicEntryService;
    }

    /**
     * GET /public/spaces/{spaceId}/feed?page=0&size=48
     * <p>
     * Returns paginated PUBLISHED entries for the given space. 404 if the
     * space does not exist (within the resolved tenant) or is ARCHIVED.
     */
    @GetMapping("/{spaceId}/feed")
    public ResponseEntity<PublicEntryPageResponse> getSpaceFeed(
            @PathVariable("spaceId") String spaceId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "48") int size
    ) {
        String tenantId = TenantContext.require();

        Optional<Space> space = spaceRepository.findByTenantIdAndId(tenantId, spaceId);
        if (space.isEmpty() || space.get().getStatus() == SpaceStatus.ARCHIVED) {
            return ResponseEntity.notFound().build();
        }

        PublicEntryPageResponse response = publicEntryService.getSpaceFeed(tenantId, spaceId, page, size);
        return ResponseEntity.ok(response);
    }
}
