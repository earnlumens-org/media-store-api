package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.Entitlement;
import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Set;

public interface EntitlementRepository {

    boolean existsByTenantIdAndUserIdAndEntryIdAndStatus(
            String tenantId, String userId, String entryId, EntitlementStatus status);

    /**
     * Return the subset of {@code entryIds} for which the user has an active entitlement.
     * Used for batch "is unlocked?" checks (e.g. favorites list).
     */
    Set<String> findEntitledEntryIds(
            String tenantId, String userId, List<String> entryIds, EntitlementStatus status);

    Page<Entitlement> findByTenantIdAndUserIdAndStatus(
            String tenantId, String userId, EntitlementStatus status, Pageable pageable);

    Entitlement save(Entitlement entitlement);
}
