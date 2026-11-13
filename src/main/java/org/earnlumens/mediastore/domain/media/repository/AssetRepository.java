package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.Asset;
import org.earnlumens.mediastore.domain.media.model.AssetStatus;
import org.earnlumens.mediastore.domain.media.model.MediaKind;

import java.util.List;
import java.util.Optional;

public interface AssetRepository {

    /**
     * The <b>most recent</b> asset of the given kind and status
     * ({@code createdAt desc, limit 1}) — the deterministic fallback every
     * call-site relies on (H8). Assets are append-only, so several FULL
     * assets of one entry are normal; this never assumes uniqueness.
     */
    Optional<Asset> findByTenantIdAndEntryIdAndKindAndStatus(
            String tenantId, String entryId, MediaKind kind, AssetStatus status);

    List<Asset> findByTenantIdAndEntryId(String tenantId, String entryId);

    Asset save(Asset asset);
}
