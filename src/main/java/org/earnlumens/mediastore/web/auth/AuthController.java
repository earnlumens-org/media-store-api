package org.earnlumens.mediastore.web.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.earnlumens.mediastore.application.auth.AuthService;
import org.earnlumens.mediastore.domain.user.model.User;
import org.earnlumens.mediastore.infrastructure.security.jwt.JwtUtils;
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

    @Value("${store.sec.cookieDomain}")
    private String cookieDomain;

    @Value("${store.sec.cookieSecure}")
    private boolean cookieSecure;

    @Value("${store.app.jwtRefreshExpirationMs}")
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

        String accessToken = jwtUtils.generateJwtToken(user);
        String refreshToken = jwtUtils.generateRefreshToken(user);

        ResponseCookie cookie = ResponseCookie.from("_rFTo", refreshToken)
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/")
                .domain(cookieDomain)
                .maxAge(Duration.ofSeconds(cookieExpirationMs / 1000L))
                .sameSite("Strict")
                .build();

        response.addHeader("Set-Cookie", cookie.toString());
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
                    .filter(cookie -> "_rFTo".equals(cookie.getName()))
                    .map(Cookie::getValue)
                    .findFirst()
                    .orElse(null);

            if (refreshToken == null || !jwtUtils.validateJwtToken(refreshToken)) {
                return unauthorizedResponse();
            }

            var claims = jwtUtils.getAllClaimsFromToken(refreshToken);
            String newAccessToken = jwtUtils.generateAccessTokenFromClaims(claims);
            return ResponseEntity.ok(Map.of("accessToken", newAccessToken));
        } catch (Exception e) {
            return unauthorizedResponse();
        }
    }

    private ResponseEntity<?> unauthorizedResponse() {
        return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
    }
}
