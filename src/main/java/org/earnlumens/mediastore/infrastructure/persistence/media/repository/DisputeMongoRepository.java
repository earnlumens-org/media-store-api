package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import org.earnlumens.mediastore.infrastructure.persistence.media.entity.DisputeEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface DisputeMongoRepository extends MongoRepository<DisputeEntity, String> {

    List<DisputeEntity> findByTenantIdAndModerationCaseId(String tenantId, String moderationCaseId);

    List<DisputeEntity> findByTenantIdAndUserIdAndStatus(String tenantId, String userId, String status);

    Optional<DisputeEntity> findByTenantIdAndId(String tenantId, String id);
}
