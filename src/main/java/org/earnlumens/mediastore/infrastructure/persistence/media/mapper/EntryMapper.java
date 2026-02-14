package org.earnlumens.mediastore.infrastructure.persistence.media.mapper;

import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.MediaKind;
import org.earnlumens.mediastore.domain.media.model.MediaVisibility;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.EntryEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface EntryMapper {

    @Mapping(target = "kind", source = "kind", qualifiedByName = "stringToMediaKind")
    @Mapping(target = "visibility", source = "visibility", qualifiedByName = "stringToMediaVisibility")
    Entry toModel(EntryEntity entity);

    @Mapping(target = "kind", source = "kind", qualifiedByName = "mediaKindToString")
    @Mapping(target = "visibility", source = "visibility", qualifiedByName = "mediaVisibilityToString")
    EntryEntity toEntity(Entry model);

    @Named("stringToMediaKind")
    default MediaKind stringToMediaKind(String value) {
        return value == null ? null : MediaKind.valueOf(value);
    }

    @Named("mediaKindToString")
    default String mediaKindToString(MediaKind value) {
        return value == null ? null : value.name();
    }

    @Named("stringToMediaVisibility")
    default MediaVisibility stringToMediaVisibility(String value) {
        return value == null ? null : MediaVisibility.valueOf(value);
    }

    @Named("mediaVisibilityToString")
    default String mediaVisibilityToString(MediaVisibility value) {
        return value == null ? null : value.name();
    }
}
