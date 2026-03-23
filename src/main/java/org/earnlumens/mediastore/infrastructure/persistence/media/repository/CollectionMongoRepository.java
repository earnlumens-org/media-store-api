package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import org.earnlumens.mediastore.infrastructure.persistence.media.entity.CollectionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface CollectionMongoRepository extends MongoRepository<CollectionEntity, String> {

    Optional<CollectionEntity> findByTenantIdAndId(String tenantId, String id);

    List<CollectionEntity> findByTenantIdAndIdIn(String tenantId, List<String> ids);

    Page<CollectionEntity> findByTenantIdAndStatusAndVisibilityOrderByPublishedAtDesc(
            String tenantId, String status, String visibility, Pageable pageable);

    Page<CollectionEntity> findByTenantIdAndUserIdOrderByCreatedAtDesc(
            String tenantId, String userId, Pageable pageable);

    Page<CollectionEntity> findByTenantIdAndAuthorUsernameIgnoreCaseAndStatusAndVisibilityOrderByPublishedAtDesc(
            String tenantId, String authorUsername, String status, String visibility, Pageable pageable);

    List<CollectionEntity> findByTenantIdAndStatusAndItems_EntryId(
            String tenantId, String status, String entryId);

    void deleteByTenantIdAndId(String tenantId, String id);
}
