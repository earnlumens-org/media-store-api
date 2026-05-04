package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.ThumbnailJob;
import org.earnlumens.mediastore.domain.media.model.ThumbnailJobKind;
import org.earnlumens.mediastore.domain.media.model.ThumbnailJobStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Repository contract for thumbnail-processing jobs. */
public interface ThumbnailJobRepository {

    ThumbnailJob save(ThumbnailJob job);

    Optional<ThumbnailJob> findByTenantIdAndId(String tenantId, String id);

    /** Find jobs in a given status across ALL tenants. Platform-level use only (dispatcher / monitoring). */
    List<ThumbnailJob> findAllByStatus(ThumbnailJobStatus status, int limit);

    /** Find stuck jobs across ALL tenants for the watchdog. */
    List<ThumbnailJob> findAllStaleJobs(LocalDateTime heartbeatBefore, int limit);

    /** Find an active (non-terminal) job for a given owner + kind. */
    Optional<ThumbnailJob> findActiveByTenantIdAndOwnerIdAndKind(String tenantId, String ownerId, ThumbnailJobKind kind);
}
