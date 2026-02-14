package org.earnlumens.mediastore.infrastructure.persistence.media.mapper;

import org.earnlumens.mediastore.domain.media.model.Collection;
import org.earnlumens.mediastore.domain.media.model.CollectionItem;
import org.earnlumens.mediastore.domain.media.model.CollectionStatus;
import org.earnlumens.mediastore.domain.media.model.CollectionType;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.CollectionEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.CollectionItemEmbeddable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface CollectionMapper {

    @Mapping(target = "collectionType", source = "collectionType", qualifiedByName = "stringToCollectionType")
    @Mapping(target = "status", source = "status", qualifiedByName = "stringToCollectionStatus")
    Collection toModel(CollectionEntity entity);

    @Mapping(target = "collectionType", source = "collectionType", qualifiedByName = "collectionTypeToString")
    @Mapping(target = "status", source = "status", qualifiedByName = "collectionStatusToString")
    CollectionEntity toEntity(Collection model);

    CollectionItem toModel(CollectionItemEmbeddable embeddable);

    CollectionItemEmbeddable toEntity(CollectionItem item);

    @Named("stringToCollectionType")
    default CollectionType stringToCollectionType(String value) {
        return value == null ? null : CollectionType.valueOf(value);
    }

    @Named("collectionTypeToString")
    default String collectionTypeToString(CollectionType value) {
        return value == null ? null : value.name();
    }

    @Named("stringToCollectionStatus")
    default CollectionStatus stringToCollectionStatus(String value) {
        return value == null ? null : CollectionStatus.valueOf(value);
    }

    @Named("collectionStatusToString")
    default String collectionStatusToString(CollectionStatus value) {
        return value == null ? null : value.name();
    }
}
