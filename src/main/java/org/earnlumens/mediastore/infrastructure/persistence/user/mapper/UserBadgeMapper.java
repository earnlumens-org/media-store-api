package org.earnlumens.mediastore.infrastructure.persistence.user.mapper;

import org.earnlumens.mediastore.domain.user.model.BadgeAssignedBy;
import org.earnlumens.mediastore.domain.user.model.BadgeAssignmentStatus;
import org.earnlumens.mediastore.domain.user.model.BadgeType;
import org.earnlumens.mediastore.domain.user.model.UserBadgeAssignment;
import org.earnlumens.mediastore.infrastructure.persistence.user.entity.UserBadgeAssignmentEntity;
import org.mapstruct.Mapper;
import org.mapstruct.InheritInverseConfiguration;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface UserBadgeMapper {

    UserBadgeAssignment toModel(UserBadgeAssignmentEntity entity);

    @InheritInverseConfiguration
    UserBadgeAssignmentEntity toEntity(UserBadgeAssignment model);

    default BadgeType mapBadgeType(String value) {
        return value == null ? null : BadgeType.valueOf(value);
    }

    default String mapBadgeTypeToString(BadgeType value) {
        return value == null ? null : value.name();
    }

    default BadgeAssignmentStatus mapStatus(String value) {
        return value == null ? null : BadgeAssignmentStatus.valueOf(value);
    }

    default String mapStatusToString(BadgeAssignmentStatus value) {
        return value == null ? null : value.name();
    }

    default BadgeAssignedBy mapAssignedBy(String value) {
        return value == null ? null : BadgeAssignedBy.valueOf(value);
    }

    default String mapAssignedByToString(BadgeAssignedBy value) {
        return value == null ? null : value.name();
    }
}
