package org.earnlumens.mediastore.application.user;

import org.earnlumens.mediastore.domain.user.model.User;
import org.earnlumens.mediastore.domain.user.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    public Boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }

    public Optional<User> findByOauthUserId(String oauthUserId) {
        return userRepository.findByOauthUserId(oauthUserId);
    }

    public Boolean existsByOauthUserId(String oauthUserId) {
        return userRepository.existsByOauthUserId(oauthUserId);
    }

    public User save(User user) {
        return userRepository.save(user);
    }

    public Optional<User> findByTempUUID(String tempUUID) {
        return userRepository.findByTempUUID(tempUUID);
    }
}
