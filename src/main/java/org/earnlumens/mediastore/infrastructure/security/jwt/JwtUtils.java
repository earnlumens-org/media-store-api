package org.earnlumens.mediastore.infrastructure.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.earnlumens.mediastore.domain.user.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtils {
    private static final Logger logger = LoggerFactory.getLogger(JwtUtils.class);

    @Value("${mediastore.app.jwtSecret}")
    private String jwtSecret;

    @Value("${mediastore.app.jwtExpirationMs}")
    private int jwtExpirationMs;

    @Value("${mediastore.app.jwtRefreshExpirationMs}")
    private int jwtRefreshExpirationMs;

    public String generateJwtToken(User user) {
        return Jwts.builder()
                .subject(user.getOauthUserId())
                .claim("name", user.getDisplayName())
                .claim("username", user.getUsername())
                .claim("profile_image_url", user.getProfileImageUrl())
                .claim("oauth_provider", user.getOauthProvider())
                .claim("followers_count", user.getFollowersCount())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtExpirationMs))
                .signWith(key())
                .compact();
    }

    /**
     * Generates a refresh token bound to the tenant that emitted the session.
     * <p>
     * The {@code tenant_id} claim is read back on every {@code /api/auth/refresh}
     * call and must match the tenant resolved from the current request host.
     * This is defence in depth against a refresh cookie that somehow leaks
     * across tenants (e.g. browser bug, manual cookie copy, future Domain
     * attribute regression): even with a syntactically valid refresh token,
     * the backend refuses to mint an access token in a tenant the original
     * session did not belong to.
     *
     * @param user     the authenticated user
     * @param tenantId the tenant the session was opened in. Must be non-null;
     *                 callers should pull this from {@link
     *                 org.earnlumens.mediastore.infrastructure.tenant.TenantContext#require()}.
     */
    public String generateRefreshToken(User user, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required when issuing a refresh token");
        }
        return Jwts.builder()
                .subject(user.getOauthUserId())
                .claim("name", user.getDisplayName())
                .claim("username", user.getUsername())
                .claim("profile_image_url", user.getProfileImageUrl())
                .claim("oauth_provider", user.getOauthProvider())
                .claim("followers_count", user.getFollowersCount())
                .claim("tenant_id", tenantId)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtRefreshExpirationMs))
                .signWith(key())
                .compact();
    }

    /**
     * Returns the {@code tenant_id} claim from a parsed token, or {@code null} if
     * the claim is absent (legacy refresh tokens minted before the migration).
     * Callers MUST treat {@code null} as a mismatch.
     */
    public String getTenantIdFromClaims(Claims claims) {
        Object value = claims.get("tenant_id");
        return value == null ? null : value.toString();
    }

    public Claims getAllClaimsFromToken(String token) {
        return Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String generateAccessTokenFromClaims(Claims claims) {
        return Jwts.builder()
                .subject(claims.getSubject())
                .claim("name", claims.get("name"))
                .claim("username", claims.get("username"))
                .claim("profile_image_url", claims.get("profile_image_url"))
                .claim("oauth_provider", claims.get("oauth_provider"))
                .claim("followers_count", claims.get("followers_count"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtExpirationMs))
                .signWith(key())
                .compact();
    }

    public SecretKey key() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 bytes long for HS256");
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public boolean validateJwtToken(String authToken) {
        try {
            Jwts.parser()
                    .verifyWith(key())
                    .build()
                    .parseSignedClaims(authToken);
            return true;
        } catch (SignatureException e) {
            logger.error("Invalid JWT signature: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            logger.error("Invalid JWT token: {}", e.getMessage());
        } catch (ExpiredJwtException e) {
            logger.error("JWT token is expired: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            logger.error("JWT token is unsupported: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.error("JWT claims string is empty: {}", e.getMessage());
        }
        return false;
    }
}
