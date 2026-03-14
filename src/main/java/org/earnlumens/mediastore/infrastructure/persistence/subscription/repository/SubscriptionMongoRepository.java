package org.earnlumens.mediastore.infrastructure.persistence.subscription.repository;

import org.earnlumens.mediastore.infrastructure.persistence.subscription.entity.SubscriptionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface SubscriptionMongoRepository extends MongoRepository<SubscriptionEntity, String> {

    boolean existsByTenantIdAndSubscriberIdAndTargetUserId(String tenantId, String subscriberId, String targetUserId);

    Optional<SubscriptionEntity> findByTenantIdAndSubscriberIdAndTargetUserId(String tenantId, String subscriberId, String targetUserId);

    Page<SubscriptionEntity> findByTenantIdAndSubscriberIdOrderBySubscribedAtDesc(String tenantId, String subscriberId, Pageable pageable);

    Page<SubscriptionEntity> findByTenantIdAndTargetUserIdOrderBySubscribedAtDesc(String tenantId, String targetUserId, Pageable pageable);

    long countByTenantIdAndTargetUserId(String tenantId, String targetUserId);

    long countByTenantIdAndSubscriberId(String tenantId, String subscriberId);

    List<SubscriptionEntity> findByTenantIdAndSubscriberIdAndTargetUserIdIn(String tenantId, String subscriberId, List<String> targetUserIds);

    void deleteByTenantIdAndId(String tenantId, String id);
}
