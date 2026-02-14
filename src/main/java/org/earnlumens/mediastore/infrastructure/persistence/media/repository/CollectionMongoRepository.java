package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import org.earnlumens.mediastore.infrastructure.persistence.media.entity.CollectionEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface CollectionMongoRepository extends MongoRepository<CollectionEntity, String> {

    Optional<CollectionEntity> findByTenantIdAndId(String tenantId, String id);
}
