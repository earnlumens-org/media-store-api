package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.bson.Document;

public interface EntryRepository {

    Optional<Entry> findByTenantIdAndId(String tenantId, String id);

    Page<Entry> findByTenantIdAndStatus(String tenantId, EntryStatus status, Pageable pageable);

    /**
     * Public space feed query: PUBLISHED (or other given status) entries
     * whose {@code spaceIds} contains the given space, newest first.
     */
    Page<Entry> findByTenantIdAndSpaceIdAndStatus(String tenantId, String spaceId, EntryStatus status, Pageable pageable);

    Page<Entry> findByTenantIdAndAuthorUsernameAndStatus(String tenantId, String authorUsername, EntryStatus status, Pageable pageable);

    Page<Entry> findByTenantIdAndAuthorUsernameAndStatusAndType(String tenantId, String authorUsername, EntryStatus status, EntryType type, Pageable pageable);

    Page<Entry> findByTenantIdAndUserId(String tenantId, String userId, Pageable pageable);

    Page<Entry> findByTenantIdAndUserIdAndStatusNot(String tenantId, String userId, EntryStatus excludeStatus, Pageable pageable);

    Page<Entry> findByTenantIdAndUserIdAndStatus(String tenantId, String userId, EntryStatus status, Pageable pageable);

    Page<Entry> findByTenantIdAndUserIdAndType(String tenantId, String userId, EntryType type, Pageable pageable);

    Page<Entry> findByTenantIdAndUserIdAndStatusNotAndType(String tenantId, String userId, EntryStatus excludeStatus, EntryType type, Pageable pageable);

    Page<Entry> findByTenantIdAndUserIdAndStatusAndType(String tenantId, String userId, EntryStatus status, EntryType type, Pageable pageable);

    List<Entry> findByTenantIdAndIdIn(String tenantId, List<String> ids);

    List<Entry> findByTenantIdAndStatus(String tenantId, EntryStatus status);

    List<Entry> findByTenantIdAndStatusAndCreatedAtBefore(String tenantId, EntryStatus status, LocalDateTime cutoff);

    /** Finds all entries matching the given tenant, status and type. Used by batch operations. */
    List<Entry> findByTenantIdAndStatusAndType(String tenantId, EntryStatus status, EntryType type);

    /** Atomically increments the view counter on an entry within a tenant. */
    void incrementViewCount(String tenantId, String entryId);

    /** Aggregated stats for the owner dashboard (counts by status + total views). */
    java.util.Map<String, Long> getOwnerStats(String tenantId, String userId);

    /** Count entries created by a user after a given timestamp (for daily quota). */
    long countByTenantIdAndUserIdAndCreatedAtAfter(String tenantId, String userId, LocalDateTime after);

    /** Count entries in a specific status for a user (for burst detection). */
    long countByTenantIdAndUserIdAndStatus(String tenantId, String userId, EntryStatus status);

    Entry save(Entry entry);

    void deleteByTenantIdAndId(String tenantId, String id);

    /**
     * Bulk-updates authorUsername and authorAvatarUrl on all entries belonging to a user within a tenant.
     * Called when a user's profile info changes (e.g. username change on X/Twitter).
     */
    long updateAuthorInfoByUserId(String tenantId, String userId, String newUsername, String newAvatarUrl);

    /**
     * Unified Creator Studio feed: entries + collections merged via $unionWith.
     */
    List<Document> findStudioItems(String tenantId, String userId,
                                   String status, String type, String search,
                                   String sort, int skip, int limit);

    /**
     * Count for unified studio feed pagination.
     */
    long countStudioItems(String tenantId, String userId,
                          String status, String type, String search);

    // ── Public profile feed ─────────────────────────────────────────────

    List<Document> findProfileFeedItems(String tenantId, String authorUsername,
                                        String type, String search, String sort,
                                        int skip, int limit);

    long countProfileFeedItems(String tenantId, String authorUsername,
                               String type, String search);

    // ── Purchased feed ──────────────────────────────────────────────────

    List<Document> findPurchasedFeedItems(String tenantId,
                                          java.util.Set<String> entryIds, java.util.Set<String> collectionIds,
                                          String type, String search, String sort,
                                          int skip, int limit);

    long countPurchasedFeedItems(String tenantId,
                                 java.util.Set<String> entryIds, java.util.Set<String> collectionIds,
                                 String type, String search);

    // ── Explore feed ────────────────────────────────────────────────────

    Document findExploreFeed(String tenantId, String type, String pricing, String sort,
                             org.earnlumens.mediastore.domain.media.model.LanguageFilter languageFilter,
                             int skip, int limit);

    // ── Community feed ─────────────────────────────────

    Document findCommunityFeed(String tenantId, String badgeKey, String type,
                               String pricing, String sort,
                               org.earnlumens.mediastore.domain.media.model.LanguageFilter languageFilter,
                               int skip, int limit);

    // ── Search ──────────────────────────────────────────────────────────

    /**
     * Unified search feed: PUBLISHED entries + PUBLISHED/PUBLIC collections for
     * the tenant whose title/description/tags/author match every token in
     * {@code query}. Returns a single {@code $facet} document with {@code data}
     * (page of items) and {@code count} (total) so a single round-trip serves
     * both the page and its pagination metadata.
     */
    Document findSearchFeed(String tenantId, String query, String type, String sort,
                            int skip, int limit);

    /**
     * Channel (creator) matches for a search query — distinct authors of the
     * tenant's PUBLISHED content whose username matches {@code query}, ranked by
     * how much content they have published.
     */
    List<Document> searchChannels(String tenantId, String query, int limit);

    /**
     * Autocomplete suggestions — distinct PUBLISHED content titles for the
     * tenant that match {@code query}, ranked by popularity.
     */
    List<String> searchSuggestions(String tenantId, String query, int limit);
}
