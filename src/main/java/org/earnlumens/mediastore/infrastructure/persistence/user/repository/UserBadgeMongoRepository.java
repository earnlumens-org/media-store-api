package org.earnlumens.mediastore.infrastructure.persistence.user.repository;

import org.earnlumens.mediastore.infrastructure.persistence.user.entity.UserBadgeAssignmentEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserBadgeMongoRepository extends MongoRepository<UserBadgeAssignmentEntity, String> {

    Optional<UserBadgeAssignmentEntity> findByTenantIdAndUserIdAndBadgeTypeAndStatus(
            String tenantId, String userId, String badgeType, String status);

    List<UserBadgeAssignmentEntity> findByTenantIdAndUserIdAndStatus(
            String tenantId, String userId, String status);

    List<UserBadgeAssignmentEntity> findByTenantIdAndBadgeTypeAndStatus(
            String tenantId, String badgeType, String status);

    List<UserBadgeAssignmentEntity> findByTenantIdAndStatusAndExpiresAtBefore(
            String tenantId, String status, LocalDateTime before);

    boolean existsByTenantIdAndUserIdAndBadgeTypeAndStatus(
            String tenantId, String userId, String badgeType, String status);
}
