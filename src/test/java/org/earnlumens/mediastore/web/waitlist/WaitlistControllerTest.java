package org.earnlumens.mediastore.web.waitlist;

import org.earnlumens.mediastore.application.waitlist.WaitlistService;
import org.earnlumens.mediastore.domain.waitlist.dto.response.WaitlistStatsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WaitlistControllerTest {

    private MockMvc mockMvc;

    private WaitlistService waitlistService;

        @BeforeEach
        void setUp() {
                waitlistService = mock(WaitlistService.class);
                mockMvc = MockMvcBuilders.standaloneSetup(new WaitlistController(waitlistService)).build();
        }

    @Test
    void subscribe_whenOk_returns200AndMessage() throws Exception {
                doNothing().when(waitlistService).register(any());

        String body = "{" +
                "\"email\":\"test@example.com\"," +
                "\"feedback\":\"hello\"," +
                "\"captchaResponse\":\"token\"" +
                "}";

        mockMvc.perform(post("/api/waitlist/subscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("User registered successfully!"));
    }

    @Test
    void subscribe_whenCaptchaInvalid_returns400AndMessage() throws Exception {
        doThrow(new IllegalArgumentException("CAPTCHA_INVALID"))
                .when(waitlistService)
                                .register(any());

        String body = "{" +
                "\"email\":\"test@example.com\"," +
                "\"feedback\":\"hello\"," +
                "\"captchaResponse\":\"bad\"" +
                "}";

        mockMvc.perform(post("/api/waitlist/subscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Error: Captcha error, try again!"));
    }

    @Test
    void getStats_whenOk_returns200AndStats() throws Exception {
        when(waitlistService.getStats()).thenReturn(new WaitlistStatsResponse(Map.of("2025-01-01", 1L)));

        mockMvc.perform(get("/api/waitlist/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats['2025-01-01']").value(1));
    }

    @Test
        void getStats_whenException_returns500() throws Exception {
        when(waitlistService.getStats()).thenThrow(new RuntimeException("boom"));

        mockMvc.perform(get("/api/waitlist/stats"))
                                .andExpect(status().isInternalServerError());
    }
}
