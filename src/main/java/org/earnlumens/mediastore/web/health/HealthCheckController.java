package org.earnlumens.mediastore.web.health;

import org.earnlumens.mediastore.application.user.UserService;
import org.earnlumens.mediastore.domain.user.model.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
public class HealthCheckController {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckController.class);

    @Value("${spring.profiles.active:NOT_SET}")
    private String activeProfile;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private UserService userService;

    @GetMapping("/public")
    public String getPublicContent() {
        try {
            long count = mongoTemplate.getCollection("founders").countDocuments();
            return "public content | profile=" + activeProfile + " | founders in db=" + count;
        } catch (Exception e) {
            log.warn("HealthCheck Mongo check failed", e);
            return "public content | profile=" + activeProfile + " | db error";
        }
    }

    @PostMapping("/public/test-user")
    public Map<String, Object> testUserImplementation() {
        try {
            // 1. Crear un usuario de prueba
            User testUser = new User();
            testUser.setOauthProvider("test");
            testUser.setOauthUserId("test-oauth-id-" + System.currentTimeMillis());
            testUser.setUsername("testuser");
            testUser.setDisplayName("Test User");
            testUser.setProfileImageUrl("https://example.com/avatar.jpg");
            testUser.setFollowersCount(100);
            testUser.setCreatedAt(LocalDateTime.now());
            testUser.setLastLoginAt(LocalDateTime.now());

            // 2. Guardar usuario
            User savedUser = userService.save(testUser);
            log.info("User saved with id: {}", savedUser.getId());

            // 3. Buscar por oauthUserId
            var foundByOauthId = userService.findByOauthUserId(savedUser.getOauthUserId());

            // 4. Verificar existencia
            boolean exists = userService.existsByOauthUserId(savedUser.getOauthUserId());

            return Map.of(
                "status", "SUCCESS",
                "savedUserId", savedUser.getId(),
                "foundByOauthId", foundByOauthId.isPresent(),
                "existsByOauthId", exists,
                "username", savedUser.getUsername(),
                "message", "User layer implementation works correctly!"
            );
        } catch (Exception e) {
            log.error("Test user implementation failed", e);
            return Map.of(
                "status", "ERROR",
                "message", e.getMessage(),
                "exceptionType", e.getClass().getSimpleName()
            );
        }
    }
}
