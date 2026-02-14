package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import org.earnlumens.mediastore.infrastructure.persistence.media.entity.EntitlementEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EntitlementMongoRepository extends MongoRepository<EntitlementEntity, String> {

    boolean existsByTenantIdAndUserIdAndEntryIdAndStatus(
            String tenantId, String userId, String entryId, String status);
}
