package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.TranscodingJob;
import org.earnlumens.mediastore.domain.media.model.TranscodingJobStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository contract for transcoding jobs.
 * All methods operate within a single tenant scope.
 */
public interface TranscodingJobRepository {

    TranscodingJob save(TranscodingJob job);

    Optional<TranscodingJob> findByTenantIdAndId(String tenantId, String id);

    Optional<TranscodingJob> findByTenantIdAndAssetId(String tenantId, String assetId);

    /**
     * Find jobs in a given status within a tenant, ordered by createdAt ASC (oldest first).
     * For platform-wide dispatch, iterate over tenants or use the cross-tenant variant.
     */
    List<TranscodingJob> findByTenantIdAndStatus(String tenantId, TranscodingJobStatus status, int limit);

    /**
     * Find jobs in a given status across ALL tenants. Only for platform-level operations
     * (dispatcher, monitoring). Must be called within TenantContext.runWithoutTenant().
     */
    List<TranscodingJob> findAllByStatus(TranscodingJobStatus status, int limit);

    /**
     * Find jobs that are stuck across ALL tenants: status is DISPATCHED or PROCESSING,
     * but lastHeartbeat is older than the given threshold.
     * Only for platform-level watchdog. Must be called within TenantContext.runWithoutTenant().
     */
    List<TranscodingJob> findAllStaleJobs(LocalDateTime heartbeatBefore, int limit);

    /** Find jobs by entry, useful for checking if an entry already has a pending/active job. */
    Optional<TranscodingJob> findActiveByTenantIdAndEntryId(String tenantId, String entryId);
}
