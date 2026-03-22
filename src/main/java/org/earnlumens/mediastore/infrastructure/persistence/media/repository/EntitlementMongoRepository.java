package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import org.earnlumens.mediastore.infrastructure.persistence.media.entity.EntitlementEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface EntitlementMongoRepository extends MongoRepository<EntitlementEntity, String> {

    boolean existsByTenantIdAndUserIdAndEntryIdAndStatus(
            String tenantId, String userId, String entryId, String status);

    boolean existsByTenantIdAndUserIdAndTargetTypeAndCollectionIdAndStatus(
            String tenantId, String userId, String targetType, String collectionId, String status);

    List<EntitlementEntity> findByTenantIdAndUserIdAndEntryIdInAndStatus(
            String tenantId, String userId, List<String> entryIds, String status);

    List<EntitlementEntity> findByTenantIdAndUserIdAndTargetTypeAndCollectionIdInAndStatus(
            String tenantId, String userId, String targetType, List<String> collectionIds, String status);

    Page<EntitlementEntity> findByTenantIdAndUserIdAndStatusOrderByGrantedAtDesc(
            String tenantId, String userId, String status, Pageable pageable);

    Page<EntitlementEntity> findByTenantIdAndUserIdAndTargetTypeAndStatusOrderByGrantedAtDesc(
            String tenantId, String userId, String targetType, String status, Pageable pageable);
}
