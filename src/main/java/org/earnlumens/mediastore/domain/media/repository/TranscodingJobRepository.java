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

    Optional<TranscodingJob> findById(String id);

    Optional<TranscodingJob> findByTenantIdAndAssetId(String tenantId, String assetId);

    /** Find jobs in a given status, ordered by createdAt ASC (oldest first). */
    List<TranscodingJob> findByStatus(TranscodingJobStatus status, int limit);

    /**
     * Find jobs that are stuck: status is DISPATCHED or PROCESSING,
     * but lastHeartbeat is older than the given threshold.
     * These are candidates for retry by the watchdog.
     */
    List<TranscodingJob> findStaleJobs(LocalDateTime heartbeatBefore, int limit);

    /** Find jobs by entry, useful for checking if an entry already has a pending/active job. */
    Optional<TranscodingJob> findActiveByTenantIdAndEntryId(String tenantId, String entryId);
}
