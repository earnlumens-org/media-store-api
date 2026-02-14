package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import org.earnlumens.mediastore.infrastructure.persistence.media.entity.EntryEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface EntryMongoRepository extends MongoRepository<EntryEntity, String> {

    Optional<EntryEntity> findByTenantIdAndId(String tenantId, String id);
}
