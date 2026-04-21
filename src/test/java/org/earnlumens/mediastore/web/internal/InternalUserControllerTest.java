package org.earnlumens.mediastore.web.internal;

import org.earnlumens.mediastore.application.user.UserBadgeService;
import org.earnlumens.mediastore.domain.user.model.BadgeType;
import org.earnlumens.mediastore.domain.user.model.User;
import org.earnlumens.mediastore.domain.user.repository.UserRepository;
import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InternalUserController}.
 */
class InternalUserControllerTest {

    private static final String SECRET = "test-admin-api-key";
    private static final String TENANT = "earnlumens";

    private UserRepository userRepository;
    private UserBadgeService userBadgeService;
    private InternalUserController controller;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userBadgeService = mock(UserBadgeService.class);
        controller = new InternalUserController(userRepository, userBadgeService, SECRET);
        TenantContext.set(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private User user(String id, String oauth) {
        User u = new User();
        u.setId(id);
        u.setOauthUserId(oauth);
        return u;
    }

    @Test
    void missingSecret_returns403() {
        ResponseEntity<?> resp = controller.blueCredential(null, "oauth-1", null);
        assertEquals(403, resp.getStatusCode().value());
        verifyNoInteractions(userRepository, userBadgeService);
    }

    @Test
    void wrongSecret_returns403() {
        ResponseEntity<?> resp = controller.blueCredential("bad", "oauth-1", null);
        assertEquals(403, resp.getStatusCode().value());
        verifyNoInteractions(userRepository, userBadgeService);
    }

    @Test
    void correctSecret_userHasU1_returnsActiveTrue() {
        when(userRepository.findByOauthUserId("oauth-1")).thenReturn(Optional.of(user("u-1", "oauth-1")));
        when(userBadgeService.hasActiveBadge(TENANT, "u-1", BadgeType.U1)).thenReturn(true);

        ResponseEntity<?> resp = controller.blueCredential(SECRET, "oauth-1", null);

        assertEquals(200, resp.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertTrue((Boolean) body.get("active"));
    }

    @Test
    void correctSecret_userWithoutU1_returnsActiveFalse() {
        when(userRepository.findByOauthUserId("oauth-1")).thenReturn(Optional.of(user("u-1", "oauth-1")));
        when(userBadgeService.hasActiveBadge(TENANT, "u-1", BadgeType.U1)).thenReturn(false);

        ResponseEntity<?> resp = controller.blueCredential(SECRET, "oauth-1", null);

        assertEquals(200, resp.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertFalse((Boolean) body.get("active"));
    }

    @Test
    void correctSecret_unknownUser_returnsActiveFalse() {
        when(userRepository.findByOauthUserId("oauth-404")).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.blueCredential(SECRET, "oauth-404", null);

        assertEquals(200, resp.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertFalse((Boolean) body.get("active"));
    }

    @Test
    void tenantIdOverride_takesPrecedenceOverContext() {
        when(userRepository.findByOauthUserId("oauth-1")).thenReturn(Optional.of(user("u-1", "oauth-1")));
        when(userBadgeService.hasActiveBadge("other-tenant", "u-1", BadgeType.U1)).thenReturn(true);

        ResponseEntity<?> resp = controller.blueCredential(SECRET, "oauth-1", "other-tenant");

        assertEquals(200, resp.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertTrue((Boolean) body.get("active"));
    }

    @Test
    void blankOauthUserId_returns400() {
        ResponseEntity<?> resp = controller.blueCredential(SECRET, "  ", null);
        assertEquals(400, resp.getStatusCode().value());
        verifyNoInteractions(userRepository, userBadgeService);
    }
}
