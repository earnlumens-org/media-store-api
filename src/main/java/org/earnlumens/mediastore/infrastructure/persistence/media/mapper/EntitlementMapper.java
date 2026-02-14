package org.earnlumens.mediastore.infrastructure.persistence.media.mapper;

import org.earnlumens.mediastore.domain.media.model.Entitlement;
import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;
import org.earnlumens.mediastore.domain.media.model.GrantType;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.EntitlementEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface EntitlementMapper {

    @Mapping(target = "status", source = "status", qualifiedByName = "stringToStatus")
    @Mapping(target = "grantType", source = "grantType", qualifiedByName = "stringToGrantType")
    Entitlement toModel(EntitlementEntity entity);

    @Mapping(target = "status", source = "status", qualifiedByName = "statusToString")
    @Mapping(target = "grantType", source = "grantType", qualifiedByName = "grantTypeToString")
    EntitlementEntity toEntity(Entitlement model);

    @Named("stringToStatus")
    default EntitlementStatus stringToStatus(String value) {
        return value == null ? null : EntitlementStatus.valueOf(value);
    }

    @Named("statusToString")
    default String statusToString(EntitlementStatus value) {
        return value == null ? null : value.name();
    }

    @Named("stringToGrantType")
    default GrantType stringToGrantType(String value) {
        return value == null ? null : GrantType.valueOf(value);
    }

    @Named("grantTypeToString")
    default String grantTypeToString(GrantType value) {
        return value == null ? null : value.name();
    }
}
