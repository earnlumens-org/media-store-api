package org.earnlumens.mediastore.infrastructure.persistence.user.repository;

import org.earnlumens.mediastore.infrastructure.persistence.user.entity.UserEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface UserMongoRepository extends MongoRepository<UserEntity, String> {

    Optional<UserEntity> findByUsernameIgnoreCase(String username);

    Boolean existsByUsernameIgnoreCase(String username);

    Optional<UserEntity> findByOauthUserId(String oauthUserId);

    Boolean existsByOauthUserId(String oauthUserId);

    Optional<UserEntity> findByTempUUID(String tempUUID);
}
