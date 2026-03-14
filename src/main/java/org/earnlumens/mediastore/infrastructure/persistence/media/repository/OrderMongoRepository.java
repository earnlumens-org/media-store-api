package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import org.earnlumens.mediastore.infrastructure.persistence.media.entity.OrderEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderMongoRepository extends MongoRepository<OrderEntity, String> {

    List<OrderEntity> findByTenantIdAndUserIdAndEntryId(String tenantId, String userId, String entryId);

    Optional<OrderEntity> findByTenantIdAndId(String tenantId, String id);

    List<OrderEntity> findByTenantIdAndStatusAndExpiresAtBefore(String tenantId, String status, LocalDateTime cutoff);

    long countByTenantIdAndSellerIdAndStatus(String tenantId, String sellerId, String status);

    List<OrderEntity> findByTenantIdAndSellerIdAndStatusOrderByCompletedAtDesc(String tenantId, String sellerId, String status);
}
