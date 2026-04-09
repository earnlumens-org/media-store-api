package org.earnlumens.mediastore.domain.user.repository;

import org.earnlumens.mediastore.domain.user.model.BadgeType;
import org.earnlumens.mediastore.domain.user.model.UserBadgeAssignment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserBadgeRepository {

    UserBadgeAssignment save(UserBadgeAssignment assignment);

    Optional<UserBadgeAssignment> findById(String tenantId, String id);

    /** Find the active assignment of a specific badge type for a user within a tenant. */
    Optional<UserBadgeAssignment> findActiveByUserAndBadge(String tenantId, String userId, BadgeType badgeType);

    /** Find all active badge assignments for a user within a tenant. */
    List<UserBadgeAssignment> findActiveByUser(String tenantId, String userId);

    /** Find all active assignments of a given badge type within a tenant. */
    List<UserBadgeAssignment> findActiveByBadgeType(String tenantId, BadgeType badgeType);

    /** Find all assignments that are ACTIVE but their expiresAt is before the given threshold. */
    List<UserBadgeAssignment> findExpiredAssignments(String tenantId, LocalDateTime before);

    /** Check if a user has a specific active badge in a tenant. */
    boolean hasActiveBadge(String tenantId, String userId, BadgeType badgeType);

    /** Bulk update status for expired assignments. Returns the count of updated documents. */
    long expireAssignments(String tenantId, LocalDateTime before);
}
