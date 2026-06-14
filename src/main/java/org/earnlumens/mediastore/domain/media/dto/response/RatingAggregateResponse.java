package org.earnlumens.mediastore.domain.media.dto.response;

/**
 * Public like/dislike summary for a target (entry or collection).
 *
 * <p>Roblox-style: {@code likePercent} is the headline score
 * ({@code likes / count * 100}). The {@code verified*} fields are computed
 * from {@code PURCHASE}-backed votes only, so callers can surface a buyer-only
 * score independent of any {@code FREE_VIEW} votes.</p>
 *
 * @param count        total votes ({@code likes + dislikes}).
 * @param likes        number of likes (thumbs up).
 * @param dislikes     number of dislikes (thumbs down).
 * @param likePercent  percentage of likes, 0&ndash;100 (0 when no votes).
 */
public record RatingAggregateResponse(
        String targetType,
        String targetId,
        long count,
        long likes,
        long dislikes,
        double likePercent,
        long verifiedCount,
        long verifiedLikes,
        long verifiedDislikes,
        double verifiedLikePercent
) {}
