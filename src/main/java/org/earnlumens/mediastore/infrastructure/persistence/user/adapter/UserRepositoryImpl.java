package org.earnlumens.mediastore.infrastructure.persistence.user.adapter;

import org.earnlumens.mediastore.domain.user.model.User;
import org.earnlumens.mediastore.domain.user.repository.UserRepository;
import org.earnlumens.mediastore.infrastructure.persistence.user.entity.UserEntity;
import org.earnlumens.mediastore.infrastructure.persistence.user.mapper.UserMapper;
import org.earnlumens.mediastore.infrastructure.persistence.user.repository.UserMongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class UserRepositoryImpl implements UserRepository {

    private final UserMongoRepository userMongoRepository;
    private final UserMapper userMapper;

    public UserRepositoryImpl(UserMongoRepository userMongoRepository, UserMapper userMapper) {
        this.userMongoRepository = userMongoRepository;
        this.userMapper = userMapper;
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return userMongoRepository.findByUsername(username)
                .map(userMapper::toModel);
    }

    @Override
    public Boolean existsByUsername(String username) {
        return userMongoRepository.existsByUsername(username);
    }

    @Override
    public Optional<User> findByOauthUserId(String oauthUserId) {
        return userMongoRepository.findByOauthUserId(oauthUserId)
                .map(userMapper::toModel);
    }

    @Override
    public Boolean existsByOauthUserId(String oauthUserId) {
        return userMongoRepository.existsByOauthUserId(oauthUserId);
    }

    @Override
    public User save(User user) {
        UserEntity entity = userMapper.toEntity(user);
        UserEntity saved = userMongoRepository.save(entity);
        return userMapper.toModel(saved);
    }

    @Override
    public Optional<User> findByTempUUID(String tempUUID) {
        return userMongoRepository.findByTempUUID(tempUUID)
                .map(userMapper::toModel);
    }
}
