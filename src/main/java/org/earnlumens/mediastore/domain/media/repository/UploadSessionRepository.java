package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.UploadSession;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@link UploadSession}. Uses Spring Data Mongo directly —
 * upload sessions are infrastructure bookkeeping, not a rich domain model,
 * so the entity/mapper indirection used for content models is skipped.
 */
public interface UploadSessionRepository extends MongoRepository<UploadSession, String> {

    Optional<UploadSession> findByIdAndTenantId(String id, String tenantId);

    List<UploadSession> findByStatusAndCreatedAtBefore(UploadSession.Status status, LocalDateTime cutoff);

    List<UploadSession> findByTenantIdAndEntryId(String tenantId, String entryId);
}
