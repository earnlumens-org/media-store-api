package org.earnlumens.mediastore.application.space;

import org.earnlumens.mediastore.domain.space.Space;
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
 * </ul>
 *
 * <p>The publish-rule check ({@code whoCanPublish}) is intentionally NOT
 * enforced here yet — it depends on credential data that does not exist on
 * users today. Pinned as a TODO; see {@link #checkPublishRule(Space)}.
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

    public SpaceValidationService(SpaceRepository spaceRepository) {
        this.spaceRepository = spaceRepository;
    }

    /**
     * Validates a publish target list. Returns the de-duplicated, order-
     * preserving list of spaceIds that should be persisted on the entry.
     *
     * @param tenantId   tenant of the calling request (from {@code TenantContext})
     * @param spaceIds   raw list submitted by the client; {@code null}/empty allowed (no spaces)
     * @return de-duplicated valid spaceIds
     * @throws IllegalArgumentException with a stable error code when validation fails
     */
    public List<String> validateForPublish(String tenantId, List<String> spaceIds) {
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

        for (String id : deduped) {
            Space s = byId.get(id);
            if (s.getStatus() == SpaceStatus.ARCHIVED) {
                throw new IllegalArgumentException("SPACE_ARCHIVED");
            }
            if (!s.isAllowPublishing()) {
                throw new IllegalArgumentException("SPACE_PUBLISHING_DISABLED");
            }
            checkPublishRule(s);
        }

        return List.copyOf(deduped);
    }

    /**
     * Placeholder for {@code whoCanPublish} enforcement. Today the only
     * non-{@code ALL} value with concrete meaning is {@code VERIFIED_BLUE},
     * which would require knowing the caller's credential state. That data
     * is not currently available in this service; wire it through when the
     * credentials feature lands.
     */
    private void checkPublishRule(Space space) {
        // Intentional no-op — see method javadoc.
    }
}
