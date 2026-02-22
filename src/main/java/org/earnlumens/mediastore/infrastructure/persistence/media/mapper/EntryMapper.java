package org.earnlumens.mediastore.infrastructure.persistence.media.mapper;

import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.MediaVisibility;
import org.earnlumens.mediastore.domain.media.model.PaymentSplit;
import org.earnlumens.mediastore.domain.media.model.SplitRole;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.EntryEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.PaymentSplitEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

@Mapper(componentModel = "spring")
public interface EntryMapper {

    @Mapping(target = "type", source = "type", qualifiedByName = "stringToEntryType")
    @Mapping(target = "status", source = "status", qualifiedByName = "stringToEntryStatus")
    @Mapping(target = "visibility", source = "visibility", qualifiedByName = "stringToMediaVisibility")
    @Mapping(target = "paymentSplits", source = "paymentSplits", qualifiedByName = "entitiesToSplits")
    Entry toModel(EntryEntity entity);

    @Mapping(target = "type", source = "type", qualifiedByName = "entryTypeToString")
    @Mapping(target = "status", source = "status", qualifiedByName = "entryStatusToString")
    @Mapping(target = "visibility", source = "visibility", qualifiedByName = "mediaVisibilityToString")
    @Mapping(target = "paymentSplits", source = "paymentSplits", qualifiedByName = "splitsToEntities")
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
