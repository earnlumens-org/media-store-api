package org.earnlumens.mediastore.domain.media.dto.response;

import java.util.List;

/**
 * Public rating summary for a target (entry or collection).
 *
 * <p>The {@code verified*} fields are computed from {@code PURCHASE}-backed
 * ratings only, so callers can surface a buyer-only score independent of any
 * {@code FREE_VIEW} ratings.</p>
 *
 * @param distribution star histogram, index 0 = 1★ … index 4 = 5★.
 */
public record RatingAggregateResponse(
        String targetType,
        String targetId,
        long count,
        double average,
        long verifiedCount,
        double verifiedAverage,
        double bayesianScore,
        List<Long> distribution
) {}
