package org.earnlumens.mediastore.web.media;

import io.jsonwebtoken.Claims;
import org.earnlumens.mediastore.application.media.EntryUploadService;
import org.earnlumens.mediastore.domain.media.dto.request.CreateEntryRequest;
import org.earnlumens.mediastore.domain.media.dto.request.UpdateEntryStatusRequest;
import org.earnlumens.mediastore.domain.media.dto.response.CreateEntryResponse;
import org.earnlumens.mediastore.infrastructure.security.jwt.AuthTokenFilter;
import org.earnlumens.mediastore.infrastructure.security.jwt.JwtUtils;
import org.earnlumens.mediastore.infrastructure.tenant.TenantResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for {@link EntryController}.
 * Uses standalone MockMvc with AuthTokenFilter wired in (Bearer JWT auth).
 */
class EntryControllerTest {

    private static final String USER_ID = "user-123";
    private static final String TENANT_ID = "earnlumens";
    private static final String ENTRY_ID = "entry-abc";
    private static final String VALID_TOKEN = "valid.bearer.token";

    private MockMvc mockMvc;
    private JwtUtils jwtUtils;
    private TenantResolver tenantResolver;
    private EntryUploadService entryUploadService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();

        jwtUtils = mock(JwtUtils.class);
        tenantResolver = mock(TenantResolver.class);
        entryUploadService = mock(EntryUploadService.class);

        EntryController controller = new EntryController(tenantResolver, entryUploadService);

        AuthTokenFilter authFilter = new AuthTokenFilter();
        ReflectionTestUtils.setField(authFilter, "jwtUtils", jwtUtils);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(authFilter)
                .build();

        when(tenantResolver.resolve(any())).thenReturn(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void configureValidToken() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(USER_ID);
        when(claims.get("name", String.class)).thenReturn("Test User");
        when(claims.get("username", String.class)).thenReturn("testuser");
        when(claims.get("profile_image_url", String.class)).thenReturn(null);
        when(claims.get("oauth_provider", String.class)).thenReturn("x");
        when(claims.get("followers_count", Number.class)).thenReturn(42);

        when(jwtUtils.validateJwtToken(VALID_TOKEN)).thenReturn(true);
        when(jwtUtils.key()).thenReturn(null); // Not needed when we mock the filter chain
    }

    // ─── POST /api/entries ─────────────────────────────────────

    @Test
    void createEntry_noAuth_returns401() throws Exception {
        CreateEntryRequest request = new CreateEntryRequest(
                "Test", null, "VIDEO", true, null);

        mockMvc.perform(post("/api/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Test\",\"type\":\"VIDEO\",\"isPaid\":true}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(entryUploadService);
    }

    @Test
    void createEntry_validAuth_returns201() throws Exception {
        configureValidToken();

        CreateEntryResponse response = new CreateEntryResponse(
                ENTRY_ID, "Test Video", "VIDEO", "DRAFT");
        when(entryUploadService.createEntry(eq(TENANT_ID), eq(USER_ID), any(CreateEntryRequest.class)))
                .thenReturn(response);

        // Build request manually to simulate what AuthTokenFilter needs
        setAuthContext();

        mockMvc.perform(post("/api/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .content("{\"title\":\"Test Video\",\"type\":\"VIDEO\",\"isPaid\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ENTRY_ID))
                .andExpect(jsonPath("$.title").value("Test Video"))
                .andExpect(jsonPath("$.type").value("VIDEO"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    // ─── PATCH /api/entries/{id}/status ────────────────────────

    @Test
    void updateStatus_noAuth_returns401() throws Exception {
        mockMvc.perform(patch("/api/entries/{id}/status", ENTRY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_REVIEW\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(entryUploadService);
    }

    @Test
    void updateStatus_validAuth_returns204() throws Exception {
        configureValidToken();
        setAuthContext();

        when(entryUploadService.updateEntryStatus(
                eq(TENANT_ID), eq(USER_ID), eq(ENTRY_ID), any(UpdateEntryStatusRequest.class)))
                .thenReturn(true);

        mockMvc.perform(patch("/api/entries/{id}/status", ENTRY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .content("{\"status\":\"IN_REVIEW\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void updateStatus_forbidden_returns403() throws Exception {
        configureValidToken();
        setAuthContext();

        when(entryUploadService.updateEntryStatus(
                eq(TENANT_ID), eq(USER_ID), eq(ENTRY_ID), any(UpdateEntryStatusRequest.class)))
                .thenReturn(false);

        mockMvc.perform(patch("/api/entries/{id}/status", ENTRY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .content("{\"status\":\"IN_REVIEW\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    /**
     * Simulate what AuthTokenFilter does: parse the JWT and set an OAuth2User
     * principal into the SecurityContext. In this test setup, AuthTokenFilter runs
     * as a servlet filter, so this helper pre-seeds the context for the controller.
     *
     * Note: Because AuthTokenFilter uses Jwts.parser().verifyWith(key) internally,
     * we cannot easily mock that path. Instead, we rely on the filter being wired in,
     * and the controller reading from SecurityContextHolder which the filter sets.
     */
    private void setAuthContext() {
        // The AuthTokenFilter will parse the Bearer token and set the context.
        // We've mocked jwtUtils.validateJwtToken to return true.
        // However, Jwts.parser() inside the filter calls jwtUtils.key() and parses the
        // real JWT, which will fail with our mock token. So we pre-set the context.
        org.springframework.security.oauth2.core.user.OAuth2UserAuthority authority =
                new org.springframework.security.oauth2.core.user.OAuth2UserAuthority(
                        "ROLE_USER",
                        java.util.Map.of(
                                "id", USER_ID,
                                "name", "Test User",
                                "username", "testuser",
                                "oauth_provider", "x"
                        )
                );
        org.springframework.security.oauth2.core.user.OAuth2User oauth2User =
                new org.springframework.security.oauth2.core.user.DefaultOAuth2User(
                        java.util.Collections.singletonList(authority),
                        java.util.Map.of(
                                "id", USER_ID,
                                "name", "Test User",
                                "username", "testuser",
                                "oauth_provider", "x"
                        ),
                        "id"
                );
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken auth =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        oauth2User, null, oauth2User.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
