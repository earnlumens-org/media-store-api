package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.RatingAggregate;
import org.earnlumens.mediastore.domain.media.model.TargetType;

import java.util.Optional;

/**
 * Port for rating-aggregate persistence. Every method is tenant-scoped.
 */
public interface RatingAggregateRepository {

    /** The aggregate for a target, if one has been computed. */
    Optional<RatingAggregate> findByTenantIdAndTargetTypeAndTargetId(
            String tenantId, TargetType targetType, String targetId);

    /** Persist (create or update) the aggregate. */
    RatingAggregate save(RatingAggregate aggregate);
}
