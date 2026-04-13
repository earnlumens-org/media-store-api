package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.ModerationJob;
import org.earnlumens.mediastore.domain.media.model.ModerationJobStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository contract for moderation jobs.
 */
public interface ModerationJobRepository {

    ModerationJob save(ModerationJob job);

    Optional<ModerationJob> findByTenantIdAndId(String tenantId, String id);

    /**
     * Find jobs in a given status across ALL tenants. Only for platform-level operations
     * (dispatcher, monitoring). Must be called within TenantContext.runWithoutTenant().
     */
    List<ModerationJob> findAllByStatus(ModerationJobStatus status, int limit);

    /**
     * Find jobs that are stuck across ALL tenants: status is DISPATCHED or PROCESSING,
     * but lastHeartbeat is older than the given threshold.
     */
    List<ModerationJob> findAllStaleJobs(LocalDateTime heartbeatBefore, int limit);

    /** Find an active (non-terminal) job for the given entry. */
    Optional<ModerationJob> findActiveByTenantIdAndEntryId(String tenantId, String entryId);
}
