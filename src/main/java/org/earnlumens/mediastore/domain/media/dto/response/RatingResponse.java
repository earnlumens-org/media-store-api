package org.earnlumens.mediastore.domain.media.dto.response;

/**
 * A single like/dislike vote (with optional review). {@code comment} is always
 * plain text (markup stripped on write); the client must still render it as
 * text, never HTML. {@code liked} is {@code true} for a like (thumbs up),
 * {@code false} for a dislike (thumbs down).
 */
public record RatingResponse(
        String id,
        String userId,
        String username,
        boolean liked,
        String comment,
        String proofType,
        boolean verified,
        String createdAt,
        String updatedAt
) {}
