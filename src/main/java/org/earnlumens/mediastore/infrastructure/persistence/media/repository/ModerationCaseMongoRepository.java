package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import org.earnlumens.mediastore.infrastructure.persistence.media.entity.ModerationCaseEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ModerationCaseMongoRepository extends MongoRepository<ModerationCaseEntity, String> {

    Optional<ModerationCaseEntity> findByTenantIdAndId(String tenantId, String id);

    List<ModerationCaseEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

    /** Cross-tenant on purpose: sweep input. */
    List<ModerationCaseEntity> findByResolvedAtIsNullOrderByCreatedAtAsc(Pageable pageable);
}
