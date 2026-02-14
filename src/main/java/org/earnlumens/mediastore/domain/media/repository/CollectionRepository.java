package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.Collection;

import java.util.Optional;

public interface CollectionRepository {

    Optional<Collection> findByTenantIdAndId(String tenantId, String id);

    Collection save(Collection collection);
}
