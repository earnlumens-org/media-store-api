package org.earnlumens.mediastore.infrastructure.persistence.media.mapper;

import org.earnlumens.mediastore.domain.media.model.Collection;
import org.earnlumens.mediastore.domain.media.model.CollectionItem;
import org.earnlumens.mediastore.domain.media.model.CollectionStatus;
import org.earnlumens.mediastore.domain.media.model.CollectionType;
import org.earnlumens.mediastore.domain.media.model.MediaVisibility;
import org.earnlumens.mediastore.domain.media.model.PaymentSplit;
import org.earnlumens.mediastore.domain.media.model.PriceCurrency;
import org.earnlumens.mediastore.domain.media.model.SplitRole;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.CollectionEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.CollectionItemEmbeddable;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.PaymentSplitEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

@Mapper(componentModel = "spring")
public interface CollectionMapper {

    @Mapping(target = "collectionType", source = "collectionType", qualifiedByName = "stringToCollectionType")
    @Mapping(target = "status", source = "status", qualifiedByName = "stringToCollectionStatus")
    @Mapping(target = "visibility", source = "visibility", qualifiedByName = "stringToMediaVisibility")
    @Mapping(target = "priceCurrency", source = "priceCurrency", qualifiedByName = "stringToPriceCurrency")
    @Mapping(target = "paymentSplits", source = "paymentSplits", qualifiedByName = "entitiesToSplits")
    Collection toModel(CollectionEntity entity);

    @Mapping(target = "collectionType", source = "collectionType", qualifiedByName = "collectionTypeToString")
    @Mapping(target = "status", source = "status", qualifiedByName = "collectionStatusToString")
    @Mapping(target = "visibility", source = "visibility", qualifiedByName = "mediaVisibilityToString")
    @Mapping(target = "priceCurrency", source = "priceCurrency", qualifiedByName = "priceCurrencyToString")
    @Mapping(target = "paymentSplits", source = "paymentSplits", qualifiedByName = "splitsToEntities")
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

    @Named("stringToMediaVisibility")
    default MediaVisibility stringToMediaVisibility(String value) {
        return value == null ? null : MediaVisibility.valueOf(value);
    }

    @Named("mediaVisibilityToString")
    default String mediaVisibilityToString(MediaVisibility value) {
        return value == null ? null : value.name();
    }

    @Named("stringToPriceCurrency")
    default PriceCurrency stringToPriceCurrency(String value) {
        return value == null ? null : PriceCurrency.valueOf(value);
    }

    @Named("priceCurrencyToString")
    default String priceCurrencyToString(PriceCurrency value) {
        return value == null ? null : value.name();
    }

    // ── PaymentSplit ↔ PaymentSplitEntity ──

    @Named("entitiesToSplits")
    default List<PaymentSplit> entitiesToSplits(List<PaymentSplitEntity> entities) {
        if (entities == null) return null;
        return entities.stream().map(this::toSplitModel).toList();
    }

    @Named("splitsToEntities")
    default List<PaymentSplitEntity> splitsToEntities(List<PaymentSplit> splits) {
        if (splits == null) return null;
        return splits.stream().map(this::toSplitEntity).toList();
    }

    default PaymentSplit toSplitModel(PaymentSplitEntity entity) {
        if (entity == null) return null;
        PaymentSplit split = new PaymentSplit();
        split.setWallet(entity.getWallet());
        split.setRole(entity.getRole() == null ? null : SplitRole.valueOf(entity.getRole()));
        split.setPercent(entity.getPercent());
        return split;
    }

    default PaymentSplitEntity toSplitEntity(PaymentSplit split) {
        if (split == null) return null;
        PaymentSplitEntity entity = new PaymentSplitEntity();
        entity.setWallet(split.getWallet());
        entity.setRole(split.getRole() == null ? null : split.getRole().name());
        entity.setPercent(split.getPercent());
        return entity;
    }
}
