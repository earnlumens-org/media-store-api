package org.earnlumens.mediastore.infrastructure.persistence.media.mapper;

import org.earnlumens.mediastore.domain.media.model.ThumbnailJob;
import org.earnlumens.mediastore.domain.media.model.ThumbnailJobKind;
import org.earnlumens.mediastore.domain.media.model.ThumbnailJobStatus;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.ThumbnailJobEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface ThumbnailJobMapper {

    @Mapping(target = "status", source = "status", qualifiedByName = "stringToThumbnailJobStatus")
    @Mapping(target = "kind", source = "kind", qualifiedByName = "stringToThumbnailJobKind")
    ThumbnailJob toModel(ThumbnailJobEntity entity);

    @Mapping(target = "status", source = "status", qualifiedByName = "thumbnailJobStatusToString")
    @Mapping(target = "kind", source = "kind", qualifiedByName = "thumbnailJobKindToString")
    ThumbnailJobEntity toEntity(ThumbnailJob model);

    @Named("stringToThumbnailJobStatus")
    default ThumbnailJobStatus stringToThumbnailJobStatus(String value) {
        return value == null ? null : ThumbnailJobStatus.valueOf(value);
    }

    @Named("thumbnailJobStatusToString")
    default String thumbnailJobStatusToString(ThumbnailJobStatus value) {
        return value == null ? null : value.name();
    }

    @Named("stringToThumbnailJobKind")
    default ThumbnailJobKind stringToThumbnailJobKind(String value) {
        return value == null ? null : ThumbnailJobKind.valueOf(value);
    }

    @Named("thumbnailJobKindToString")
    default String thumbnailJobKindToString(ThumbnailJobKind value) {
        return value == null ? null : value.name();
    }
}
