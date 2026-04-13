package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import org.earnlumens.mediastore.infrastructure.persistence.media.entity.ModerationConfigEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * Read-only access to the {@code moderationConfigs} collection
 * (managed by admin-api, shared MongoDB).
 */
public interface ModerationConfigMongoRepository extends MongoRepository<ModerationConfigEntity, String> {

    Optional<ModerationConfigEntity> findByTenantId(String tenantId);
}
