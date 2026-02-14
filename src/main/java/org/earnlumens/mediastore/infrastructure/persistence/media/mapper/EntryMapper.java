package org.earnlumens.mediastore.infrastructure.persistence.media.mapper;

import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.MediaVisibility;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.EntryEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface EntryMapper {

    @Mapping(target = "type", source = "type", qualifiedByName = "stringToEntryType")
    @Mapping(target = "status", source = "status", qualifiedByName = "stringToEntryStatus")
    @Mapping(target = "visibility", source = "visibility", qualifiedByName = "stringToMediaVisibility")
    Entry toModel(EntryEntity entity);

    @Mapping(target = "type", source = "type", qualifiedByName = "entryTypeToString")
    @Mapping(target = "status", source = "status", qualifiedByName = "entryStatusToString")
    @Mapping(target = "visibility", source = "visibility", qualifiedByName = "mediaVisibilityToString")
    EntryEntity toEntity(Entry model);

    @Named("stringToEntryType")
    default EntryType stringToEntryType(String value) {
        return value == null ? null : EntryType.valueOf(value);
    }

    @Named("entryTypeToString")
    default String entryTypeToString(EntryType value) {
        return value == null ? null : value.name();
    }

    @Named("stringToEntryStatus")
    default EntryStatus stringToEntryStatus(String value) {
        return value == null ? null : EntryStatus.valueOf(value);
    }

    @Named("entryStatusToString")
    default String entryStatusToString(EntryStatus value) {
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
