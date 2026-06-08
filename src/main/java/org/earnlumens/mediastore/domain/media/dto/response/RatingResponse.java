package org.earnlumens.mediastore.domain.media.dto.response;

/**
 * A single rating/review. {@code comment} is always plain text (markup
 * stripped on write); the client must still render it as text, never HTML.
 */
public record RatingResponse(
        String id,
        String userId,
        String username,
        int stars,
        String comment,
        String proofType,
        boolean verified,
        String createdAt,
        String updatedAt
) {}
