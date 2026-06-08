package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import org.earnlumens.mediastore.infrastructure.persistence.media.entity.RatingEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RatingMongoRepository extends MongoRepository<RatingEntity, String> {

    /** A user's existing rating for a target (edit / duplicate check). */
    Optional<RatingEntity> findByTenantIdAndUserIdAndTargetTypeAndTargetId(
            String tenantId, String userId, String targetType, String targetId);

    /** Delete a user's rating for a target. */
    long deleteByTenantIdAndUserIdAndTargetTypeAndTargetId(
            String tenantId, String userId, String targetType, String targetId);

    /** Rate-limit window: count this user's ratings created after a cutoff (all targets). */
    long countByTenantIdAndUserIdAndCreatedAtAfter(String tenantId, String userId, LocalDateTime after);

    /** Paginated reviews for a target (sort supplied via Pageable). */
    Page<RatingEntity> findByTenantIdAndTargetTypeAndTargetId(
            String tenantId, String targetType, String targetId, Pageable pageable);

    // ── Aggregate recomputation (always derived from source — no drift) ──

    long countByTenantIdAndTargetTypeAndTargetIdAndStars(
            String tenantId, String targetType, String targetId, int stars);

    long countByTenantIdAndTargetTypeAndTargetIdAndProofTypeAndStars(
            String tenantId, String targetType, String targetId, String proofType, int stars);
}
