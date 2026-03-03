package org.earnlumens.mediastore.web.media;

import io.jsonwebtoken.Claims;
import org.earnlumens.mediastore.application.media.FavoriteService;
import org.earnlumens.mediastore.domain.media.dto.response.FavoriteItemResponse;
import org.earnlumens.mediastore.domain.media.dto.response.FavoritePageResponse;
import org.earnlumens.mediastore.domain.media.model.FavoriteItemType;
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
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for {@link FavoriteController} + {@link AuthTokenFilter}.
 *
 * Covers:
 *   - Auth enforcement (no token → 401)
 *   - All 4 endpoints (list, toggle, check, remove)
 *   - Tenant resolution is passed through correctly
 *   - Cross-tenant isolation
 */
class FavoriteControllerTest {

    private static final String VALID_TOKEN = "valid.bearer.token";
    private static final String USER_ID = "oauth-user-123";
    private static final String TENANT_ID = "earnlumens";
    private static final String ENTRY_ID = "entry-abc-456";
    private static final String COLLECTION_ID = "coll-xyz-789";

    private MockMvc mockMvc;
    private JwtUtils jwtUtils;
    private TenantResolver tenantResolver;
    private FavoriteService favoriteService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();

        jwtUtils = mock(JwtUtils.class);
        tenantResolver = mock(TenantResolver.class);
        favoriteService = mock(FavoriteService.class);

        FavoriteController controller = new FavoriteController(tenantResolver, favoriteService);

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

    // ── helpers ───────────────────────────────────────────────

    private void configureValidToken() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(USER_ID);
        when(claims.get("name", String.class)).thenReturn("Test User");
        when(claims.get("username", String.class)).thenReturn("testuser");
        when(claims.get("profile_image_url", String.class)).thenReturn(null);
        when(claims.get("oauth_provider", String.class)).thenReturn("x");
        when(claims.get("followers_count", Number.class)).thenReturn(42);

        when(jwtUtils.validateJwtToken(VALID_TOKEN)).thenReturn(true);
        when(jwtUtils.key()).thenReturn(null);
    }

    /**
     * Pre-seed SecurityContext with an OAuth2User principal.
     * AuthTokenFilter uses Jwts.parser() internally which can't parse mock tokens,
     * so we set the context directly (same pattern as EntryControllerTest).
     */
    private void setAuthContext() {
        OAuth2UserAuthority authority = new OAuth2UserAuthority(
                "ROLE_USER",
                Map.of(
                        "id", USER_ID,
                        "name", "Test User",
                        "username", "testuser",
                        "oauth_provider", "x"
                )
        );
        OAuth2User oauth2User = new DefaultOAuth2User(
                Collections.singletonList(authority),
                Map.of(
                        "id", USER_ID,
                        "name", "Test User",
                        "username", "testuser",
                        "oauth_provider", "x"
                ),
                "id"
        );
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(
                        oauth2User, null, oauth2User.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private FavoritePageResponse samplePage() {
        FavoriteItemResponse item = new FavoriteItemResponse(
                "fav-1", ENTRY_ID, "entry", "video", "Test Video",
                "creator", null, "2026-01-15T10:30:00",
                "public/thumb/entry.jpg", null, 120,
                null, null, true, false, "2026-01-15T10:30:00"
        );
        return new FavoritePageResponse(List.of(item), 0, 24, 1, 1);
    }

    // ─── Auth enforcement ─────────────────────────────────────

    @Test
    void listFavorites_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/favorites"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        verifyNoInteractions(favoriteService);
    }

    @Test
    void toggleFavorite_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/favorites/{itemId}", ENTRY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemType\":\"ENTRY\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(favoriteService);
    }

    @Test
    void checkFavorite_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/favorites/check/{itemId}", ENTRY_ID))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(favoriteService);
    }

    @Test
    void removeFavorite_noToken_returns401() throws Exception {
        mockMvc.perform(delete("/api/favorites/{itemId}", ENTRY_ID))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(favoriteService);
    }

    // ─── GET /api/favorites — paginated list ──────────────────

    @Test
    void listFavorites_authenticated_returnsPage() throws Exception {
        configureValidToken();
        setAuthContext();
        when(favoriteService.listFavorites(TENANT_ID, USER_ID, 0, 24))
                .thenReturn(samplePage());

        mockMvc.perform(get("/api/favorites")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(24))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].itemId").value(ENTRY_ID))
                .andExpect(jsonPath("$.content[0].itemType").value("entry"))
                .andExpect(jsonPath("$.content[0].title").value("Test Video"));

        verify(favoriteService).listFavorites(TENANT_ID, USER_ID, 0, 24);
    }

    @Test
    void listFavorites_customPagination() throws Exception {
        configureValidToken();
        setAuthContext();
        when(favoriteService.listFavorites(TENANT_ID, USER_ID, 2, 10))
                .thenReturn(new FavoritePageResponse(List.of(), 2, 10, 25, 3));

        mockMvc.perform(get("/api/favorites")
                        .param("page", "2")
                        .param("size", "10")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(3));

        verify(favoriteService).listFavorites(TENANT_ID, USER_ID, 2, 10);
    }

    // ─── POST /api/favorites/{itemId} — toggle ───────────────

    @Test
    void toggleFavorite_entry_addsAndReturnsTrue() throws Exception {
        configureValidToken();
        setAuthContext();
        when(favoriteService.toggleFavorite(TENANT_ID, USER_ID, ENTRY_ID, FavoriteItemType.ENTRY))
                .thenReturn(true);

        mockMvc.perform(post("/api/favorites/{itemId}", ENTRY_ID)
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemType\":\"ENTRY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorited").value(true));

        verify(favoriteService).toggleFavorite(TENANT_ID, USER_ID, ENTRY_ID, FavoriteItemType.ENTRY);
    }

    @Test
    void toggleFavorite_collection_removesAndReturnsFalse() throws Exception {
        configureValidToken();
        setAuthContext();
        when(favoriteService.toggleFavorite(TENANT_ID, USER_ID, COLLECTION_ID, FavoriteItemType.COLLECTION))
                .thenReturn(false);

        mockMvc.perform(post("/api/favorites/{itemId}", COLLECTION_ID)
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemType\":\"COLLECTION\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorited").value(false));

        verify(favoriteService).toggleFavorite(TENANT_ID, USER_ID, COLLECTION_ID, FavoriteItemType.COLLECTION);
    }

    @Test
    void toggleFavorite_invalidItemType_returns400() throws Exception {
        configureValidToken();
        setAuthContext();

        mockMvc.perform(post("/api/favorites/{itemId}", ENTRY_ID)
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemType\":\"INVALID\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        verify(favoriteService, never()).toggleFavorite(any(), any(), any(), any());
    }

    // ─── GET /api/favorites/check/{itemId} ────────────────────

    @Test
    void checkFavorite_whenFavorited_returnsTrue() throws Exception {
        configureValidToken();
        setAuthContext();
        when(favoriteService.isFavorite(TENANT_ID, USER_ID, ENTRY_ID)).thenReturn(true);

        mockMvc.perform(get("/api/favorites/check/{itemId}", ENTRY_ID)
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorited").value(true));

        verify(favoriteService).isFavorite(TENANT_ID, USER_ID, ENTRY_ID);
    }

    @Test
    void checkFavorite_whenNotFavorited_returnsFalse() throws Exception {
        configureValidToken();
        setAuthContext();
        when(favoriteService.isFavorite(TENANT_ID, USER_ID, ENTRY_ID)).thenReturn(false);

        mockMvc.perform(get("/api/favorites/check/{itemId}", ENTRY_ID)
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorited").value(false));
    }

    // ─── DELETE /api/favorites/{itemId} ───────────────────────

    @Test
    void removeFavorite_whenExists_removesAndReturnsFalse() throws Exception {
        configureValidToken();
        setAuthContext();
        when(favoriteService.isFavorite(TENANT_ID, USER_ID, ENTRY_ID)).thenReturn(true);
        when(favoriteService.toggleFavorite(TENANT_ID, USER_ID, ENTRY_ID, FavoriteItemType.ENTRY))
                .thenReturn(false);

        mockMvc.perform(delete("/api/favorites/{itemId}", ENTRY_ID)
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorited").value(false));

        verify(favoriteService).isFavorite(TENANT_ID, USER_ID, ENTRY_ID);
        verify(favoriteService).toggleFavorite(TENANT_ID, USER_ID, ENTRY_ID, FavoriteItemType.ENTRY);
    }

    @Test
    void removeFavorite_whenNotExists_isIdempotent() throws Exception {
        configureValidToken();
        setAuthContext();
        when(favoriteService.isFavorite(TENANT_ID, USER_ID, ENTRY_ID)).thenReturn(false);

        mockMvc.perform(delete("/api/favorites/{itemId}", ENTRY_ID)
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorited").value(false));

        verify(favoriteService).isFavorite(TENANT_ID, USER_ID, ENTRY_ID);
        verify(favoriteService, never()).toggleFavorite(any(), any(), any(), any());
    }

    // ─── Tenant isolation at controller level ─────────────────

    @Test
    void listFavorites_passesTenantFromResolver() throws Exception {
        configureValidToken();
        setAuthContext();
        String otherTenant = "other-site";
        when(tenantResolver.resolve(any())).thenReturn(otherTenant);
        when(favoriteService.listFavorites(otherTenant, USER_ID, 0, 24))
                .thenReturn(new FavoritePageResponse(List.of(), 0, 24, 0, 0));

        mockMvc.perform(get("/api/favorites")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk());

        verify(favoriteService).listFavorites(eq(otherTenant), eq(USER_ID), anyInt(), anyInt());
        verify(favoriteService, never()).listFavorites(eq(TENANT_ID), any(), anyInt(), anyInt());
    }

    @Test
    void toggleFavorite_passesTenantFromResolver() throws Exception {
        configureValidToken();
        setAuthContext();
        String otherTenant = "other-site";
        when(tenantResolver.resolve(any())).thenReturn(otherTenant);
        when(favoriteService.toggleFavorite(otherTenant, USER_ID, ENTRY_ID, FavoriteItemType.ENTRY))
                .thenReturn(true);

        mockMvc.perform(post("/api/favorites/{itemId}", ENTRY_ID)
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemType\":\"ENTRY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorited").value(true));

        verify(favoriteService).toggleFavorite(eq(otherTenant), eq(USER_ID), eq(ENTRY_ID), eq(FavoriteItemType.ENTRY));
        verify(favoriteService, never()).toggleFavorite(eq(TENANT_ID), any(), any(), any());
    }

    @Test
    void checkFavorite_passesTenantFromResolver() throws Exception {
        configureValidToken();
        setAuthContext();
        String otherTenant = "other-site";
        when(tenantResolver.resolve(any())).thenReturn(otherTenant);
        when(favoriteService.isFavorite(otherTenant, USER_ID, ENTRY_ID)).thenReturn(true);

        mockMvc.perform(get("/api/favorites/check/{itemId}", ENTRY_ID)
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorited").value(true));

        verify(favoriteService).isFavorite(eq(otherTenant), eq(USER_ID), eq(ENTRY_ID));
        verify(favoriteService, never()).isFavorite(eq(TENANT_ID), any(), any());
    }
}
