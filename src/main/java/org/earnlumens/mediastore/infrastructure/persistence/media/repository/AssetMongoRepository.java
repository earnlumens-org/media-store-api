package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import org.earnlumens.mediastore.infrastructure.persistence.media.entity.AssetEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface AssetMongoRepository extends MongoRepository<AssetEntity, String> {

    /**
     * Most recent asset of the given kind/status (H8). Never a query that
     * assumes uniqueness: two READY FULL assets must resolve deterministically,
     * not throw {@code IncorrectResultSizeDataAccessException}.
     */
    Optional<AssetEntity> findFirstByTenantIdAndEntryIdAndKindAndStatusOrderByCreatedAtDesc(
            String tenantId, String entryId, String kind, String status);

    List<AssetEntity> findByTenantIdAndEntryId(String tenantId, String entryId);
}
