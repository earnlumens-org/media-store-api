package org.earnlumens.mediastore.web.media;

import org.earnlumens.mediastore.application.media.EntryUploadService;
import org.earnlumens.mediastore.domain.media.dto.request.FinalizeUploadRequest;
import org.earnlumens.mediastore.domain.media.dto.request.InitUploadRequest;
import org.earnlumens.mediastore.domain.media.dto.response.FinalizeUploadResponse;
import org.earnlumens.mediastore.domain.media.dto.response.InitUploadResponse;
import org.earnlumens.mediastore.infrastructure.security.jwt.AuthTokenFilter;
import org.earnlumens.mediastore.infrastructure.security.jwt.JwtUtils;
import org.earnlumens.mediastore.infrastructure.tenant.TenantResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for {@link UploadController}.
 * Uses standalone MockMvc with AuthTokenFilter wired in (Bearer JWT auth).
 */
class UploadControllerTest {

    private static final String USER_ID = "user-123";
    private static final String TENANT_ID = "earnlumens";
    private static final String ENTRY_ID = "entry-abc";

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

        UploadController controller = new UploadController(tenantResolver, entryUploadService);

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

    private void setAuthContext() {
        OAuth2UserAuthority authority = new OAuth2UserAuthority(
                "ROLE_USER",
                Map.of("id", USER_ID, "name", "Test User",
                        "username", "testuser", "oauth_provider", "x")
        );
        OAuth2User oauth2User = new DefaultOAuth2User(
                Collections.singletonList(authority),
                Map.of("id", USER_ID, "name", "Test User",
                        "username", "testuser", "oauth_provider", "x"),
                "id"
        );
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(oauth2User, null, oauth2User.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ─── POST /api/uploads/init ────────────────────────────────

    @Test
    void initUpload_noAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/uploads/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryId\":\"e1\",\"fileName\":\"f.mp4\",\"contentType\":\"video/mp4\",\"kind\":\"FULL\",\"fileSizeBytes\":100}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(entryUploadService);
    }

    @Test
    void initUpload_validAuth_returns200() throws Exception {
        setAuthContext();

        InitUploadResponse response = new InitUploadResponse(
                "upload-id-1", "https://r2.example.com/presigned", "private/media/e1/full/uuid-f.mp4");
        when(entryUploadService.initUpload(eq(TENANT_ID), eq(USER_ID), any(InitUploadRequest.class)))
                .thenReturn(Optional.of(response));

        mockMvc.perform(post("/api/uploads/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryId\":\"e1\",\"fileName\":\"f.mp4\",\"contentType\":\"video/mp4\",\"kind\":\"FULL\",\"fileSizeBytes\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadId").value("upload-id-1"))
                .andExpect(jsonPath("$.presignedUrl").value("https://r2.example.com/presigned"))
                .andExpect(jsonPath("$.r2Key").value("private/media/e1/full/uuid-f.mp4"));
    }

    @Test
    void initUpload_forbidden_returns403() throws Exception {
        setAuthContext();

        when(entryUploadService.initUpload(eq(TENANT_ID), eq(USER_ID), any(InitUploadRequest.class)))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/uploads/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryId\":\"e1\",\"fileName\":\"f.mp4\",\"contentType\":\"video/mp4\",\"kind\":\"FULL\",\"fileSizeBytes\":100}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    // ─── POST /api/uploads/finalize ────────────────────────────

    @Test
    void finalizeUpload_noAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/uploads/finalize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"u1\",\"entryId\":\"e1\",\"r2Key\":\"k\",\"contentType\":\"video/mp4\",\"fileName\":\"f.mp4\",\"fileSizeBytes\":100,\"kind\":\"FULL\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(entryUploadService);
    }

    @Test
    void finalizeUpload_validAuth_returns200() throws Exception {
        setAuthContext();

        FinalizeUploadResponse response = new FinalizeUploadResponse("asset-001", "UPLOADED");
        when(entryUploadService.finalizeUpload(eq(TENANT_ID), eq(USER_ID), any(FinalizeUploadRequest.class)))
                .thenReturn(Optional.of(response));

        mockMvc.perform(post("/api/uploads/finalize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"u1\",\"entryId\":\"e1\",\"r2Key\":\"k\",\"contentType\":\"video/mp4\",\"fileName\":\"f.mp4\",\"fileSizeBytes\":100,\"kind\":\"FULL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value("asset-001"))
                .andExpect(jsonPath("$.status").value("UPLOADED"));
    }

    @Test
    void finalizeUpload_forbidden_returns403() throws Exception {
        setAuthContext();

        when(entryUploadService.finalizeUpload(eq(TENANT_ID), eq(USER_ID), any(FinalizeUploadRequest.class)))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/uploads/finalize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"u1\",\"entryId\":\"e1\",\"r2Key\":\"k\",\"contentType\":\"video/mp4\",\"fileName\":\"f.mp4\",\"fileSizeBytes\":100,\"kind\":\"FULL\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }
}
