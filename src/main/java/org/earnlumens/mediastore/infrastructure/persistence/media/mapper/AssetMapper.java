package org.earnlumens.mediastore.infrastructure.persistence.media.mapper;

import org.earnlumens.mediastore.domain.media.model.Asset;
import org.earnlumens.mediastore.domain.media.model.AssetStatus;
import org.earnlumens.mediastore.domain.media.model.MediaKind;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.AssetEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface AssetMapper {

    @Mapping(target = "kind", source = "kind", qualifiedByName = "stringToMediaKind")
    @Mapping(target = "status", source = "status", qualifiedByName = "stringToAssetStatus")
    Asset toModel(AssetEntity entity);

    @Mapping(target = "kind", source = "kind", qualifiedByName = "mediaKindToString")
    @Mapping(target = "status", source = "status", qualifiedByName = "assetStatusToString")
    AssetEntity toEntity(Asset model);

    @Named("stringToMediaKind")
    default MediaKind stringToMediaKind(String value) {
        return value == null ? null : MediaKind.valueOf(value);
    }

    @Named("mediaKindToString")
    default String mediaKindToString(MediaKind value) {
        return value == null ? null : value.name();
    }

    @Named("stringToAssetStatus")
    default AssetStatus stringToAssetStatus(String value) {
        return value == null ? null : AssetStatus.valueOf(value);
    }

    @Named("assetStatusToString")
    default String assetStatusToString(AssetStatus value) {
        return value == null ? null : value.name();
    }
}
