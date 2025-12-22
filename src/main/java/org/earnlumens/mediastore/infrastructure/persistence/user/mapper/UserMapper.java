package org.earnlumens.mediastore.infrastructure.persistence.user.mapper;

import org.earnlumens.mediastore.domain.user.model.User;
import org.earnlumens.mediastore.infrastructure.persistence.user.entity.UserEntity;
import org.mapstruct.InheritInverseConfiguration;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface UserMapper {

    User toModel(UserEntity entity);

    @InheritInverseConfiguration
    UserEntity toEntity(User user);
}
