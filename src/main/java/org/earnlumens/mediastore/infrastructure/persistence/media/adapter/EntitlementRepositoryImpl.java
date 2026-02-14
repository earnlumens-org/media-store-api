package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.EntitlementMongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public class EntitlementRepositoryImpl implements EntitlementRepository {

    private final EntitlementMongoRepository entitlementMongoRepository;

    public EntitlementRepositoryImpl(EntitlementMongoRepository entitlementMongoRepository) {
        this.entitlementMongoRepository = entitlementMongoRepository;
    }

    @Override
    public boolean existsByTenantIdAndUserIdAndEntryIdAndStatus(
            String tenantId, String userId, String entryId, EntitlementStatus status) {
        return entitlementMongoRepository.existsByTenantIdAndUserIdAndEntryIdAndStatus(
                tenantId, userId, entryId, status.name());
    }
}
