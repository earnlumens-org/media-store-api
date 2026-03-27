package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import org.bson.Document;

import java.util.List;

/**
 * Custom repository operations that cannot be expressed as Spring Data derived queries.
 */
public interface EntryMongoRepositoryCustom {

    /**
     * Bulk-updates authorUsername and authorAvatarUrl on all entries belonging to a given user within a tenant.
     * Used when a user's profile info changes (e.g. username change on X/Twitter).
     *
     * @param tenantId       the tenant scope
     * @param userId         the stable OAuth user ID (e.g. Twitter numeric ID)
     * @param newUsername     the updated username
     * @param newAvatarUrl   the updated avatar URL
     * @return number of entries updated
     */
    long updateAuthorInfoByUserId(String tenantId, String userId, String newUsername, String newAvatarUrl);

    /**
     * Atomically increments the viewCount field on a single entry within a tenant.
     * Uses MongoDB $inc for thread-safe, lock-free counting.
     */
    void incrementViewCount(String tenantId, String entryId);

    /**
     * Aggregates owner stats: count by status + sum of viewCount.
     * Returns a map with keys: totalEntries, published, drafts, inReview, rejected, totalViews.
     */
    java.util.Map<String, Long> getOwnerStats(String tenantId, String userId);

    /**
     * Unified Creator Studio feed: merges entries + collections via $unionWith,
     * applies server-side filtering, sorting, and pagination.
     *
     * @return list of raw BSON documents, each with a "kind" field ("entry" or "collection")
     */
    List<Document> findStudioItems(String tenantId, String userId,
                                   String status, String type, String search,
                                   String sort, int skip, int limit);

    /**
     * Counts total items matching the studio feed filters (for pagination metadata).
     */
    long countStudioItems(String tenantId, String userId,
                          String status, String type, String search);

    // ── Public profile feed ─────────────────────────────────────────────────

    /**
     * Unified public profile feed: merges PUBLISHED entries + PUBLISHED/PUBLIC collections
     * for a given author, with optional type filter, search, sort and pagination.
     */
    List<Document> findProfileFeedItems(String tenantId, String authorUsername,
                                        String type, String search, String sort,
                                        int skip, int limit);

    long countProfileFeedItems(String tenantId, String authorUsername,
                               String type, String search);

    // ── Purchased feed ──────────────────────────────────────────────────────

    /**
     * Unified purchased feed: merges entries + collections whose IDs are in the
     * provided sets, with optional type filter, search, sort and pagination.
     */
    List<Document> findPurchasedFeedItems(String tenantId,
                                          java.util.Set<String> entryIds,
                                          java.util.Set<String> collectionIds,
                                          String type, String search, String sort,
                                          int skip, int limit);

    long countPurchasedFeedItems(String tenantId,
                                 java.util.Set<String> entryIds,
                                 java.util.Set<String> collectionIds,
                                 String type, String search);
}
