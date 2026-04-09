package org.earnlumens.mediastore.infrastructure.persistence.user.adapter;

import org.earnlumens.mediastore.domain.user.model.BadgeAssignmentStatus;
import org.earnlumens.mediastore.domain.user.model.BadgeType;
import org.earnlumens.mediastore.domain.user.model.UserBadgeAssignment;
import org.earnlumens.mediastore.domain.user.repository.UserBadgeRepository;
import org.earnlumens.mediastore.infrastructure.persistence.user.entity.UserBadgeAssignmentEntity;
import org.earnlumens.mediastore.infrastructure.persistence.user.mapper.UserBadgeMapper;
import org.earnlumens.mediastore.infrastructure.persistence.user.repository.UserBadgeMongoRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class UserBadgeRepositoryImpl implements UserBadgeRepository {

    private final UserBadgeMongoRepository mongoRepository;
    private final UserBadgeMapper mapper;
    private final MongoTemplate mongoTemplate;

    public UserBadgeRepositoryImpl(UserBadgeMongoRepository mongoRepository,
                                   UserBadgeMapper mapper,
                                   MongoTemplate mongoTemplate) {
        this.mongoRepository = mongoRepository;
        this.mapper = mapper;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public UserBadgeAssignment save(UserBadgeAssignment assignment) {
        UserBadgeAssignmentEntity entity = mapper.toEntity(assignment);
        entity = mongoRepository.save(entity);
        return mapper.toModel(entity);
    }

    @Override
    public Optional<UserBadgeAssignment> findById(String tenantId, String id) {
        return mongoRepository.findById(id)
                .filter(entity -> tenantId.equals(entity.getTenantId()))
                .map(mapper::toModel);
    }

    @Override
    public Optional<UserBadgeAssignment> findActiveByUserAndBadge(String tenantId, String userId, BadgeType badgeType) {
        return mongoRepository
                .findByTenantIdAndUserIdAndBadgeTypeAndStatus(tenantId, userId, badgeType.name(), BadgeAssignmentStatus.ACTIVE.name())
                .map(mapper::toModel);
    }

    @Override
    public List<UserBadgeAssignment> findActiveByUser(String tenantId, String userId) {
        return mongoRepository
                .findByTenantIdAndUserIdAndStatus(tenantId, userId, BadgeAssignmentStatus.ACTIVE.name())
                .stream()
                .map(mapper::toModel)
                .toList();
    }

    @Override
    public List<UserBadgeAssignment> findActiveByBadgeType(String tenantId, BadgeType badgeType) {
        return mongoRepository
                .findByTenantIdAndBadgeTypeAndStatus(tenantId, badgeType.name(), BadgeAssignmentStatus.ACTIVE.name())
                .stream()
                .map(mapper::toModel)
                .toList();
    }

    @Override
    public List<UserBadgeAssignment> findExpiredAssignments(String tenantId, LocalDateTime before) {
        return mongoRepository
                .findByTenantIdAndStatusAndExpiresAtBefore(tenantId, BadgeAssignmentStatus.ACTIVE.name(), before)
                .stream()
                .map(mapper::toModel)
                .toList();
    }

    @Override
    public boolean hasActiveBadge(String tenantId, String userId, BadgeType badgeType) {
        return mongoRepository
                .existsByTenantIdAndUserIdAndBadgeTypeAndStatus(tenantId, userId, badgeType.name(), BadgeAssignmentStatus.ACTIVE.name());
    }

    @Override
    public long expireAssignments(String tenantId, LocalDateTime before) {
        Query query = new Query(Criteria.where("tenantId").is(tenantId)
                .and("status").is(BadgeAssignmentStatus.ACTIVE.name())
                .and("expiresAt").lt(before));

        Update update = new Update().set("status", BadgeAssignmentStatus.EXPIRED.name());

        return mongoTemplate.updateMulti(query, update, UserBadgeAssignmentEntity.class).getModifiedCount();
    }
}
