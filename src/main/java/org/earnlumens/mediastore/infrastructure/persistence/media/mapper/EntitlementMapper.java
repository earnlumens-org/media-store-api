package org.earnlumens.mediastore.infrastructure.persistence.media.mapper;

import org.earnlumens.mediastore.domain.media.model.Entitlement;
import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.EntitlementEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface EntitlementMapper {

    @Mapping(target = "status", source = "status", qualifiedByName = "stringToStatus")
    Entitlement toModel(EntitlementEntity entity);

    @Mapping(target = "status", source = "status", qualifiedByName = "statusToString")
    EntitlementEntity toEntity(Entitlement model);

    @Named("stringToStatus")
    default EntitlementStatus stringToStatus(String value) {
        return value == null ? null : EntitlementStatus.valueOf(value);
    }

    @Named("statusToString")
    default String statusToString(EntitlementStatus value) {
        return value == null ? null : value.name();
    }
}
