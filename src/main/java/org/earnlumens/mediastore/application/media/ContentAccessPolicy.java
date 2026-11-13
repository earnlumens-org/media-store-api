package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.model.Collection;
import org.earnlumens.mediastore.domain.media.model.CollectionItem;
import org.earnlumens.mediastore.domain.media.model.CollectionStatus;
import org.earnlumens.mediastore.domain.media.model.Entitlement;
import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.GrantType;
import org.earnlumens.mediastore.domain.media.model.Order;
import org.earnlumens.mediastore.domain.media.model.OrderStatus;
import org.earnlumens.mediastore.domain.media.model.TargetType;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The single source of truth for "can this viewer access this content"
 * (MYCELIUM-RESILIENCE.md H3, DATA-MODEL.md §8.3 / §8.4). Both decision
 * points — the CDN entitlement check ({@link MediaEntitlementService}) and
 * the public detail page ({@link PublicEntryService}) — delegate here, so the
 * CDN can never be more permissive than the UI or vice versa.
 *
 * <p>Two orthogonal facts decide access (D-01 / S-03):
 * <ul>
 *   <li>{@code moderationBlock} — non-null ⇒ <b>denied to everyone</b>, the
 *       creator included, in every version (D-16). Step 0.</li>
 *   <li>{@code status} — decides <b>public visibility</b> only. A buyer with
 *       a confirmed purchase retains access in <i>any</i> status the creator
 *       or the review workflow can put the entry in (DRAFT, APPROVED,
 *       IN_REVIEW, REJECTED, SUSPENDED, UNLISTED, ARCHIVED, DELETED).</li>
 * </ul>
 * Free content is public only while {@code PUBLISHED} / {@code UNLISTED}
 * (D-19); elsewhere only its creator opens it. A PURCHASE entitlement grants
 * access only when its backing order is {@code COMPLETED} with an on-chain
 * tx hash (§8.1 invariant 1), on both decision points.
 */
@Service
public class ContentAccessPolicy {

    private static final Logger logger = LoggerFactory.getLogger(ContentAccessPolicy.class);

    /** Statuses in which non-buyers may discover / open the content. */
    public static final Set<EntryStatus> PUBLIC_VISIBLE = EnumSet.of(EntryStatus.PUBLISHED, EntryStatus.UNLISTED);

    /**
     * Result of an evaluation; {@code entitled} is true only when access came
     * from a purchase. {@code rightStart} is then the earliest instant the
     * viewer held a right to the content (direct {@code grantedAt}, or
     * {@code max(grantedAt, addedAt)} per covering collection, S-07) and
     * drives the purchased version (N8-lite); {@code null} otherwise.
     */
    public record Result(AccessDecision decision, boolean owner, boolean entitled, LocalDateTime rightStart) {
        public boolean allowed() { return decision.allowed(); }

        static Result allowedOwner() { return new Result(AccessDecision.ALLOWED, true, false, null); }
        static Result allowedPublic() { return new Result(AccessDecision.ALLOWED, false, false, null); }
        static Result allowedEntitled(LocalDateTime rightStart) {
            return new Result(AccessDecision.ALLOWED, false, true, rightStart);
        }
        static Result denied(AccessDecision d) { return new Result(d, false, false, null); }
    }

    private final EntitlementRepository entitlementRepository;
    private final CollectionRepository collectionRepository;
    private final OrderRepository orderRepository;

    public ContentAccessPolicy(EntitlementRepository entitlementRepository,
                               CollectionRepository collectionRepository,
                               OrderRepository orderRepository) {
        this.entitlementRepository = entitlementRepository;
        this.collectionRepository = collectionRepository;
        this.orderRepository = orderRepository;
    }

    public static boolean isPubliclyVisible(EntryStatus status) {
        return status != null && PUBLIC_VISIBLE.contains(status);
    }

    /**
     * Entry access check (DATA-MODEL.md §8.3).
     *
     * @param viewerUserId OAuth user id of the requester, or {@code null} when anonymous
     */
    public Result evaluate(String tenantId, Entry entry, String viewerUserId) {
        // 0. Moderation block → no one, not even the owner, regardless of version (D-16)
        if (entry.isBlocked()) {
            return Result.denied(AccessDecision.DENIED_BLOCKED);
        }

        // 1. Owner always has access to their own content
        if (viewerUserId != null && viewerUserId.equals(entry.getUserId())) {
            return Result.allowedOwner();
        }

        boolean publiclyVisible = isPubliclyVisible(entry.getStatus());

        // 2. Free content → only while publicly visible (D-19)
        if (!entry.isPaid()) {
            return publiclyVisible ? Result.allowedPublic() : Result.denied(AccessDecision.DENIED_HIDDEN);
        }

        // Paid content: an anonymous viewer can hold no right
        if (viewerUserId != null) {
            // 3. Direct entry entitlement — survives every creator / review status
            Optional<Entitlement> direct = entitlementRepository
                    .findByTenantIdAndUserIdAndEntryIdAndStatus(
                            tenantId, viewerUserId, entry.getId(), EntitlementStatus.ACTIVE);
            boolean directRight = direct.isPresent() && isBackedByCompletedOrder(direct.get());
            // Without versions[] every right resolves to v1 with no computation
            // (S-01); only a versioned entry needs the earliest of both paths.
            if (directRight && entry.getVersions().isEmpty()) {
                return Result.allowedEntitled(direct.get().getGrantedAt());
            }

            // 4. Collection entitlement — union by right date over the
            //    membership history, in ANY collection status (H2 / D-20);
            //    a blocked collection grants nothing.
            Optional<LocalDateTime> viaCollection = collectionRightStart(tenantId, entry.getId(), viewerUserId);
            if (directRight || viaCollection.isPresent()) {
                LocalDateTime start = directRight ? direct.get().getGrantedAt() : null;
                if (viaCollection.isPresent() && (start == null || viaCollection.get().isBefore(start))) {
                    start = viaCollection.get();
                }
                return Result.allowedEntitled(start);
            }
        }

        // 5. Paid, no right
        return Result.denied(publiclyVisible ? AccessDecision.DENIED_LOCKED : AccessDecision.DENIED_HIDDEN);
    }

    /**
     * Collection access check (DATA-MODEL.md §8.4). Collection entitlements are
     * honoured in any collection status for the buyer who holds them.
     */
    public Result evaluateCollection(String tenantId, Collection collection, String viewerUserId) {
        if (collection.isBlocked()) {
            return Result.denied(AccessDecision.DENIED_BLOCKED);
        }
        if (viewerUserId != null && viewerUserId.equals(collection.getUserId())) {
            return Result.allowedOwner();
        }
        boolean publiclyVisible = collection.getStatus() == CollectionStatus.PUBLISHED;
        if (!collection.isPaid()) {
            return publiclyVisible ? Result.allowedPublic() : Result.denied(AccessDecision.DENIED_HIDDEN);
        }
        if (viewerUserId != null) {
            List<Entitlement> ents = entitlementRepository.findByTenantIdAndUserIdAndCollectionIdsAndStatus(
                    tenantId, viewerUserId, List.of(collection.getId()), EntitlementStatus.ACTIVE);
            Optional<Entitlement> backed = ents.stream().filter(this::isBackedByCompletedOrder).findFirst();
            if (backed.isPresent()) {
                return Result.allowedEntitled(backed.get().getGrantedAt());
            }
        }
        return Result.denied(publiclyVisible ? AccessDecision.DENIED_LOCKED : AccessDecision.DENIED_HIDDEN);
    }

    /**
     * Union-by-right-date rule (D-20 / S-04): the viewer reaches {@code entryId}
     * through a collection when some paid, unblocked collection — in any
     * status — holds a membership record of the entry that is active or was
     * removed at or after the viewer's {@code grantedAt}, backed by a
     * confirmed order. Returns the earliest instant such a right reached the
     * entry ({@code max(grantedAt, addedAt)}, S-07), or empty when none does.
     * Nothing is ever written: the history lives in the collection.
     */
    private Optional<LocalDateTime> collectionRightStart(String tenantId, String entryId, String viewerUserId) {
        List<Collection> parents = collectionRepository.findByTenantIdAndItemsEntryId(tenantId, entryId);
        if (parents.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Collection> eligible = parents.stream()
                .filter(Collection::isPaid)
                .filter(c -> !c.isBlocked())
                .collect(Collectors.toMap(Collection::getId, c -> c, (a, b) -> a, LinkedHashMap::new));
        if (eligible.isEmpty()) {
            return Optional.empty();
        }
        List<Entitlement> ents = entitlementRepository
                .findByTenantIdAndUserIdAndCollectionIdsAndStatus(
                        tenantId, viewerUserId, new ArrayList<>(eligible.keySet()), EntitlementStatus.ACTIVE);
        LocalDateTime earliest = null;
        for (Entitlement e : ents) {
            if (e.getTargetType() != null && e.getTargetType() != TargetType.COLLECTION) continue;
            Collection c = eligible.get(e.getCollectionId());
            if (c == null) continue;
            List<CollectionItem> records = c.getItems().stream()
                    .filter(i -> entryId.equals(i.getEntryId()))
                    .filter(i -> i.coversRightSince(e.getGrantedAt()))
                    .toList();
            if (records.isEmpty() || !isBackedByCompletedOrder(e)) continue;
            for (CollectionItem r : records) {
                LocalDateTime start = r.rightStartFor(e.getGrantedAt());
                if (start == null) start = SINCE_FOREVER; // neither grantedAt nor addedAt: historical data
                if (earliest == null || start.isBefore(earliest)) earliest = start;
            }
        }
        return Optional.ofNullable(earliest);
    }

    /** Start of a right whose dates are unknown (historical data): "since forever". */
    static final LocalDateTime SINCE_FOREVER = LocalDateTime.of(1970, 1, 1, 0, 0);

    /**
     * Entry ids the user reaches through their ACTIVE collection entitlements
     * under the union rule (D-20): active records plus records removed at or
     * after the right started; blocked collections grant nothing. Used for
     * batch "unlocked" badges in feeds and the library (no order check — the
     * CDN and the detail page re-verify on access).
     */
    public Set<String> entryIdsCoveredByCollections(String tenantId, String userId) {
        List<Entitlement> ents = entitlementRepository.findAllCollectionEntitlements(
                tenantId, userId, EntitlementStatus.ACTIVE);
        if (ents.isEmpty()) {
            return Set.of();
        }
        Map<String, LocalDateTime> grantedAtByCollection = new LinkedHashMap<>();
        for (Entitlement e : ents) {
            // several entitlements on one collection: the earliest right wins
            grantedAtByCollection.merge(e.getCollectionId(), e.getGrantedAt() == null ? SINCE_FOREVER : e.getGrantedAt(),
                    (a, b) -> a.isBefore(b) ? a : b);
        }
        Set<String> ids = new java.util.HashSet<>();
        for (Collection c : collectionRepository.findByTenantIdAndIdIn(tenantId, new ArrayList<>(grantedAtByCollection.keySet()))) {
            if (c.isBlocked()) continue;
            for (CollectionItem i : c.itemsCoveredBy(grantedAtByCollection.get(c.getId()))) {
                if (i.getEntryId() != null) ids.add(i.getEntryId());
            }
        }
        return ids;
    }

    /**
     * Defence in depth (§8.1 invariant 1): a PURCHASE entitlement releases
     * content only if its order is COMPLETED with an on-chain tx hash. An
     * entitlement that somehow exists without a confirmed payment grants
     * nothing. Non-purchase grants pass through (none are issued today, D-18).
     */
    public boolean isBackedByCompletedOrder(Entitlement entitlement) {
        if (entitlement.getGrantType() != GrantType.PURCHASE) {
            return true;
        }
        if (entitlement.getOrderId() == null) {
            logger.warn("PURCHASE entitlement without orderId — denying access: entitlementId={}",
                    entitlement.getId());
            return false;
        }
        Optional<Order> order = orderRepository.findByTenantIdAndId(
                entitlement.getTenantId(), entitlement.getOrderId());
        boolean valid = order.isPresent()
                && order.get().getStatus() == OrderStatus.COMPLETED
                && order.get().getStellarTxHash() != null;
        if (!valid) {
            logger.warn("PURCHASE entitlement not backed by a confirmed COMPLETED order — denying access: "
                            + "entitlementId={}, orderId={}, orderStatus={}",
                    entitlement.getId(), entitlement.getOrderId(),
                    order.map(o -> o.getStatus().name()).orElse("MISSING"));
        }
        return valid;
    }
}
