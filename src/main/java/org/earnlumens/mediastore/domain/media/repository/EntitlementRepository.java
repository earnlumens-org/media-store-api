package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.Entitlement;
import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;
import org.earnlumens.mediastore.domain.media.model.TargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface EntitlementRepository {

    boolean existsByTenantIdAndUserIdAndEntryIdAndStatus(
            String tenantId, String userId, String entryId, EntitlementStatus status);

    /** Full entitlement for an entry (used to verify the backing order before unlocking). */
    Optional<Entitlement> findByTenantIdAndUserIdAndEntryIdAndStatus(
            String tenantId, String userId, String entryId, EntitlementStatus status);

    /** Full collection entitlements (used to verify the backing orders before unlocking). */
    List<Entitlement> findByTenantIdAndUserIdAndCollectionIdsAndStatus(
            String tenantId, String userId, List<String> collectionIds, EntitlementStatus status);

    /** Check if user has an active collection entitlement */
    boolean existsByTenantIdAndUserIdAndTargetTypeAndCollectionIdAndStatus(
            String tenantId, String userId, TargetType targetType, String collectionId, EntitlementStatus status);

    /**
     * Return the subset of {@code entryIds} for which the user has an active entitlement.
     * Used for batch "is unlocked?" checks (e.g. favorites list).
     */
    Set<String> findEntitledEntryIds(
            String tenantId, String userId, List<String> entryIds, EntitlementStatus status);

    /** Return the subset of collectionIds for which the user has an active entitlement */
    Set<String> findEntitledCollectionIds(
            String tenantId, String userId, List<String> collectionIds, EntitlementStatus status);

    Page<Entitlement> findByTenantIdAndUserIdAndStatus(
            String tenantId, String userId, EntitlementStatus status, Pageable pageable);

    /** Paginated entitlements filtered by targetType (ENTRY or COLLECTION) */
    Page<Entitlement> findByTenantIdAndUserIdAndTargetTypeAndStatus(
            String tenantId, String userId, TargetType targetType, EntitlementStatus status, Pageable pageable);

    /** All entry IDs the user is entitled to (unpaginated, for purchased feed). */
    Set<String> findAllEntitledEntryIds(String tenantId, String userId, EntitlementStatus status);

    /** All collection IDs the user is entitled to (unpaginated, for purchased feed). */
    Set<String> findAllEntitledCollectionIds(String tenantId, String userId, EntitlementStatus status);

    Entitlement save(Entitlement entitlement);
}
