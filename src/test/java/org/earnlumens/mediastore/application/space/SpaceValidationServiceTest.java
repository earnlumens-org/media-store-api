package org.earnlumens.mediastore.application.space;

import org.earnlumens.mediastore.application.user.UserBadgeService;
import org.earnlumens.mediastore.domain.space.Space;
import org.earnlumens.mediastore.domain.space.SpacePublishRule;
import org.earnlumens.mediastore.domain.space.SpaceStatus;
import org.earnlumens.mediastore.domain.space.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpaceValidationServiceTest {

    private static final String TENANT = "earnlumens";
    private static final String USER = "oauth-user-1";

    private SpaceRepository spaceRepository;
    private UserBadgeService userBadgeService;
    private SpaceValidationService service;

    @BeforeEach
    void setUp() {
        spaceRepository = mock(SpaceRepository.class);
        userBadgeService = mock(UserBadgeService.class);
        when(userBadgeService.getActiveBadgeKey(any(), any())).thenReturn(Optional.empty());
        service = new SpaceValidationService(spaceRepository, userBadgeService);
    }

    private static Space space(String id, SpaceStatus status, boolean allowPublishing) {
        Space s = new Space();
        s.setId(id);
        s.setTenantId(TENANT);
        s.setStatus(status);
        s.setAllowPublishing(allowPublishing);
        return s;
    }

    @Test
    void nullList_returnsEmpty() {
        assertEquals(List.of(), service.validateForPublish(TENANT, USER, null));
    }

    @Test
    void emptyList_returnsEmpty() {
        assertEquals(List.of(), service.validateForPublish(TENANT, USER, List.of()));
    }

    @Test
    void blanksAndDuplicates_areCollapsed() {
        when(spaceRepository.findByTenantIdAndIdIn(eq(TENANT), any()))
                .thenReturn(List.of(space("a", SpaceStatus.ACTIVE, true)));

        List<String> result = service.validateForPublish(TENANT, USER, java.util.Arrays.asList("a", "", null, "a"));
        assertEquals(List.of("a"), result);
    }

    @Test
    void overTheCap_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.validateForPublish(TENANT, USER, List.of("a", "b", "c", "d", "e", "f")));
        assertEquals("TOO_MANY_SPACES", ex.getMessage());
    }

    @Test
    void unknownSpace_throws() {
        when(spaceRepository.findByTenantIdAndIdIn(eq(TENANT), any()))
                .thenReturn(List.of(space("a", SpaceStatus.ACTIVE, true)));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.validateForPublish(TENANT, USER, List.of("a", "ghost")));
        assertEquals("SPACE_NOT_FOUND", ex.getMessage());
    }

    @Test
    void archivedSpace_throws() {
        when(spaceRepository.findByTenantIdAndIdIn(eq(TENANT), any()))
                .thenReturn(List.of(space("a", SpaceStatus.ARCHIVED, true)));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.validateForPublish(TENANT, USER, List.of("a")));
        assertEquals("SPACE_ARCHIVED", ex.getMessage());
    }

    @Test
    void publishingDisabled_throws() {
        when(spaceRepository.findByTenantIdAndIdIn(eq(TENANT), any()))
                .thenReturn(List.of(space("a", SpaceStatus.ACTIVE, false)));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.validateForPublish(TENANT, USER, List.of("a")));
        assertEquals("SPACE_PUBLISHING_DISABLED", ex.getMessage());
    }

    @Test
    void allValid_returnsDedupedOrderPreservedList() {
        when(spaceRepository.findByTenantIdAndIdIn(eq(TENANT), any())).thenReturn(List.of(
                space("a", SpaceStatus.ACTIVE, true),
                space("b", SpaceStatus.ACTIVE, true),
                space("c", SpaceStatus.ACTIVE, true)
        ));

        List<String> result = service.validateForPublish(TENANT, USER, List.of("c", "a", "b"));
        assertEquals(List.of("c", "a", "b"), result);
    }

    @Test
    void crossTenantIsolation_repositoryFiltersByTenant() {
        // The service trusts the repository to scope by tenantId; we just
        // confirm the call passes through the same tenantId we received.
        when(spaceRepository.findByTenantIdAndIdIn(eq("tenant-x"), any()))
                .thenReturn(List.of(space("a", SpaceStatus.ACTIVE, true)));

        List<String> result = service.validateForPublish("tenant-x", USER, List.of("a"));
        assertEquals(List.of("a"), result);
    }

    // ── whoCanPublish enforcement (hierarchical) ────────────────────────

    private static Space ruledSpace(String id, SpacePublishRule rule) {
        Space s = space(id, SpaceStatus.ACTIVE, true);
        s.setWhoCanPublish(rule);
        return s;
    }

    private void badge(String key) {
        when(userBadgeService.getActiveBadgeKey(eq(TENANT), eq(USER)))
                .thenReturn(Optional.ofNullable(key));
    }

    @Test
    void verifiedBlueRule_rejectsUnbadgedUser() {
        when(spaceRepository.findByTenantIdAndIdIn(eq(TENANT), any()))
                .thenReturn(List.of(ruledSpace("a", SpacePublishRule.VERIFIED_BLUE)));
        badge(null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.validateForPublish(TENANT, USER, List.of("a")));
        assertEquals("SPACE_REQUIRES_VERIFIED_BLUE", ex.getMessage());
    }

    @Test
    void verifiedBlueRule_acceptsAnyBadgeTier() {
        when(spaceRepository.findByTenantIdAndIdIn(eq(TENANT), any()))
                .thenReturn(List.of(ruledSpace("a", SpacePublishRule.VERIFIED_BLUE)));

        for (String key : List.of("u1", "u2", "u3")) {
            badge(key);
            assertEquals(List.of("a"), service.validateForPublish(TENANT, USER, List.of("a")));
        }
    }

    @Test
    void verifiedGoldRule_rejectsBlueUser() {
        when(spaceRepository.findByTenantIdAndIdIn(eq(TENANT), any()))
                .thenReturn(List.of(ruledSpace("a", SpacePublishRule.VERIFIED_GOLD)));
        badge("u1");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.validateForPublish(TENANT, USER, List.of("a")));
        assertEquals("SPACE_REQUIRES_VERIFIED_GOLD", ex.getMessage());
    }

    @Test
    void verifiedGoldRule_acceptsGoldAndAmbassador() {
        when(spaceRepository.findByTenantIdAndIdIn(eq(TENANT), any()))
                .thenReturn(List.of(ruledSpace("a", SpacePublishRule.VERIFIED_GOLD)));

        for (String key : List.of("u2", "u3")) {
            badge(key);
            assertEquals(List.of("a"), service.validateForPublish(TENANT, USER, List.of("a")));
        }
    }

    @Test
    void allRule_acceptsUnbadgedUser() {
        when(spaceRepository.findByTenantIdAndIdIn(eq(TENANT), any()))
                .thenReturn(List.of(ruledSpace("a", SpacePublishRule.ALL)));
        badge(null);

        assertEquals(List.of("a"), service.validateForPublish(TENANT, USER, List.of("a")));
    }
}
