package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import org.earnlumens.mediastore.infrastructure.persistence.media.entity.AssetEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface AssetMongoRepository extends MongoRepository<AssetEntity, String> {

    Optional<AssetEntity> findByTenantIdAndEntryIdAndKindAndStatus(
            String tenantId, String entryId, String kind, String status);

    List<AssetEntity> findByTenantIdAndEntryId(String tenantId, String entryId);
}
