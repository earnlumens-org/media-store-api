package org.earnlumens.mediastore.web.user;

import org.earnlumens.mediastore.application.user.UserService;
import org.earnlumens.mediastore.domain.user.model.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserControllerTest {

    private MockMvc mockMvc;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userService)).build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void me_whenAuthenticated_returns200AndUser() throws Exception {
        OAuth2User oauth2User = buildOauthUser();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(oauth2User, null, oauth2User.getAuthorities())
        );

        mockMvc.perform(get("/api/user/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("user123"))
                .andExpect(jsonPath("$.displayName").value("Display"))
                .andExpect(jsonPath("$.profileImageUrl").value("https://img"))
                .andExpect(jsonPath("$.followersCount").value(42))
                .andExpect(jsonPath("$.oauthProvider").value("x"));
    }

    @Test
    void me_whenNoAuthentication_returns401() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(get("/api/user/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    void getByUsername_whenFound_returns200AndUser() throws Exception {
        User user = new User();
        user.setId("abc");
        user.setUsername("daniel");
        user.setDisplayName("Daniel");

        when(userService.findByUsername("daniel")).thenReturn(Optional.of(user));

        mockMvc.perform(get("/api/user/by-username/daniel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("daniel"));
    }

    @Test
    void getByUsername_whenMissing_returns404() throws Exception {
        when(userService.findByUsername("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/user/by-username/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("user not found"));
    }

    @Test
    void existsByUsername_returns200AndExistsFlag() throws Exception {
        when(userService.existsByUsername("daniel")).thenReturn(true);

        mockMvc.perform(get("/api/user/exists/daniel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("daniel"))
                .andExpect(jsonPath("$.exists").value(true));
    }

    private OAuth2User buildOauthUser() {
        Map<String, Object> attributes = Map.of(
                "id", "oauth-id",
                "username", "user123",
                "name", "Display",
                "profile_image_url", "https://img",
                "followers_count", 42,
                "oauth_provider", "x"
        );
        OAuth2UserAuthority authority = new OAuth2UserAuthority("ROLE_USER", attributes);
        return new DefaultOAuth2User(List.of(authority), attributes, "id");
    }
}
