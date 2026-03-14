package org.earnlumens.mediastore.infrastructure.persistence.subscription.adapter;

import org.earnlumens.mediastore.domain.subscription.model.Subscription;
import org.earnlumens.mediastore.domain.subscription.repository.SubscriptionRepository;
import org.earnlumens.mediastore.infrastructure.persistence.subscription.entity.SubscriptionEntity;
import org.earnlumens.mediastore.infrastructure.persistence.subscription.mapper.SubscriptionMapper;
import org.earnlumens.mediastore.infrastructure.persistence.subscription.repository.SubscriptionMongoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class SubscriptionRepositoryImpl implements SubscriptionRepository {

    private final SubscriptionMongoRepository mongoRepository;
    private final SubscriptionMapper mapper;

    public SubscriptionRepositoryImpl(SubscriptionMongoRepository mongoRepository, SubscriptionMapper mapper) {
        this.mongoRepository = mongoRepository;
        this.mapper = mapper;
    }

    @Override
    public boolean existsByTenantIdAndSubscriberIdAndTargetUserId(String tenantId, String subscriberId, String targetUserId) {
        return mongoRepository.existsByTenantIdAndSubscriberIdAndTargetUserId(tenantId, subscriberId, targetUserId);
    }

    @Override
    public Optional<Subscription> findByTenantIdAndSubscriberIdAndTargetUserId(String tenantId, String subscriberId, String targetUserId) {
        return mongoRepository.findByTenantIdAndSubscriberIdAndTargetUserId(tenantId, subscriberId, targetUserId)
                .map(mapper::toModel);
    }

    @Override
    public Page<Subscription> findByTenantIdAndSubscriberId(String tenantId, String subscriberId, Pageable pageable) {
        return mongoRepository.findByTenantIdAndSubscriberIdOrderBySubscribedAtDesc(tenantId, subscriberId, pageable)
                .map(mapper::toModel);
    }

    @Override
    public Page<Subscription> findByTenantIdAndTargetUserId(String tenantId, String targetUserId, Pageable pageable) {
        return mongoRepository.findByTenantIdAndTargetUserIdOrderBySubscribedAtDesc(tenantId, targetUserId, pageable)
                .map(mapper::toModel);
    }

    @Override
    public long countByTenantIdAndTargetUserId(String tenantId, String targetUserId) {
        return mongoRepository.countByTenantIdAndTargetUserId(tenantId, targetUserId);
    }

    @Override
    public long countByTenantIdAndSubscriberId(String tenantId, String subscriberId) {
        return mongoRepository.countByTenantIdAndSubscriberId(tenantId, subscriberId);
    }

    @Override
    public Set<String> findSubscribedTargetIds(String tenantId, String subscriberId, List<String> targetUserIds) {
        if (targetUserIds == null || targetUserIds.isEmpty()) return Set.of();
        return mongoRepository.findByTenantIdAndSubscriberIdAndTargetUserIdIn(tenantId, subscriberId, targetUserIds)
                .stream()
                .map(SubscriptionEntity::getTargetUserId)
                .collect(Collectors.toSet());
    }

    @Override
    public Subscription save(Subscription subscription) {
        SubscriptionEntity entity = mapper.toEntity(subscription);
        SubscriptionEntity saved = mongoRepository.save(entity);
        return mapper.toModel(saved);
    }

    @Override
    public void deleteByTenantIdAndId(String tenantId, String id) {
        mongoRepository.deleteByTenantIdAndId(tenantId, id);
    }
}
