package org.earnlumens.mediastore.application.space;

import org.earnlumens.mediastore.application.user.UserBadgeService;
import org.earnlumens.mediastore.domain.space.Space;
import org.earnlumens.mediastore.domain.space.SpacePublishRule;
import org.earnlumens.mediastore.domain.space.SpaceStatus;
import org.earnlumens.mediastore.domain.space.repository.SpaceRepository;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates the {@code spaceIds} a creator wants to publish to. Used by
 * {@code EntryUploadService} on create and metadata update.
 *
 * <p><b>Rules</b>
 * <ul>
 *   <li>An entry may target up to {@link #MAX_SPACES_PER_ENTRY} spaces.</li>
 *   <li>Every spaceId must resolve to a space in the same tenant
 *       ({@code SPACE_NOT_FOUND}).</li>
 *   <li>The space must be {@link SpaceStatus#ACTIVE}
 *       ({@code SPACE_ARCHIVED}).</li>
 *   <li>The space must have {@code allowPublishing=true}
 *       ({@code SPACE_PUBLISHING_DISABLED}).</li>
 *   <li>The caller must satisfy the space's {@code whoCanPublish} rule.
 *       Enforcement is hierarchical on the badge ladder
 *       (u3 Ambassador &gt; u2 Gold &gt; u1 Blue &gt; none):
 *       {@code VERIFIED_BLUE} requires any badge
 *       ({@code SPACE_REQUIRES_VERIFIED_BLUE}), {@code VERIFIED_GOLD}
 *       requires Gold or Ambassador ({@code SPACE_REQUIRES_VERIFIED_GOLD}).</li>
 * </ul>
 *
 * <p>All error codes are surfaced as {@link IllegalArgumentException}
 * messages, following the existing {@code EntryUploadService} convention
 * (controller maps them to 400 / 429 / 409).
 */
@Service
public class SpaceValidationService {

    /** Hard cap to prevent abuse / accidental fan-out. */
    public static final int MAX_SPACES_PER_ENTRY = 5;

    private final SpaceRepository spaceRepository;
    private final UserBadgeService userBadgeService;

    public SpaceValidationService(SpaceRepository spaceRepository,
                                  UserBadgeService userBadgeService) {
        this.spaceRepository = spaceRepository;
        this.userBadgeService = userBadgeService;
    }

    /**
     * Validates a publish target list. Returns the de-duplicated, order-
     * preserving list of spaceIds that should be persisted on the entry.
     *
     * @param tenantId   tenant of the calling request (from {@code TenantContext})
     * @param userId     oauthUserId of the publishing creator (for {@code whoCanPublish})
     * @param spaceIds   raw list submitted by the client; {@code null}/empty allowed (no spaces)
     * @return de-duplicated valid spaceIds
     * @throws IllegalArgumentException with a stable error code when validation fails
     */
    public List<String> validateForPublish(String tenantId, String userId, List<String> spaceIds) {
        if (spaceIds == null || spaceIds.isEmpty()) {
            return List.of();
        }

        // De-duplicate while preserving order.
        Set<String> deduped = new LinkedHashSet<>();
        for (String id : spaceIds) {
            if (id != null && !id.isBlank()) deduped.add(id);
        }
        if (deduped.isEmpty()) return List.of();

        if (deduped.size() > MAX_SPACES_PER_ENTRY) {
            throw new IllegalArgumentException("TOO_MANY_SPACES");
        }

        List<Space> resolved = spaceRepository.findByTenantIdAndIdIn(tenantId, List.copyOf(deduped));
        Map<String, Space> byId = resolved.stream()
                .collect(Collectors.toMap(Space::getId, s -> s));

        Set<String> missing = new HashSet<>(deduped);
        missing.removeAll(byId.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("SPACE_NOT_FOUND");
        }

        // Resolve the caller's badge rank once for the whole batch.
        int callerRank = resolveBadgeRank(tenantId, userId);

        for (String id : deduped) {
            Space s = byId.get(id);
            if (s.getStatus() == SpaceStatus.ARCHIVED) {
                throw new IllegalArgumentException("SPACE_ARCHIVED");
            }
            if (!s.isAllowPublishing()) {
                throw new IllegalArgumentException("SPACE_PUBLISHING_DISABLED");
            }
            checkPublishRule(s, callerRank);
        }

        return List.copyOf(deduped);
    }

    /**
     * Enforces {@code whoCanPublish} hierarchically: a higher badge always
     * satisfies a lower requirement (Ambassador can publish anywhere a Gold
     * or Blue user can).
     */
    private void checkPublishRule(Space space, int callerRank) {
        SpacePublishRule rule = space.getWhoCanPublish();
        if (rule == null || rule == SpacePublishRule.ALL) {
            return;
        }
        if (rule == SpacePublishRule.VERIFIED_BLUE && callerRank < 1) {
            throw new IllegalArgumentException("SPACE_REQUIRES_VERIFIED_BLUE");
        }
        if (rule == SpacePublishRule.VERIFIED_GOLD && callerRank < 2) {
            throw new IllegalArgumentException("SPACE_REQUIRES_VERIFIED_GOLD");
        }
    }

    /** Badge ladder rank: none=0, u1=1, u2=2, u3=3. */
    private int resolveBadgeRank(String tenantId, String userId) {
        if (userId == null || userId.isBlank()) {
            return 0;
        }
        return userBadgeService.getActiveBadgeKey(tenantId, userId)
                .map(key -> switch (key) {
                    case "u3" -> 3;
                    case "u2" -> 2;
                    case "u1" -> 1;
                    default -> 0;
                })
                .orElse(0);
    }
}
