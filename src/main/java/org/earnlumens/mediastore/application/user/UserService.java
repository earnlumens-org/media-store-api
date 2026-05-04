package org.earnlumens.mediastore.application.user;

import org.earnlumens.mediastore.domain.user.dto.request.UpdateContentLanguagePreferencesRequest;
import org.earnlumens.mediastore.domain.user.model.User;
import org.earnlumens.mediastore.domain.user.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;
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

    /**
     * Apply a partial update of the user's consumer-side content language
     * preferences. Fields that are {@code null} in the request are not
     * touched. An empty {@code contentLanguages} list is treated as an
     * explicit "no preferred languages" signal (and is persisted as such);
     * combined with {@code showAllLanguages = false} this means feeds will
     * effectively only show {@code multi} content (or nothing if
     * {@code includeMulti = false}). The UI guards against this footgun.
     *
     * @return the saved {@link User} or {@link Optional#empty()} if the
     *         user does not exist.
     */
    public Optional<User> updateContentLanguagePreferences(
            String oauthUserId,
            UpdateContentLanguagePreferencesRequest request
    ) {
        return userRepository.findByOauthUserId(oauthUserId).map(user -> {
            List<String> langs = request.contentLanguages();
            if (langs != null) {
                user.setContentLanguages(List.copyOf(langs));
            }
            if (request.includeMulti() != null) {
                user.setIncludeMulti(request.includeMulti());
            }
            if (request.showAllLanguages() != null) {
                user.setShowAllLanguages(request.showAllLanguages());
            }
            return userRepository.save(user);
        });
    }
}

