package org.earnlumens.mediastore.infrastructure.security.jwt;

import io.jsonwebtoken.Claims;
import org.earnlumens.mediastore.domain.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilsTest {

    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils();
        // 32+ bytes (HS256 requirement in JwtUtils.key())
        ReflectionTestUtils.setField(jwtUtils, "jwtSecret", "0123456789abcdef0123456789abcdef");
        // Defaults for tests; individual tests may override
        ReflectionTestUtils.setField(jwtUtils, "jwtExpirationMs", 1_000);
        ReflectionTestUtils.setField(jwtUtils, "jwtRefreshExpirationMs", 2_000);
    }

    @Test
    void key_whenSecretTooShort_throws() {
        ReflectionTestUtils.setField(jwtUtils, "jwtSecret", "short-secret");
        assertThrows(IllegalArgumentException.class, () -> jwtUtils.key());
    }

    @Test
    void generateJwtToken_setsSubjectAndClaimsAndExpirationApprox() {
        User user = new User();
        user.setOauthUserId("oauth-id");
        user.setDisplayName("Display");
        user.setUsername("user123");
        user.setProfileImageUrl("https://example.com/a.png");
        user.setOauthProvider("x");
        user.setFollowersCount(42);

        String token = jwtUtils.generateJwtToken(user);
        assertNotNull(token);

        Claims claims = jwtUtils.getAllClaimsFromToken(token);

        assertEquals("oauth-id", claims.getSubject());
        assertEquals("Display", claims.get("name"));
        assertEquals("user123", claims.get("username"));
        assertEquals("https://example.com/a.png", claims.get("profile_image_url"));
        assertEquals("x", claims.get("oauth_provider"));
        assertEquals(42, ((Number) claims.get("followers_count")).intValue());

        Date iat = claims.getIssuedAt();
        Date exp = claims.getExpiration();
        assertNotNull(iat);
        assertNotNull(exp);

        long deltaMs = exp.getTime() - iat.getTime();
        // Allow jitter due to system clock granularity and execution time
        assertTrue(deltaMs >= 900 && deltaMs <= 2_500, "expiration delta out of expected range: " + deltaMs);
    }

    @Test
    void generateRefreshToken_usesRefreshExpirationApprox() {
        ReflectionTestUtils.setField(jwtUtils, "jwtRefreshExpirationMs", 5_000);

        User user = new User();
        user.setOauthUserId("oauth-id");
        user.setDisplayName("Display");
        user.setUsername("user123");
        user.setProfileImageUrl("https://example.com/a.png");
        user.setOauthProvider("x");
        user.setFollowersCount(42);

        String token = jwtUtils.generateRefreshToken(user);
        Claims claims = jwtUtils.getAllClaimsFromToken(token);

        long deltaMs = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
        assertTrue(deltaMs >= 4_500 && deltaMs <= 7_000, "refresh expiration delta out of expected range: " + deltaMs);
    }

    @Test
    void validateJwtToken_whenTampered_returnsFalse() {
        User user = new User();
        user.setOauthUserId("oauth-id");
        user.setDisplayName("Display");
        user.setUsername("user123");
        user.setProfileImageUrl("https://example.com/a.png");
        user.setOauthProvider("x");
        user.setFollowersCount(42);

        String token = jwtUtils.generateJwtToken(user);
        assertTrue(jwtUtils.validateJwtToken(token));

        String[] parts = token.split("\\.");
        assertEquals(3, parts.length);

        String signature = parts[2];
        int indexToFlip = signature.length() / 2;
        char original = signature.charAt(indexToFlip);
        char replacement = (original == 'a') ? 'b' : 'a';

        String tamperedSignature = signature.substring(0, indexToFlip) + replacement + signature.substring(indexToFlip + 1);
        String tampered = parts[0] + "." + parts[1] + "." + tamperedSignature;

        assertFalse(jwtUtils.validateJwtToken(tampered));
    }

    @Test
    void generateAccessTokenFromClaims_reusesSubjectAndClaims() {
        User user = new User();
        user.setOauthUserId("oauth-id");
        user.setDisplayName("Display");
        user.setUsername("user123");
        user.setProfileImageUrl("https://example.com/a.png");
        user.setOauthProvider("x");
        user.setFollowersCount(42);

        String refresh = jwtUtils.generateRefreshToken(user);
        Claims refreshClaims = jwtUtils.getAllClaimsFromToken(refresh);

        String access = jwtUtils.generateAccessTokenFromClaims(refreshClaims);
        Claims accessClaims = jwtUtils.getAllClaimsFromToken(access);

        assertEquals(refreshClaims.getSubject(), accessClaims.getSubject());
        assertEquals(refreshClaims.get("name"), accessClaims.get("name"));
        assertEquals(refreshClaims.get("username"), accessClaims.get("username"));
        assertEquals(refreshClaims.get("profile_image_url"), accessClaims.get("profile_image_url"));
        assertEquals(refreshClaims.get("oauth_provider"), accessClaims.get("oauth_provider"));
        assertEquals(((Number) refreshClaims.get("followers_count")).intValue(), ((Number) accessClaims.get("followers_count")).intValue());

        // access token uses jwtExpirationMs
        long accessDeltaMs = accessClaims.getExpiration().getTime() - accessClaims.getIssuedAt().getTime();
        assertTrue(accessDeltaMs >= 900 && accessDeltaMs <= 2_500);

        // refresh token uses jwtRefreshExpirationMs
        long refreshDeltaMs = refreshClaims.getExpiration().getTime() - refreshClaims.getIssuedAt().getTime();
        assertTrue(refreshDeltaMs >= 1_500 && refreshDeltaMs <= 3_500);
    }
}
