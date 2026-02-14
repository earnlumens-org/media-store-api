package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;

public interface EntitlementRepository {

    boolean existsByTenantIdAndUserIdAndEntryIdAndStatus(
            String tenantId, String userId, String entryId, EntitlementStatus status);
}
