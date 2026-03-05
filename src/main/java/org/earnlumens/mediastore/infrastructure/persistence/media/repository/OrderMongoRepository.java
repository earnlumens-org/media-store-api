package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import org.earnlumens.mediastore.infrastructure.persistence.media.entity.OrderEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderMongoRepository extends MongoRepository<OrderEntity, String> {

    List<OrderEntity> findByTenantIdAndUserIdAndEntryId(String tenantId, String userId, String entryId);

    List<OrderEntity> findByStatusAndExpiresAtBefore(String status, LocalDateTime cutoff);

    long countByTenantIdAndSellerIdAndStatus(String tenantId, String sellerId, String status);

    List<OrderEntity> findByTenantIdAndSellerIdAndStatusOrderByCompletedAtDesc(String tenantId, String sellerId, String status);
}
