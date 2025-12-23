package org.earnlumens.mediastore.web.auth;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import org.earnlumens.mediastore.application.auth.AuthService;
import org.earnlumens.mediastore.domain.user.model.User;
import org.earnlumens.mediastore.infrastructure.security.jwt.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthControllerTest {

    private MockMvc mockMvc;
    private JwtUtils jwtUtils;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        jwtUtils = mock(JwtUtils.class);
        authService = mock(AuthService.class);

        AuthController controller = new AuthController(jwtUtils, authService);

        // Simular @Value para tests standalone
        ReflectionTestUtils.setField(controller, "cookieDomain", "localhost");
        ReflectionTestUtils.setField(controller, "cookieSecure", false);
        ReflectionTestUtils.setField(controller, "cookieExpirationMs", 1814400000); // 21d

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void createSession_whenUuidMissing_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/session"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UUID inválido"));

        verifyNoInteractions(authService);
        verifyNoInteractions(jwtUtils);
    }

    @Test
    void createSession_whenUuidBlank_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/session").header("UUID", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UUID inválido"));

        verifyNoInteractions(authService);
        verifyNoInteractions(jwtUtils);
    }

    @Test
    void createSession_whenUuidInvalidFormat_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/session").header("UUID", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Formato de UUID inválido"));

        verifyNoInteractions(authService);
        verifyNoInteractions(jwtUtils);
    }

    @Test
    void createSession_whenUuidNotFound_returns401() throws Exception {
        String uuid = "123e4567-e89b-12d3-a456-426614174000";
        when(authService.findUserByTempUUID(uuid)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/session").header("UUID", uuid))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UUID no válido o ya utilizado"));

        verify(authService).findUserByTempUUID(uuid);
        verifyNoInteractions(jwtUtils);
    }

    @Test
    void createSession_whenUuidValid_setsCookieAndReturnsAccessToken() throws Exception {
        String uuid = "123e4567-e89b-12d3-a456-426614174000";

        User user = new User();
        user.setOauthUserId("oauth-id");
        user.setUsername("user123");
        user.setDisplayName("Display");
        user.setOauthProvider("x");

        when(authService.findUserByTempUUID(uuid)).thenReturn(Optional.of(user));
        when(jwtUtils.generateJwtToken(user)).thenReturn("access.jwt");
        when(jwtUtils.generateRefreshToken(user)).thenReturn("refresh.jwt");

        mockMvc.perform(post("/api/auth/session").header("UUID", uuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access.jwt"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("_rFTo=refresh.jwt")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("SameSite=Strict")));

        verify(authService).findUserByTempUUID(uuid);
        verify(jwtUtils).generateJwtToken(user);
        verify(jwtUtils).generateRefreshToken(user);
    }

    @Test
    void refreshAccessToken_whenNoCookies_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        verifyNoInteractions(jwtUtils);
    }

    @Test
    void refreshAccessToken_whenMissingRefreshCookie_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("other", "x")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        verifyNoInteractions(jwtUtils);
    }

    @Test
    void refreshAccessToken_whenRefreshInvalid_returns401() throws Exception {
        when(jwtUtils.validateJwtToken("refresh.jwt")).thenReturn(false);

        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("_rFTo", "refresh.jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        verify(jwtUtils).validateJwtToken("refresh.jwt");
        verify(jwtUtils, never()).getAllClaimsFromToken(any());
        verify(jwtUtils, never()).generateAccessTokenFromClaims(any());
    }

    @Test
    void refreshAccessToken_whenRefreshValid_returnsNewAccessToken() throws Exception {
        Claims claims = mock(Claims.class);

        when(jwtUtils.validateJwtToken("refresh.jwt")).thenReturn(true);
        when(jwtUtils.getAllClaimsFromToken("refresh.jwt")).thenReturn(claims);
        when(jwtUtils.generateAccessTokenFromClaims(claims)).thenReturn("new.access.jwt");

        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("_rFTo", "refresh.jwt")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new.access.jwt"));

        verify(jwtUtils).validateJwtToken("refresh.jwt");
        verify(jwtUtils).getAllClaimsFromToken("refresh.jwt");
        verify(jwtUtils).generateAccessTokenFromClaims(claims);
    }
}
