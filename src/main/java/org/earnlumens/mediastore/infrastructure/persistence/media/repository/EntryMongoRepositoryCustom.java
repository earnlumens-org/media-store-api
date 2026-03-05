package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

/**
 * Custom repository operations that cannot be expressed as Spring Data derived queries.
 */
public interface EntryMongoRepositoryCustom {

    /**
     * Bulk-updates authorUsername and authorAvatarUrl on all entries belonging to a given user.
     * Used when a user's profile info changes (e.g. username change on X/Twitter).
     *
     * @param userId         the stable OAuth user ID (e.g. Twitter numeric ID)
     * @param newUsername     the updated username
     * @param newAvatarUrl   the updated avatar URL
     * @return number of entries updated
     */
    long updateAuthorInfoByUserId(String userId, String newUsername, String newAvatarUrl);

    /**
     * Atomically increments the viewCount field on a single entry.
     * Uses MongoDB $inc for thread-safe, lock-free counting.
     */
    void incrementViewCount(String entryId);

    /**
     * Aggregates owner stats: count by status + sum of viewCount.
     * Returns a map with keys: totalEntries, published, drafts, inReview, rejected, totalViews.
     */
    java.util.Map<String, Long> getOwnerStats(String tenantId, String userId);
}
