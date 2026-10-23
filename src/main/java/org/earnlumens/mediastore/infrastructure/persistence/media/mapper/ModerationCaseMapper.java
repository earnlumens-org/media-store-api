package org.earnlumens.mediastore.infrastructure.persistence.media.mapper;

import org.earnlumens.mediastore.domain.media.model.ModerationCase;
import org.earnlumens.mediastore.domain.media.model.ModerationCaseKind;
import org.earnlumens.mediastore.domain.media.model.ModerationCaseResolution;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.ModerationCaseEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface ModerationCaseMapper {

    @Mapping(target = "kind", source = "kind", qualifiedByName = "stringToKind")
    @Mapping(target = "resolution", source = "resolution", qualifiedByName = "stringToResolution")
    ModerationCase toModel(ModerationCaseEntity entity);

    @Mapping(target = "kind", source = "kind", qualifiedByName = "kindToString")
    @Mapping(target = "resolution", source = "resolution", qualifiedByName = "resolutionToString")
    ModerationCaseEntity toEntity(ModerationCase model);

    @Named("stringToKind")
    default ModerationCaseKind stringToKind(String v) { return v == null ? null : ModerationCaseKind.valueOf(v); }

    @Named("kindToString")
    default String kindToString(ModerationCaseKind v) { return v == null ? null : v.name(); }

    @Named("stringToResolution")
    default ModerationCaseResolution stringToResolution(String v) {
        return v == null ? null : ModerationCaseResolution.valueOf(v);
    }

    @Named("resolutionToString")
    default String resolutionToString(ModerationCaseResolution v) { return v == null ? null : v.name(); }
}
