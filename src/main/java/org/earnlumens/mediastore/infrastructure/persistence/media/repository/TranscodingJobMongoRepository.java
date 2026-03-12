package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import org.earnlumens.mediastore.infrastructure.persistence.media.entity.TranscodingJobEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TranscodingJobMongoRepository extends MongoRepository<TranscodingJobEntity, String> {

    Optional<TranscodingJobEntity> findByTenantIdAndAssetId(String tenantId, String assetId);

    List<TranscodingJobEntity> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);

    /**
     * Find jobs whose status is DISPATCHED or PROCESSING and whose lastHeartbeat
     * is older than the given threshold — these are stuck/crashed workers.
     */
    @Query("{ 'status': { $in: ['DISPATCHED', 'PROCESSING'] }, 'lastHeartbeat': { $lt: ?0 } }")
    List<TranscodingJobEntity> findStaleJobs(LocalDateTime heartbeatBefore, Pageable pageable);

    /**
     * Find an active (non-terminal) job for the given entry.
     * Active = PENDING, DISPATCHED, or PROCESSING.
     */
    @Query("{ 'tenantId': ?0, 'entryId': ?1, 'status': { $in: ['PENDING', 'DISPATCHED', 'PROCESSING'] } }")
    Optional<TranscodingJobEntity> findActiveByTenantIdAndEntryId(String tenantId, String entryId);
}
