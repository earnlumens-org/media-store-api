package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EntryRepository {

    Optional<Entry> findByTenantIdAndId(String tenantId, String id);

    Page<Entry> findByTenantIdAndStatus(String tenantId, EntryStatus status, Pageable pageable);

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

    Entry save(Entry entry);

    void deleteByTenantIdAndId(String tenantId, String id);

    /**
     * Bulk-updates authorUsername and authorAvatarUrl on all entries belonging to a user within a tenant.
     * Called when a user's profile info changes (e.g. username change on X/Twitter).
     */
    long updateAuthorInfoByUserId(String tenantId, String userId, String newUsername, String newAvatarUrl);
}
