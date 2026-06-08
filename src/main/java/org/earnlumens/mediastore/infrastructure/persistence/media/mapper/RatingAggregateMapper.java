package org.earnlumens.mediastore.infrastructure.persistence.media.mapper;

import org.earnlumens.mediastore.domain.media.model.RatingAggregate;
import org.earnlumens.mediastore.domain.media.model.TargetType;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.RatingAggregateEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface RatingAggregateMapper {

    @Mapping(target = "targetType", source = "targetType", qualifiedByName = "stringToTargetType")
    RatingAggregate toModel(RatingAggregateEntity entity);

    @Mapping(target = "targetType", source = "targetType", qualifiedByName = "targetTypeToString")
    RatingAggregateEntity toEntity(RatingAggregate model);

    @Named("stringToTargetType")
    default TargetType stringToTargetType(String value) {
        return value == null ? null : TargetType.valueOf(value);
    }

    @Named("targetTypeToString")
    default String targetTypeToString(TargetType value) {
        return value == null ? null : value.name();
    }
}
