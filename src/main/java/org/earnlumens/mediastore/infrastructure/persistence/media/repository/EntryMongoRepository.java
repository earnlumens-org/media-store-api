package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import org.earnlumens.mediastore.infrastructure.persistence.media.entity.EntryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EntryMongoRepository extends MongoRepository<EntryEntity, String> {

    Optional<EntryEntity> findByTenantIdAndId(String tenantId, String id);

    Page<EntryEntity> findByTenantIdAndStatusOrderByPublishedAtDesc(String tenantId, String status, Pageable pageable);

    List<EntryEntity> findByStatus(String status);

    List<EntryEntity> findByStatusAndCreatedAtBefore(String status, LocalDateTime cutoff);
}
