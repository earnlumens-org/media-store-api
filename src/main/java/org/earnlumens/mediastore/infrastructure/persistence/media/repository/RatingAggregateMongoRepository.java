package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import org.earnlumens.mediastore.infrastructure.persistence.media.entity.RatingAggregateEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface RatingAggregateMongoRepository extends MongoRepository<RatingAggregateEntity, String> {

    Optional<RatingAggregateEntity> findByTenantIdAndTargetTypeAndTargetId(
            String tenantId, String targetType, String targetId);
}
