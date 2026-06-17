package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.Collection;
import org.earnlumens.mediastore.domain.media.model.CollectionStatus;
import org.earnlumens.mediastore.domain.media.model.MediaVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface CollectionRepository {

    Optional<Collection> findByTenantIdAndId(String tenantId, String id);

    List<Collection> findByTenantIdAndIdIn(String tenantId, List<String> ids);

    /** Public feed: PUBLISHED + PUBLIC visibility, newest first */
    Page<Collection> findByTenantIdAndStatusAndVisibility(
            String tenantId, CollectionStatus status, MediaVisibility visibility, Pageable pageable);

    /** Creator dashboard: all collections by user, newest first */
    Page<Collection> findByTenantIdAndUserId(String tenantId, String userId, Pageable pageable);

    /** Public profile: PUBLISHED + PUBLIC collections by author username */
    Page<Collection> findByTenantIdAndAuthorUsernameAndStatusAndVisibility(
            String tenantId, String authorUsername, CollectionStatus status, MediaVisibility visibility, Pageable pageable);

    /** Find all PUBLISHED collections that contain a specific entry */
    List<Collection> findByTenantIdAndStatusAndItemsEntryId(
            String tenantId, CollectionStatus status, String entryId);

    /**
     * Bulk-updates authorUsername, authorUsernameLower and authorAvatarUrl on all
     * collections belonging to a user within a tenant. Called when a user's profile
     * info changes (e.g. username change on X/Twitter), mirroring the equivalent
     * backfill on entries so denormalized author cards stay fresh.
     */
    long updateAuthorInfoByUserId(String tenantId, String userId, String newUsername, String newAvatarUrl);

    Collection save(Collection collection);

    void deleteByTenantIdAndId(String tenantId, String id);
}
