package org.earnlumens.mediastore.infrastructure.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import jakarta.annotation.PostConstruct;
import org.earnlumens.mediastore.domain.user.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

@Component
public class JwtUtils {
    private static final Logger logger = LoggerFactory.getLogger(JwtUtils.class);

    /**
     * Slack added on top of the access-token lifetime when classifying a
     * token by its iat→exp span (see {@link #isAccessTokenShaped}). Covers
     * clock rounding without ever overlapping the refresh-token lifetime
     * (minutes vs. weeks).
     */
    private static final long TOKEN_LIFESPAN_TOLERANCE_MS = 60_000;

    @Value("${mediastore.app.jwtSecret}")
    private String jwtSecret;

    @Value("${mediastore.app.jwtExpirationMs}")
    private int jwtExpirationMs;

    @Value("${mediastore.app.jwtRefreshExpirationMs}")
    private int jwtRefreshExpirationMs;

    @PostConstruct
    void validateConfiguration() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException("mediastore.app.jwtSecret must be configured");
        }
        if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("mediastore.app.jwtSecret must be at least 32 bytes long for HS256");
        }
    }

    public String generateJwtToken(User user) {
        return withLanguageClaims(Jwts.builder(), user)
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
     * Adds the consumer language preferences as access-token claims (P1-1).
     * Feed endpoints read these from the principal instead of hitting the
     * {@code users} collection on every request. Staleness is bounded by the
     * short access-token expiry; the preferences PATCH endpoint additionally
     * returns a freshly minted token so the UI never waits for it.
     */
    private JwtBuilder withLanguageClaims(JwtBuilder builder, User user) {
        return builder
                .claim("content_languages",
                        user.getContentLanguages() == null ? List.of() : user.getContentLanguages())
                .claim("include_multi",
                        user.getIncludeMulti() == null || user.getIncludeMulti())
                .claim("show_all_languages",
                        user.getShowAllLanguages() != null && user.getShowAllLanguages());
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

    /**
     * Returns {@code true} only for tokens that look like genuine short-lived
     * access tokens. Used by {@code AuthTokenFilter} to refuse refresh tokens
     * presented as {@code Authorization: Bearer} credentials — without this
     * gate a leaked 21-day refresh token would work everywhere as an access
     * token, bypassing both the short access expiry and the ban / tenant
     * checks performed on {@code /api/auth/refresh}.
     * <p>
     * Classification is defence in depth on two independent signals:
     * <ul>
     *   <li>refresh tokens always carry the {@code tenant_id} claim;</li>
     *   <li>the iat→exp span of an access token is {@code jwtExpirationMs},
     *       weeks shorter than the refresh lifetime — legacy refresh tokens
     *       minted before the {@code tenant_id} migration are caught here.</li>
     * </ul>
     */
    public boolean isAccessTokenShaped(Claims claims) {
        if (claims.get("tenant_id") != null) {
            return false;
        }
        Date issuedAt = claims.getIssuedAt();
        Date expiration = claims.getExpiration();
        if (issuedAt == null || expiration == null) {
            // Tokens minted by this service always carry both; anything else
            // is not one of our access tokens.
            return false;
        }
        return expiration.getTime() - issuedAt.getTime() <= jwtExpirationMs + TOKEN_LIFESPAN_TOLERANCE_MS;
    }

    public Claims getAllClaimsFromToken(String token) {
        return Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String generateAccessTokenFromClaims(Claims claims) {
        return generateAccessTokenFromClaims(claims, null);
    }

    /**
     * Mints an access token from refresh-token claims. When {@code user} is
     * non-null (the refresh endpoint already loads it for the ban gate), the
     * current language preferences are minted in as claims so feeds keep
     * skipping the per-request user lookup after every token rotation — read
     * fresh from the DB, NOT copied from the (up to weeks old) refresh token.
     */
    public String generateAccessTokenFromClaims(Claims claims, User user) {
        JwtBuilder builder = Jwts.builder()
                .subject(claims.getSubject())
                .claim("name", claims.get("name"))
                .claim("username", claims.get("username"))
                .claim("profile_image_url", claims.get("profile_image_url"))
                .claim("oauth_provider", claims.get("oauth_provider"))
                .claim("followers_count", claims.get("followers_count"));
        if (user != null) {
            builder = withLanguageClaims(builder, user);
        }
        return builder
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
