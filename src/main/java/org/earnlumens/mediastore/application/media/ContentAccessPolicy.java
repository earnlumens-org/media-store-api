package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.model.Collection;
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

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    /** Result of an evaluation; {@code entitled} is true only when access came from a purchase. */
    public record Result(AccessDecision decision, boolean owner, boolean entitled) {
        public boolean allowed() { return decision.allowed(); }

        static Result allowedOwner() { return new Result(AccessDecision.ALLOWED, true, false); }
        static Result allowedPublic() { return new Result(AccessDecision.ALLOWED, false, false); }
        static Result allowedEntitled() { return new Result(AccessDecision.ALLOWED, false, true); }
        static Result denied(AccessDecision d) { return new Result(d, false, false); }
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
            if (direct.isPresent() && isBackedByCompletedOrder(direct.get())) {
                return Result.allowedEntitled();
            }

            // 4. Collection entitlement. Membership is the live items[] of
            //    PUBLISHED collections until H2 adds the membership history;
            //    a blocked collection grants nothing.
            if (hasCollectionRight(tenantId, entry.getId(), viewerUserId)) {
                return Result.allowedEntitled();
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
            if (ents.stream().anyMatch(this::isBackedByCompletedOrder)) {
                return Result.allowedEntitled();
            }
        }
        return Result.denied(publiclyVisible ? AccessDecision.DENIED_LOCKED : AccessDecision.DENIED_HIDDEN);
    }

    private boolean hasCollectionRight(String tenantId, String entryId, String viewerUserId) {
        List<Collection> parents = collectionRepository
                .findByTenantIdAndStatusAndItemsEntryId(tenantId, CollectionStatus.PUBLISHED, entryId);
        if (parents.isEmpty()) {
            return false;
        }
        List<String> paidIds = parents.stream()
                .filter(Collection::isPaid)
                .filter(c -> !c.isBlocked())
                .map(Collection::getId)
                .toList();
        if (paidIds.isEmpty()) {
            return false;
        }
        List<Entitlement> ents = entitlementRepository
                .findByTenantIdAndUserIdAndCollectionIdsAndStatus(
                        tenantId, viewerUserId, paidIds, EntitlementStatus.ACTIVE);
        return ents.stream()
                .filter(e -> e.getTargetType() == null || e.getTargetType() == TargetType.COLLECTION)
                .anyMatch(this::isBackedByCompletedOrder);
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
