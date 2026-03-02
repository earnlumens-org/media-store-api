package org.earnlumens.mediastore.infrastructure.persistence.media.mapper;

import org.earnlumens.mediastore.domain.media.model.Favorite;
import org.earnlumens.mediastore.domain.media.model.FavoriteItemType;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.FavoriteEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface FavoriteMapper {

    @Mapping(target = "itemType", source = "itemType", qualifiedByName = "stringToItemType")
    Favorite toModel(FavoriteEntity entity);

    @Mapping(target = "itemType", source = "itemType", qualifiedByName = "itemTypeToString")
    FavoriteEntity toEntity(Favorite model);

    @Named("stringToItemType")
    default FavoriteItemType stringToItemType(String value) {
        return value == null ? null : FavoriteItemType.valueOf(value);
    }

    @Named("itemTypeToString")
    default String itemTypeToString(FavoriteItemType value) {
        return value == null ? null : value.name();
    }
}
