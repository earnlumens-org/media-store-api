package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.Collection;

import java.util.List;
import java.util.Optional;

public interface CollectionRepository {

    Optional<Collection> findByTenantIdAndId(String tenantId, String id);

    List<Collection> findByTenantIdAndIdIn(String tenantId, List<String> ids);

    Collection save(Collection collection);
}
