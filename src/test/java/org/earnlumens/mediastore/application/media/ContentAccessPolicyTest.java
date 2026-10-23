package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.model.Collection;
import org.earnlumens.mediastore.domain.media.model.CollectionItem;
import org.earnlumens.mediastore.domain.media.model.CollectionStatus;
import org.earnlumens.mediastore.domain.media.model.Entitlement;
import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.GrantType;
import org.earnlumens.mediastore.domain.media.model.ModerationBlock;
import org.earnlumens.mediastore.domain.media.model.Order;
import org.earnlumens.mediastore.domain.media.model.OrderStatus;
import org.earnlumens.mediastore.domain.media.model.TargetType;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H3 acceptance matrix for {@link ContentAccessPolicy}: status ×
 * moderationBlock × (buyer / anonymous / owner), free-content rule (D-19)
 * and the backing-order defence on the shared decision point.
 */
class ContentAccessPolicyTest {

    private static final String TENANT = "t1";
    private static final String OWNER = "owner";
    private static final String BUYER = "buyer";
    private static final String STRANGER = "stranger";
    private static final String ENTRY = "entry-1";
    private static final String COLL = "coll-1";

    private EntitlementRepository entitlements;
    private CollectionRepository collections;
    private OrderRepository orders;
    private ContentAccessPolicy policy;

    @BeforeEach
    void setUp() {
        entitlements = mock(EntitlementRepository.class);
        collections = mock(CollectionRepository.class);
        orders = mock(OrderRepository.class);
        when(entitlements.findByTenantIdAndUserIdAndEntryIdAndStatus(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(entitlements.findByTenantIdAndUserIdAndCollectionIdsAndStatus(any(), any(), anyList(), any()))
                .thenReturn(List.of());
        when(collections.findByTenantIdAndStatusAndItemsEntryId(any(), any(), any())).thenReturn(List.of());
        policy = new ContentAccessPolicy(entitlements, collections, orders);
    }

    private Entry paid(EntryStatus status) {
        Entry e = new Entry();
        e.setId(ENTRY);
        e.setTenantId(TENANT);
        e.setUserId(OWNER);
        e.setType(EntryType.VIDEO);
        e.setStatus(status);
        e.setPaid(true);
        return e;
    }

    private Entry free(EntryStatus status) {
        Entry e = paid(status);
        e.setPaid(false);
        return e;
    }

    private void stubDirectPurchase(String userId, boolean orderCompleted) {
        Entitlement ent = new Entitlement();
        ent.setId("ent-1");
        ent.setTenantId(TENANT);
        ent.setUserId(userId);
        ent.setEntryId(ENTRY);
        ent.setTargetType(TargetType.ENTRY);
        ent.setGrantType(GrantType.PURCHASE);
        ent.setOrderId("order-1");
        ent.setStatus(EntitlementStatus.ACTIVE);
        when(entitlements.findByTenantIdAndUserIdAndEntryIdAndStatus(TENANT, userId, ENTRY, EntitlementStatus.ACTIVE))
                .thenReturn(Optional.of(ent));
        Order order = new Order();
        order.setId("order-1");
        order.setStatus(orderCompleted ? OrderStatus.COMPLETED : OrderStatus.PENDING);
        order.setStellarTxHash(orderCompleted ? "hash" : null);
        when(orders.findByTenantIdAndId(TENANT, "order-1")).thenReturn(Optional.of(order));
    }

    private void stubCollectionPurchase(String userId, boolean collectionBlocked) {
        Collection c = new Collection();
        c.setId(COLL);
        c.setTenantId(TENANT);
        c.setUserId(OWNER);
        c.setPaid(true);
        c.setStatus(CollectionStatus.PUBLISHED);
        c.setItems(List.of(new CollectionItem(ENTRY, 0)));
        if (collectionBlocked) c.setModerationBlock(new ModerationBlock("case", "r", "mod", LocalDateTime.now()));
        when(collections.findByTenantIdAndStatusAndItemsEntryId(TENANT, CollectionStatus.PUBLISHED, ENTRY))
                .thenReturn(List.of(c));
        Entitlement ent = new Entitlement();
        ent.setId("ent-c");
        ent.setTenantId(TENANT);
        ent.setUserId(userId);
        ent.setCollectionId(COLL);
        ent.setTargetType(TargetType.COLLECTION);
        ent.setGrantType(GrantType.PURCHASE);
        ent.setOrderId("order-c");
        ent.setStatus(EntitlementStatus.ACTIVE);
        when(entitlements.findByTenantIdAndUserIdAndCollectionIdsAndStatus(eq(TENANT), eq(userId), anyList(), eq(EntitlementStatus.ACTIVE)))
                .thenReturn(List.of(ent));
        Order order = new Order();
        order.setId("order-c");
        order.setStatus(OrderStatus.COMPLETED);
        order.setStellarTxHash("hash-c");
        when(orders.findByTenantIdAndId(TENANT, "order-c")).thenReturn(Optional.of(order));
    }

    // ─── Step 0: moderation block beats everything (D-16) ───────

    @ParameterizedTest
    @EnumSource(EntryStatus.class)
    void blockDeniesEveryoneInEveryStatus(EntryStatus status) {
        Entry e = paid(status);
        e.setModerationBlock(new ModerationBlock("case-1", "illegal", "mod", LocalDateTime.now()));
        stubDirectPurchase(BUYER, true);

        assertThat(policy.evaluate(TENANT, e, OWNER).decision()).isEqualTo(AccessDecision.DENIED_BLOCKED);
        assertThat(policy.evaluate(TENANT, e, BUYER).decision()).isEqualTo(AccessDecision.DENIED_BLOCKED);
        assertThat(policy.evaluate(TENANT, e, null).decision()).isEqualTo(AccessDecision.DENIED_BLOCKED);
    }

    @Test
    void blockedFreeContentIsDeniedToEveryone() {
        Entry e = free(EntryStatus.PUBLISHED);
        e.setModerationBlock(new ModerationBlock("case-1", "illegal", "mod", LocalDateTime.now()));
        assertThat(policy.evaluate(TENANT, e, null).decision()).isEqualTo(AccessDecision.DENIED_BLOCKED);
        assertThat(policy.evaluate(TENANT, e, OWNER).decision()).isEqualTo(AccessDecision.DENIED_BLOCKED);
    }

    // ─── Owner ──────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(EntryStatus.class)
    void ownerAlwaysAllowedWithoutBlock(EntryStatus status) {
        ContentAccessPolicy.Result r = policy.evaluate(TENANT, paid(status), OWNER);
        assertThat(r.decision()).isEqualTo(AccessDecision.ALLOWED);
        assertThat(r.owner()).isTrue();
        assertThat(policy.evaluate(TENANT, free(status), OWNER).allowed()).isTrue();
    }

    // ─── Buyer retains in every non-blocked status (BUYERS_RETAIN) ─

    @ParameterizedTest
    @EnumSource(EntryStatus.class)
    void buyerWithConfirmedOrderRetainsAccessInEveryStatus(EntryStatus status) {
        stubDirectPurchase(BUYER, true);
        ContentAccessPolicy.Result r = policy.evaluate(TENANT, paid(status), BUYER);
        assertThat(r.decision()).as(status.name()).isEqualTo(AccessDecision.ALLOWED);
        assertThat(r.entitled()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(EntryStatus.class)
    void collectionBuyerRetainsAccessInEveryEntryStatus(EntryStatus status) {
        stubCollectionPurchase(BUYER, false);
        assertThat(policy.evaluate(TENANT, paid(status), BUYER).decision()).isEqualTo(AccessDecision.ALLOWED);
    }

    @Test
    void blockedCollectionGrantsNothing() {
        stubCollectionPurchase(BUYER, true);
        assertThat(policy.evaluate(TENANT, paid(EntryStatus.PUBLISHED), BUYER).decision())
                .isEqualTo(AccessDecision.DENIED_LOCKED);
    }

    // ─── Backing order defence applies on the shared decision point ─

    @Test
    void purchaseWithoutCompletedOrderGrantsNothing() {
        stubDirectPurchase(BUYER, false);
        assertThat(policy.evaluate(TENANT, paid(EntryStatus.PUBLISHED), BUYER).decision())
                .isEqualTo(AccessDecision.DENIED_LOCKED);
        assertThat(policy.evaluate(TENANT, paid(EntryStatus.ARCHIVED), BUYER).decision())
                .isEqualTo(AccessDecision.DENIED_HIDDEN);
    }

    // ─── Non-buyers: status decides public visibility ───────────

    @Test
    void strangerOnPublicPaidEntryIsLockedNotHidden() {
        assertThat(policy.evaluate(TENANT, paid(EntryStatus.PUBLISHED), STRANGER).decision())
                .isEqualTo(AccessDecision.DENIED_LOCKED);
        assertThat(policy.evaluate(TENANT, paid(EntryStatus.UNLISTED), null).decision())
                .isEqualTo(AccessDecision.DENIED_LOCKED);
    }

    @ParameterizedTest
    @EnumSource(value = EntryStatus.class, names = {"DRAFT", "IN_REVIEW", "APPROVED", "REJECTED", "SUSPENDED", "ARCHIVED", "DELETED"})
    void strangerOnNonPublicPaidEntryIsHidden(EntryStatus status) {
        assertThat(policy.evaluate(TENANT, paid(status), STRANGER).decision()).isEqualTo(AccessDecision.DENIED_HIDDEN);
        assertThat(policy.evaluate(TENANT, paid(status), null).decision()).isEqualTo(AccessDecision.DENIED_HIDDEN);
    }

    // ─── Free content (D-19): public only in PUBLISHED / UNLISTED ──

    @ParameterizedTest
    @EnumSource(value = EntryStatus.class, names = {"PUBLISHED", "UNLISTED"})
    void freePublicContentIsAllowedToAnyone(EntryStatus status) {
        assertThat(policy.evaluate(TENANT, free(status), null).decision()).isEqualTo(AccessDecision.ALLOWED);
        assertThat(policy.evaluate(TENANT, free(status), STRANGER).decision()).isEqualTo(AccessDecision.ALLOWED);
    }

    @ParameterizedTest
    @EnumSource(value = EntryStatus.class, names = {"DRAFT", "IN_REVIEW", "APPROVED", "REJECTED", "SUSPENDED", "ARCHIVED", "DELETED"})
    void freeNonPublicContentIsHiddenFromNonOwners(EntryStatus status) {
        assertThat(policy.evaluate(TENANT, free(status), null).decision()).isEqualTo(AccessDecision.DENIED_HIDDEN);
        assertThat(policy.evaluate(TENANT, free(status), STRANGER).decision()).isEqualTo(AccessDecision.DENIED_HIDDEN);
        assertThat(policy.evaluate(TENANT, free(status), OWNER).decision()).isEqualTo(AccessDecision.ALLOWED);
    }

    // ─── Collections (§8.4) ─────────────────────────────────────

    @Test
    void collectionBlockDeniesOwnerAndBuyer() {
        Collection c = new Collection();
        c.setId(COLL);
        c.setTenantId(TENANT);
        c.setUserId(OWNER);
        c.setPaid(true);
        c.setStatus(CollectionStatus.PUBLISHED);
        c.setModerationBlock(new ModerationBlock("case", "r", "mod", LocalDateTime.now()));
        assertThat(policy.evaluateCollection(TENANT, c, OWNER).decision()).isEqualTo(AccessDecision.DENIED_BLOCKED);
        assertThat(policy.evaluateCollection(TENANT, c, BUYER).decision()).isEqualTo(AccessDecision.DENIED_BLOCKED);
    }
}
