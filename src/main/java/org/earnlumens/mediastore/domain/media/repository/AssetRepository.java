package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.Asset;
import org.earnlumens.mediastore.domain.media.model.AssetStatus;
import org.earnlumens.mediastore.domain.media.model.MediaKind;

import java.util.List;
import java.util.Optional;

public interface AssetRepository {

    Optional<Asset> findByTenantIdAndEntryIdAndKindAndStatus(
            String tenantId, String entryId, MediaKind kind, AssetStatus status);

    List<Asset> findByTenantIdAndEntryId(String tenantId, String entryId);

    Asset save(Asset asset);
}
