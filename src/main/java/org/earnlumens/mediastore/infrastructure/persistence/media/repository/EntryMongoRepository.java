package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import org.earnlumens.mediastore.infrastructure.persistence.media.entity.EntryEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EntryMongoRepository extends MongoRepository<EntryEntity, String> {

    Optional<EntryEntity> findByTenantIdAndId(String tenantId, String id);

    List<EntryEntity> findByStatusAndCreatedAtBefore(String status, LocalDateTime cutoff);
}
