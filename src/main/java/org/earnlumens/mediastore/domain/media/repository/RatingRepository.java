package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.Rating;
import org.earnlumens.mediastore.domain.media.model.RatingProofType;
import org.earnlumens.mediastore.domain.media.model.TargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Port for rating persistence. Every method is tenant-scoped.
 */
public interface RatingRepository {

    /** A user's existing rating for a target (edit / duplicate check). */
    Optional<Rating> findByTenantIdAndUserIdAndTargetTypeAndTargetId(
            String tenantId, String userId, TargetType targetType, String targetId);

    /** Delete a user's rating for a target. Returns the number of deleted documents. */
    long deleteByTenantIdAndUserIdAndTargetTypeAndTargetId(
            String tenantId, String userId, TargetType targetType, String targetId);

    /** Rate-limit window: count this user's ratings created after a cutoff (all targets). */
    long countByTenantIdAndUserIdAndCreatedAtAfter(String tenantId, String userId, LocalDateTime after);

    /** Paginated reviews for a target. */
    Page<Rating> findByTenantIdAndTargetTypeAndTargetId(
            String tenantId, TargetType targetType, String targetId, Pageable pageable);

    /** Count ratings with a given like/dislike value (aggregate recomputation). */
    long countByTenantIdAndTargetTypeAndTargetIdAndLiked(
            String tenantId, TargetType targetType, String targetId, boolean liked);

    /** Count verified-proof ratings with a given like/dislike value (aggregate recomputation). */
    long countByTenantIdAndTargetTypeAndTargetIdAndProofTypeAndLiked(
            String tenantId, TargetType targetType, String targetId, RatingProofType proofType, boolean liked);

    /** Persist (create or update) a rating. */
    Rating save(Rating rating);
}
