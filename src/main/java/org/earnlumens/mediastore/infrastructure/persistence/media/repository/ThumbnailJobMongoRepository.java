package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import org.earnlumens.mediastore.infrastructure.persistence.media.entity.ThumbnailJobEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ThumbnailJobMongoRepository extends MongoRepository<ThumbnailJobEntity, String> {

    List<ThumbnailJobEntity> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);

    /**
     * Find jobs whose status is DISPATCHED or PROCESSING and whose lastHeartbeat
     * is older than the given threshold — these are stuck/crashed workers.
     */
    @Query("{ 'status': { $in: ['DISPATCHED', 'PROCESSING'] }, 'lastHeartbeat': { $lt: ?0 } }")
    List<ThumbnailJobEntity> findStaleJobs(LocalDateTime heartbeatBefore, Pageable pageable);

    /**
     * Find an active (non-terminal) job for the given owner + kind.
     */
    @Query("{ 'tenantId': ?0, 'ownerId': ?1, 'kind': ?2, "
            + "'status': { $in: ['PENDING', 'DISPATCHED', 'PROCESSING'] } }")
    Optional<ThumbnailJobEntity> findActiveByTenantIdAndOwnerIdAndKind(
            String tenantId, String ownerId, String kind);
}
