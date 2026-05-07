package org.earnlumens.mediastore.infrastructure.persistence.space.repository;

import org.earnlumens.mediastore.infrastructure.persistence.space.entity.SpaceEntity;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data binding for the (admin-api owned) {@code spaces} collection.
 * Read-only from this side.
 */
@Repository
public interface SpaceMongoRepository extends MongoRepository<SpaceEntity, String> {

    Optional<SpaceEntity> findByTenantIdAndId(String tenantId, String id);

    List<SpaceEntity> findByTenantIdAndIdIn(String tenantId, List<String> ids);

    List<SpaceEntity> findByTenantIdAndStatusAndShowInSidebarTrueOrderBySortOrderAsc(
            String tenantId, String status);
}
