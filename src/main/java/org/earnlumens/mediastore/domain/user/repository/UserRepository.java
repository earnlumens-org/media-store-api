package org.earnlumens.mediastore.domain.user.repository;

import org.earnlumens.mediastore.domain.user.model.User;

import java.util.Optional;

public interface UserRepository {

    Optional<User> findByUsername(String username);

    Boolean existsByUsername(String username);

    Optional<User> findByOauthUserId(String oauthUserId);

    Boolean existsByOauthUserId(String oauthUserId);

    User save(User user);

    Optional<User> findByTempUUID(String tempUUID);
}
