package org.earnlumens.mediastore.infrastructure.persistence.media.mapper;

import org.earnlumens.mediastore.domain.media.model.Rating;
import org.earnlumens.mediastore.domain.media.model.RatingProofType;
import org.earnlumens.mediastore.domain.media.model.TargetType;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.RatingEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface RatingMapper {

    @Mapping(target = "targetType", source = "targetType", qualifiedByName = "stringToTargetType")
    @Mapping(target = "proofType", source = "proofType", qualifiedByName = "stringToProofType")
    Rating toModel(RatingEntity entity);

    @Mapping(target = "targetType", source = "targetType", qualifiedByName = "targetTypeToString")
    @Mapping(target = "proofType", source = "proofType", qualifiedByName = "proofTypeToString")
    RatingEntity toEntity(Rating model);

    @Named("stringToTargetType")
    default TargetType stringToTargetType(String value) {
        return value == null ? null : TargetType.valueOf(value);
    }

    @Named("targetTypeToString")
    default String targetTypeToString(TargetType value) {
        return value == null ? null : value.name();
    }

    @Named("stringToProofType")
    default RatingProofType stringToProofType(String value) {
        return value == null ? null : RatingProofType.valueOf(value);
    }

    @Named("proofTypeToString")
    default String proofTypeToString(RatingProofType value) {
        return value == null ? null : value.name();
    }
}
