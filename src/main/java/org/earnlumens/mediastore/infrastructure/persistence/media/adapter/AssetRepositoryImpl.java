package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import org.earnlumens.mediastore.domain.media.model.Asset;
import org.earnlumens.mediastore.domain.media.model.AssetStatus;
import org.earnlumens.mediastore.domain.media.model.MediaKind;
import org.earnlumens.mediastore.domain.media.repository.AssetRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.AssetEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.AssetMapper;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.AssetMongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class AssetRepositoryImpl implements AssetRepository {

    private final AssetMongoRepository assetMongoRepository;
    private final AssetMapper assetMapper;

    public AssetRepositoryImpl(AssetMongoRepository assetMongoRepository, AssetMapper assetMapper) {
        this.assetMongoRepository = assetMongoRepository;
        this.assetMapper = assetMapper;
    }

    @Override
    public Optional<Asset> findByTenantIdAndEntryIdAndKindAndStatus(
            String tenantId, String entryId, MediaKind kind, AssetStatus status) {
        return assetMongoRepository
                .findByTenantIdAndEntryIdAndKindAndStatus(tenantId, entryId, kind.name(), status.name())
                .map(assetMapper::toModel);
    }

    @Override
    public List<Asset> findByTenantIdAndEntryId(String tenantId, String entryId) {
        return assetMongoRepository.findByTenantIdAndEntryId(tenantId, entryId)
                .stream()
                .map(assetMapper::toModel)
                .toList();
    }

    @Override
    public Asset save(Asset asset) {
        AssetEntity entity = assetMapper.toEntity(asset);
        AssetEntity saved = assetMongoRepository.save(entity);
        return assetMapper.toModel(saved);
    }
}
