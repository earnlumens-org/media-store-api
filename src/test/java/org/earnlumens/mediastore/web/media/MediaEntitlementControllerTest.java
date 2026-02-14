package org.earnlumens.mediastore.web.media;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import org.earnlumens.mediastore.application.media.MediaEntitlementService;
import org.earnlumens.mediastore.domain.media.dto.response.MediaEntitlementResponse;
import org.earnlumens.mediastore.infrastructure.security.jwt.JwtUtils;
import org.earnlumens.mediastore.infrastructure.security.jwt.RefreshCookieAuthFilter;
import org.earnlumens.mediastore.infrastructure.tenant.TenantResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for {@link MediaEntitlementController} + {@link RefreshCookieAuthFilter}.
 * <p>
 * Uses standalone MockMvc with the cookie-auth filter wired in, matching
 * the project's existing testing pattern (see AuthControllerTest).
 * <p>
 * In production the endpoint is additionally protected by Spring Security's
 * {@code .anyRequest().authenticated()} rule (not in permitAll), so
 * unauthenticated requests are blocked at the framework level before
 * reaching the controller.
 */
class MediaEntitlementControllerTest {

    private static final String COOKIE_NAME = "_rFTo_test";
    private static final String VALID_TOKEN = "valid.refresh.token";
    private static final String USER_ID = "oauth-user-123";
    private static final String ENTRY_ID = "entry-abc-456";
    private static final String TENANT_ID = "earnlumens";

    private MockMvc mockMvc;
    private JwtUtils jwtUtils;
    private TenantResolver tenantResolver;
    private MediaEntitlementService mediaEntitlementService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();

        jwtUtils = mock(JwtUtils.class);
        tenantResolver = mock(TenantResolver.class);
        mediaEntitlementService = mock(MediaEntitlementService.class);

        // Controller (no longer depends on JwtUtils)
        MediaEntitlementController controller =
                new MediaEntitlementController(tenantResolver, mediaEntitlementService);

        // Filter (cookie → SecurityContext)
        RefreshCookieAuthFilter cookieFilter = new RefreshCookieAuthFilter();
        ReflectionTestUtils.setField(cookieFilter, "jwtUtils", jwtUtils);
        ReflectionTestUtils.setField(cookieFilter, "cookieName", COOKIE_NAME);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(cookieFilter)
                .build();

        // Default: tenant resolves to TENANT_ID
        when(tenantResolver.resolve(any())).thenReturn(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ─── helpers ───────────────────────────────────────────────

    private void configureValidToken() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(USER_ID);
        when(claims.get("name", String.class)).thenReturn("Test User");
        when(claims.get("username", String.class)).thenReturn("testuser");
        when(claims.get("profile_image_url", String.class)).thenReturn(null);
        when(claims.get("oauth_provider", String.class)).thenReturn("x");
        when(claims.get("followers_count", Number.class)).thenReturn(42);

        when(jwtUtils.validateJwtToken(VALID_TOKEN)).thenReturn(true);
        when(jwtUtils.getAllClaimsFromToken(VALID_TOKEN)).thenReturn(claims);
    }

    private MediaEntitlementResponse allowedResponse() {
        return new MediaEntitlementResponse(
                true,
                "private/media/entry-abc-456/video.mp4",
                "video/mp4",
                "inline; filename=\"video.mp4\"",
                "video.mp4"
        );
    }

    // ─── 1) No cookie → 401 ───────────────────────────────────

    @Test
    void noCookie_returns401() throws Exception {
        mockMvc.perform(get("/api/media/entitlements/{entryId}", ENTRY_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        verifyNoInteractions(mediaEntitlementService);
    }

    @Test
    void invalidCookie_returns401() throws Exception {
        when(jwtUtils.validateJwtToken("bad.token")).thenReturn(false);

        mockMvc.perform(get("/api/media/entitlements/{entryId}", ENTRY_ID)
                        .cookie(new Cookie(COOKIE_NAME, "bad.token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        verify(jwtUtils).validateJwtToken("bad.token");
        verifyNoInteractions(mediaEntitlementService);
    }

    // ─── 2) Valid cookie, not owner, no entitlement → 403 ─────

    @Test
    void validCookie_notOwner_noEntitlement_returns403() throws Exception {
        configureValidToken();
        when(mediaEntitlementService.checkEntitlement(TENANT_ID, USER_ID, ENTRY_ID))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/media/entitlements/{entryId}", ENTRY_ID)
                        .cookie(new Cookie(COOKIE_NAME, VALID_TOKEN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));

        verify(mediaEntitlementService).checkEntitlement(TENANT_ID, USER_ID, ENTRY_ID);
    }

    // ─── 3) Valid cookie, user is owner → 200 allowed ─────────

    @Test
    void validCookie_owner_returns200() throws Exception {
        configureValidToken();
        when(mediaEntitlementService.checkEntitlement(TENANT_ID, USER_ID, ENTRY_ID))
                .thenReturn(Optional.of(allowedResponse()));

        mockMvc.perform(get("/api/media/entitlements/{entryId}", ENTRY_ID)
                        .cookie(new Cookie(COOKIE_NAME, VALID_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.r2Key").value("private/media/entry-abc-456/video.mp4"))
                .andExpect(jsonPath("$.contentType").value("video/mp4"))
                .andExpect(jsonPath("$.contentDisposition").value("inline; filename=\"video.mp4\""))
                .andExpect(jsonPath("$.fileName").value("video.mp4"));

        verify(mediaEntitlementService).checkEntitlement(TENANT_ID, USER_ID, ENTRY_ID);
    }

    // ─── 4) Valid cookie, ACTIVE entitlement → 200 allowed ────

    @Test
    void validCookie_activeEntitlement_returns200() throws Exception {
        configureValidToken();
        when(mediaEntitlementService.checkEntitlement(TENANT_ID, USER_ID, ENTRY_ID))
                .thenReturn(Optional.of(allowedResponse()));

        mockMvc.perform(get("/api/media/entitlements/{entryId}", ENTRY_ID)
                        .cookie(new Cookie(COOKIE_NAME, VALID_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true));

        verify(mediaEntitlementService).checkEntitlement(TENANT_ID, USER_ID, ENTRY_ID);
    }

    // ─── 5) Tenant mismatch → 403 ────────────────────────────

    @Test
    void tenantMismatch_returns403() throws Exception {
        configureValidToken();
        String otherTenant = "other-tenant";
        when(tenantResolver.resolve(any())).thenReturn(otherTenant);

        // Service finds no entry for the mismatched tenant → empty
        when(mediaEntitlementService.checkEntitlement(otherTenant, USER_ID, ENTRY_ID))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/media/entitlements/{entryId}", ENTRY_ID)
                        .cookie(new Cookie(COOKIE_NAME, VALID_TOKEN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));

        // Verify the resolved tenant was passed through (not the entry's tenant)
        verify(mediaEntitlementService).checkEntitlement(eq(otherTenant), eq(USER_ID), eq(ENTRY_ID));
        verify(mediaEntitlementService, never()).checkEntitlement(eq(TENANT_ID), anyString(), anyString());
    }

    // ─── Edge: wrong cookie name is ignored → 401 ────────────

    @Test
    void wrongCookieName_returns401() throws Exception {
        configureValidToken();

        mockMvc.perform(get("/api/media/entitlements/{entryId}", ENTRY_ID)
                        .cookie(new Cookie("wrong_cookie", VALID_TOKEN)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        verifyNoInteractions(mediaEntitlementService);
    }
}
