package org.earnlumens.mediastore.domain.user.repository;

import org.earnlumens.mediastore.domain.user.model.User;

import java.util.List;
import java.util.Optional;

public interface UserRepository {

    List<User> findAllById(List<String> ids);

    Optional<User> findByUsername(String username);

    Boolean existsByUsername(String username);

    Optional<User> findByOauthUserId(String oauthUserId);

    Boolean existsByOauthUserId(String oauthUserId);

    User save(User user);

    Optional<User> findByTempUUID(String tempUUID);
}
