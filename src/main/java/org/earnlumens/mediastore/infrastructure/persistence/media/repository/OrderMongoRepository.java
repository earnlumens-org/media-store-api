package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import org.earnlumens.mediastore.infrastructure.persistence.media.entity.OrderEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface OrderMongoRepository extends MongoRepository<OrderEntity, String> {

    Optional<OrderEntity> findByTenantIdAndUserIdAndEntryId(String tenantId, String userId, String entryId);
}
