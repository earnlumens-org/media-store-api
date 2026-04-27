package org.earnlumens.mediastore.web.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.earnlumens.mediastore.application.auth.AuthService;
import org.earnlumens.mediastore.domain.user.model.User;
import org.earnlumens.mediastore.infrastructure.security.jwt.JwtUtils;
import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JwtUtils jwtUtils;
    private final AuthService authService;

    /**
     * Cookie {@code Domain} attribute. Defaults to empty in the base
     * configuration so the refresh cookie is host-only by default — i.e.
     * scoped exactly to the host that emitted it (e.g. {@code acme.earnlumens.org}),
     * never sent to sibling tenants. This is the primary mechanism that keeps
     * sessions isolated per tenant. Override per environment ONLY if you
     * understand the cross-tenant implications.
     */
    @Value("${mediastore.sec.cookieDomain:}")
    private String cookieDomain;

    @Value("${mediastore.sec.cookieSecure:true}")
    private boolean cookieSecure;

    @Value("${mediastore.sec.cookieName}")
    private String cookieName;

    @Value("${mediastore.app.jwtRefreshExpirationMs}")
    private int cookieExpirationMs;

    public AuthController(JwtUtils jwtUtils, AuthService authService) {
        this.jwtUtils = jwtUtils;
        this.authService = authService;
    }

    @PostMapping("/session")
    public ResponseEntity<?> createSession(
            @RequestHeader(value = "UUID", required = false) String tempUUID,
            HttpServletResponse response
    ) {
        if (tempUUID == null || tempUUID.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "UUID inválido"));
        }

        try {
            UUID.fromString(tempUUID);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Formato de UUID inválido"));
        }

        Optional<User> optionalUser = authService.findUserByTempUUID(tempUUID);
        if (optionalUser.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "UUID no válido o ya utilizado"));
        }

        User user = optionalUser.get();

        // Pin the refresh token to the tenant the session is being opened in.
        // TenantFilter has already populated TenantContext from the request
        // host. The refresh endpoint will reject any later /refresh attempt
        // whose request tenant does not match this claim.
        String tenantId = TenantContext.require();

        String accessToken = jwtUtils.generateJwtToken(user);
        String refreshToken = jwtUtils.generateRefreshToken(user, tenantId);

        response.addHeader("Set-Cookie", buildSessionCookie(refreshToken).toString());
        return ResponseEntity.ok(Map.of("accessToken", accessToken));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refreshAccessToken(HttpServletRequest request) {
        try {
            Cookie[] cookies = request.getCookies();
            if (cookies == null || cookies.length == 0) {
                return unauthorizedResponse();
            }

            String refreshToken = Arrays.stream(cookies)
                    .filter(c -> cookieName.equals(c.getName()))
                    .map(Cookie::getValue)
                    .findFirst()
                    .orElse(null);

            if (refreshToken == null || !jwtUtils.validateJwtToken(refreshToken)) {
                return unauthorizedResponse();
            }

            var claims = jwtUtils.getAllClaimsFromToken(refreshToken);

            // Defence in depth: even if the refresh cookie somehow reached a
            // different tenant origin (browser bug, manual cookie copy, future
            // Domain attribute regression), refuse to mint an access token
            // outside the tenant the original session was opened in.
            String tokenTenant = jwtUtils.getTenantIdFromClaims(claims);
            String requestTenant = TenantContext.require();
            if (tokenTenant == null || !tokenTenant.equals(requestTenant)) {
                return unauthorizedResponse();
            }

            String newAccessToken = jwtUtils.generateAccessTokenFromClaims(claims);
            return ResponseEntity.ok(Map.of("accessToken", newAccessToken));
        } catch (Exception e) {
            return unauthorizedResponse();
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletResponse response) {
        // Emit a cookie with the same name + Domain attributes as the live
        // session cookie but Max-Age=0, so the browser drops it immediately.
        response.addHeader("Set-Cookie", buildLogoutCookie().toString());
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    /**
     * Builds the live refresh-token cookie. The {@code Domain} attribute is
     * intentionally omitted when {@link #cookieDomain} is blank, which makes
     * the cookie host-only and per-tenant. Setting a {@code Domain} attribute
     * here would re-introduce cross-tenant cookie leakage.
     */
    private ResponseCookie buildSessionCookie(String refreshToken) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(cookieName, refreshToken)
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/")
                .maxAge(Duration.ofSeconds(cookieExpirationMs / 1000L))
                .sameSite("Strict");
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            builder.domain(cookieDomain);
        }
        return builder.build();
    }

    private ResponseCookie buildLogoutCookie() {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(cookieName, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/")
                .maxAge(0)
                .sameSite("Strict");
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            builder.domain(cookieDomain);
        }
        return builder.build();
    }

    private ResponseEntity<?> unauthorizedResponse() {
        return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
    }
}
