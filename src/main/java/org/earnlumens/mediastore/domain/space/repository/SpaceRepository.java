package org.earnlumens.mediastore.domain.space.repository;

import org.earnlumens.mediastore.domain.space.Space;

import java.util.List;
import java.util.Optional;

/**
 * Read-only port for {@code spaces} (owned by admin-api). All accessors are
 * tenant-scoped: {@code spaceId} alone is never sufficient to fetch a space.
 */
public interface SpaceRepository {

    Optional<Space> findByTenantIdAndId(String tenantId, String id);

    List<Space> findByTenantIdAndIdIn(String tenantId, List<String> ids);

    /**
     * Returns ACTIVE spaces flagged {@code showInSidebar=true} for the given
     * tenant, ordered by {@code sortOrder} ascending. Used to drive the
     * tenant sidebar in the public UI.
     */
    List<Space> findSidebarSpaces(String tenantId);
}
