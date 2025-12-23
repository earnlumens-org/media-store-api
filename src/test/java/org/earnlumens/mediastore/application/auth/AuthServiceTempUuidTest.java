package org.earnlumens.mediastore.application.auth;

import org.earnlumens.mediastore.application.user.UserService;
import org.earnlumens.mediastore.domain.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceTempUuidTest {

    private UserService userService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        authService = new AuthService(userService);
    }

    @Test
    void generateTempUUID_whenPrincipalIsNotOAuth2User_throws() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn("not-oauth2-user");

        assertThrows(IllegalArgumentException.class, () -> authService.generateTempUUID(authentication));
        verifyNoInteractions(userService);
    }

    @Test
    void generateTempUUID_whenUserExists_overwritesTempUuidAndSetsCreatedAt() {
        OAuth2User oauth2User = mock(OAuth2User.class);
        when(oauth2User.getAttribute("id")).thenReturn("oauth-id");
        when(oauth2User.getAttribute("name")).thenReturn("Display Name");
        when(oauth2User.getAttribute("username")).thenReturn("user123");
        when(oauth2User.getAttribute("oauth_provider")).thenReturn("x");
        when(oauth2User.getAttribute("profile_image_url")).thenReturn("https://example.com/avatar_normal.png");
        when(oauth2User.getAttribute("public_metrics")).thenReturn(java.util.Map.of("followers_count", 123));

        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(oauth2User);

        User existing = new User();
        existing.setId("u1");
        existing.setTempUUID("old");
        existing.setTempUUIDCreatedAt(Instant.EPOCH);

        when(userService.findByOauthUserId("oauth-id")).thenReturn(Optional.of(existing));
        when(userService.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        String uuid = authService.generateTempUUID(authentication);

        assertNotNull(uuid);
        assertNotEquals("old", existing.getTempUUID());
        assertNotNull(existing.getTempUUIDCreatedAt());
        assertTrue(existing.getTempUUIDCreatedAt().isAfter(Instant.EPOCH));
        verify(userService).save(existing);
    }

    @Test
    void generateTempUUID_whenUserMissing_createsNewUserWithTempUuidAndCreatedAt() {
        OAuth2User oauth2User = mock(OAuth2User.class);
        when(oauth2User.getAttribute("id")).thenReturn("oauth-id");
        when(oauth2User.getAttribute("name")).thenReturn("Display Name");
        when(oauth2User.getAttribute("username")).thenReturn("user123");
        when(oauth2User.getAttribute("oauth_provider")).thenReturn("x");
        when(oauth2User.getAttribute("profile_image_url")).thenReturn("https://example.com/avatar_normal.png");
        when(oauth2User.getAttribute("public_metrics")).thenReturn(java.util.Map.of("followers_count", 123));

        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(oauth2User);

        when(userService.findByOauthUserId("oauth-id")).thenReturn(Optional.empty());
        when(userService.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        String uuid = authService.generateTempUUID(authentication);

        assertNotNull(uuid);
        verify(userService).save(argThat(u ->
                "oauth-id".equals(u.getOauthUserId())
                        && "x".equals(u.getOauthProvider())
                        && "user123".equals(u.getUsername())
                        && u.getTempUUID() != null
                        && u.getTempUUIDCreatedAt() != null
        ));
    }

    @Test
    void findUserByTempUUID_whenMissing_returnsEmptyAndDoesNotSave() {
        when(userService.findByTempUUID("uuid")).thenReturn(Optional.empty());

        Optional<User> result = authService.findUserByTempUUID("uuid");

        assertTrue(result.isEmpty());
        verify(userService, never()).save(any());
    }

    @Test
    void findUserByTempUUID_whenValid_notExpired_returnsUserAndInvalidatesTempUuid() {
        User user = new User();
        user.setId("u1");
        user.setTempUUID("uuid");
        user.setTempUUIDCreatedAt(Instant.now().minusSeconds(30));

        when(userService.findByTempUUID("uuid")).thenReturn(Optional.of(user));
        when(userService.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<User> result = authService.findUserByTempUUID("uuid");

        assertTrue(result.isPresent());
        assertEquals("u1", result.get().getId());
        assertNull(user.getTempUUID());
        assertNull(user.getTempUUIDCreatedAt());
        verify(userService, times(1)).save(user);
    }

    @Test
    void findUserByTempUUID_whenExpired_invalidatesAndReturnsEmpty() {
        User user = new User();
        user.setId("u1");
        user.setTempUUID("uuid");
        user.setTempUUIDCreatedAt(Instant.now().minus(Duration.ofMinutes(3)));

        when(userService.findByTempUUID("uuid")).thenReturn(Optional.of(user));
        when(userService.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<User> result = authService.findUserByTempUUID("uuid");

        assertTrue(result.isEmpty());
        assertNull(user.getTempUUID());
        assertNull(user.getTempUUIDCreatedAt());
        verify(userService, times(1)).save(user);
    }

    @Test
    void findUserByTempUUID_whenCreatedAtMissing_treatsAsExpired_invalidatesAndReturnsEmpty() {
        User user = new User();
        user.setId("u1");
        user.setTempUUID("uuid");
        user.setTempUUIDCreatedAt(null);

        when(userService.findByTempUUID("uuid")).thenReturn(Optional.of(user));
        when(userService.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<User> result = authService.findUserByTempUUID("uuid");

        assertTrue(result.isEmpty());
        assertNull(user.getTempUUID());
        assertNull(user.getTempUUIDCreatedAt());
        verify(userService, times(1)).save(user);
    }
}
