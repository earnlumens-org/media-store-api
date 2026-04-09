package org.earnlumens.mediastore.application.user;

import org.earnlumens.mediastore.domain.user.model.BadgeAssignedBy;
import org.earnlumens.mediastore.domain.user.model.BadgeAssignmentStatus;
import org.earnlumens.mediastore.domain.user.model.BadgeType;
import org.earnlumens.mediastore.domain.user.model.UserBadgeAssignment;
import org.earnlumens.mediastore.domain.user.repository.UserBadgeRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.EntryMongoRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.CollectionMongoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class UserBadgeService {

    private static final Logger log = LoggerFactory.getLogger(UserBadgeService.class);

    private final UserBadgeRepository badgeRepository;
    private final MongoTemplate mongoTemplate;

    public UserBadgeService(UserBadgeRepository badgeRepository,
                            MongoTemplate mongoTemplate) {
        this.badgeRepository = badgeRepository;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Claim a free community badge (U1) for the promotional period.
     *
     * @return the created assignment, or empty if user already has an active U1 badge
     */
    public Optional<UserBadgeAssignment> claimCommunityBadge(String tenantId, String userId, int durationYears) {
        // Check if user already has an active U1 badge
        if (badgeRepository.hasActiveBadge(tenantId, userId, BadgeType.U1)) {
            return Optional.empty();
        }

        LocalDateTime now = LocalDateTime.now();

        UserBadgeAssignment assignment = new UserBadgeAssignment();
        assignment.setTenantId(tenantId);
        assignment.setUserId(userId);
        assignment.setBadgeType(BadgeType.U1);
        assignment.setStatus(BadgeAssignmentStatus.ACTIVE);
        assignment.setAssignedBy(BadgeAssignedBy.PROMOTION);
        assignment.setStartedAt(now);
        assignment.setExpiresAt(now.plusYears(durationYears));
        assignment.setCreatedAt(now);

        UserBadgeAssignment saved = badgeRepository.save(assignment);

        // Stamp the badge on all existing PUBLISHED entries and collections of this user
        stampBadgeOnUserContent(tenantId, userId, toBadgeKey(BadgeType.U1));

        return Optional.of(saved);
    }

    /**
     * Get the highest-priority active badge for a user (U2 > U1).
     */
    public Optional<String> getActiveBadgeKey(String tenantId, String userId) {
        List<UserBadgeAssignment> active = badgeRepository.findActiveByUser(tenantId, userId);
        if (active.isEmpty()) {
            return Optional.empty();
        }

        // Return highest badge: U3 > U2 > U1
        return active.stream()
                .map(UserBadgeAssignment::getBadgeType)
                .max(BadgeType::compareTo)
                .map(this::toBadgeKey);
    }

    /**
     * Check if user has an active badge of a specific type.
     */
    public boolean hasActiveBadge(String tenantId, String userId, BadgeType badgeType) {
        return badgeRepository.hasActiveBadge(tenantId, userId, badgeType);
    }

    /**
     * Find all active badge assignments for a user.
     */
    public List<UserBadgeAssignment> getActiveAssignments(String tenantId, String userId) {
        return badgeRepository.findActiveByUser(tenantId, userId);
    }

    /**
     * Process expired badge assignments: mark them as EXPIRED and clear authorBadge
     * from their entries/collections so they no longer appear in community feeds.
     */
    public long processExpiredBadges(String tenantId) {
        LocalDateTime now = LocalDateTime.now();
        List<UserBadgeAssignment> expired = badgeRepository.findExpiredAssignments(tenantId, now);

        if (expired.isEmpty()) {
            return 0;
        }

        // Expire the assignments
        long count = badgeRepository.expireAssignments(tenantId, now);

        // For each expired user, check if they still have any active badge
        for (UserBadgeAssignment assignment : expired) {
            Optional<String> remainingBadge = getActiveBadgeKey(tenantId, assignment.getUserId());
            if (remainingBadge.isEmpty()) {
                // No more active badges — clear from content
                clearBadgeFromUserContent(tenantId, assignment.getUserId());
            } else {
                // Downgrade to remaining badge
                stampBadgeOnUserContent(tenantId, assignment.getUserId(), remainingBadge.get());
            }
        }

        log.info("Processed {} expired badge assignments for tenant {}", count, tenantId);
        return count;
    }

    /**
     * Set the authorBadge field on all PUBLISHED entries and collections of a user.
     */
    private void stampBadgeOnUserContent(String tenantId, String userId, String badgeKey) {
        Query query = new Query(Criteria.where("tenantId").is(tenantId)
                .and("userId").is(userId)
                .and("status").is("PUBLISHED"));

        Update update = new Update().set("authorBadge", badgeKey);

        long entries = mongoTemplate.updateMulti(query, update,
                org.earnlumens.mediastore.infrastructure.persistence.media.entity.EntryEntity.class).getModifiedCount();
        long collections = mongoTemplate.updateMulti(query, update,
                org.earnlumens.mediastore.infrastructure.persistence.media.entity.CollectionEntity.class).getModifiedCount();

        log.info("Stamped badge {} on {} entries and {} collections for user {} in tenant {}",
                badgeKey, entries, collections, userId, tenantId);
    }

    /**
     * Clear the authorBadge field from all entries and collections of a user.
     */
    private void clearBadgeFromUserContent(String tenantId, String userId) {
        Query query = new Query(Criteria.where("tenantId").is(tenantId)
                .and("userId").is(userId));

        Update update = new Update().unset("authorBadge");

        long entries = mongoTemplate.updateMulti(query, update,
                org.earnlumens.mediastore.infrastructure.persistence.media.entity.EntryEntity.class).getModifiedCount();
        long collections = mongoTemplate.updateMulti(query, update,
                org.earnlumens.mediastore.infrastructure.persistence.media.entity.CollectionEntity.class).getModifiedCount();

        log.info("Cleared badge from {} entries and {} collections for user {} in tenant {}",
                entries, collections, userId, tenantId);
    }

    /**
     * Convert BadgeType enum to the lowercase badge key used by the frontend.
     */
    private String toBadgeKey(BadgeType type) {
        return type.name().toLowerCase();
    }
}
