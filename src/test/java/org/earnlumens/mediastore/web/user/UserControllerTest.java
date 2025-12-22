package org.earnlumens.mediastore.web.user;

import org.earnlumens.mediastore.application.user.UserService;
import org.earnlumens.mediastore.domain.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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

    @Test
    void getByUsername_whenFound_returns200AndUser() throws Exception {
        User user = new User();
        user.setId("abc");
        user.setUsername("daniel");
        user.setDisplayName("Daniel");

        when(userService.findByUsername("daniel")).thenReturn(Optional.of(user));

        mockMvc.perform(get("/api/user/by-username/daniel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("daniel"))
                .andExpect(jsonPath("$.id").value("abc"));
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
}
