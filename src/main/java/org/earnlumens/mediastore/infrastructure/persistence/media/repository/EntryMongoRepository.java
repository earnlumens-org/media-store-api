package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import org.earnlumens.mediastore.infrastructure.persistence.media.entity.EntryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EntryMongoRepository extends MongoRepository<EntryEntity, String>, EntryMongoRepositoryCustom {

    Optional<EntryEntity> findByTenantIdAndId(String tenantId, String id);

    List<EntryEntity> findByTenantIdAndIdIn(String tenantId, List<String> ids);

    Page<EntryEntity> findByTenantIdAndStatusOrderByPublishedAtDesc(String tenantId, String status, Pageable pageable);

    Page<EntryEntity> findByTenantIdAndAuthorUsernameIgnoreCaseAndStatusOrderByPublishedAtDesc(String tenantId, String authorUsername, String status, Pageable pageable);

    Page<EntryEntity> findByTenantIdAndAuthorUsernameIgnoreCaseAndStatusAndTypeOrderByPublishedAtDesc(String tenantId, String authorUsername, String status, String type, Pageable pageable);

    Page<EntryEntity> findByTenantIdAndUserIdOrderByCreatedAtDesc(String tenantId, String userId, Pageable pageable);

    Page<EntryEntity> findByTenantIdAndUserIdAndStatusNotOrderByCreatedAtDesc(String tenantId, String userId, String status, Pageable pageable);

    Page<EntryEntity> findByTenantIdAndUserIdAndStatusOrderByCreatedAtDesc(String tenantId, String userId, String status, Pageable pageable);

    Page<EntryEntity> findByTenantIdAndUserIdAndTypeOrderByCreatedAtDesc(String tenantId, String userId, String type, Pageable pageable);

    Page<EntryEntity> findByTenantIdAndUserIdAndStatusNotAndTypeOrderByCreatedAtDesc(String tenantId, String userId, String status, String type, Pageable pageable);

    Page<EntryEntity> findByTenantIdAndUserIdAndStatusAndTypeOrderByCreatedAtDesc(String tenantId, String userId, String status, String type, Pageable pageable);

    List<EntryEntity> findByTenantIdAndStatus(String tenantId, String status);

    List<EntryEntity> findByTenantIdAndStatusAndCreatedAtBefore(String tenantId, String status, LocalDateTime cutoff);

    List<EntryEntity> findByTenantIdAndStatusAndType(String tenantId, String status, String type);
}
