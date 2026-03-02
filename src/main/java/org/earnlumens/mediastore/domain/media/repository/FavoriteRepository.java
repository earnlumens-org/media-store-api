package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.Favorite;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

/**
 * Port for favorite persistence operations.
 */
public interface FavoriteRepository {

    /**
     * Check whether a specific item is already favorited by the user.
     */
    boolean existsByTenantIdAndUserIdAndItemId(String tenantId, String userId, String itemId);

    /**
     * Find a specific favorite (for deletion).
     */
    Optional<Favorite> findByTenantIdAndUserIdAndItemId(String tenantId, String userId, String itemId);

    /**
     * List all favorites for a user, newest first (paginated).
     */
    Page<Favorite> findByTenantIdAndUserId(String tenantId, String userId, Pageable pageable);

    /**
     * Persist a new favorite.
     */
    Favorite save(Favorite favorite);

    /**
     * Delete a favorite by its ID.
     */
    void deleteById(String id);
}
