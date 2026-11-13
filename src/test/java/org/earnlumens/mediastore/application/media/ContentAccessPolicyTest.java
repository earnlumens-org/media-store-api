package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.model.Collection;
import org.earnlumens.mediastore.domain.media.model.CollectionItem;
import org.earnlumens.mediastore.domain.media.model.CollectionStatus;
import org.earnlumens.mediastore.domain.media.model.Entitlement;
import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.EntryVersion;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H3 acceptance matrix for {@link ContentAccessPolicy}: status ×
 * moderationBlock × (buyer / anonymous / owner), free-content rule (D-19)
 * and the backing-order defence on the shared decision point. H2 adds the
 * union-by-right-date scenarios (D-20) and N8-lite the right start (S-07).
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
        when(collections.findByTenantIdAndItemsEntryId(any(), any())).thenReturn(List.of());
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
        stubCollectionPurchase(userId, c, null);
    }

    /** Collection purchase with an explicit membership history and right date. */
    private void stubCollectionPurchase(String userId, Collection c, LocalDateTime grantedAt) {
        when(collections.findByTenantIdAndItemsEntryId(TENANT, ENTRY)).thenReturn(List.of(c));
        Entitlement ent = new Entitlement();
        ent.setId("ent-c");
        ent.setTenantId(TENANT);
        ent.setUserId(userId);
        ent.setCollectionId(c.getId());
        ent.setTargetType(TargetType.COLLECTION);
        ent.setGrantType(GrantType.PURCHASE);
        ent.setOrderId("order-c");
        ent.setStatus(EntitlementStatus.ACTIVE);
        ent.setGrantedAt(grantedAt);
        when(entitlements.findByTenantIdAndUserIdAndCollectionIdsAndStatus(eq(TENANT), eq(userId), anyList(), eq(EntitlementStatus.ACTIVE)))
                .thenReturn(List.of(ent));
        Order order = new Order();
        order.setId("order-c");
        order.setStatus(OrderStatus.COMPLETED);
        order.setStellarTxHash("hash-c");
        when(orders.findByTenantIdAndId(TENANT, "order-c")).thenReturn(Optional.of(order));
    }

    private static Collection paidCollection(CollectionStatus status, CollectionItem... items) {
        Collection c = new Collection();
        c.setId(COLL);
        c.setTenantId(TENANT);
        c.setUserId(OWNER);
        c.setPaid(true);
        c.setStatus(status);
        c.setItems(new java.util.ArrayList<>(List.of(items)));
        return c;
    }

    private static CollectionItem removedItem(String entryId, LocalDateTime addedAt, LocalDateTime removedAt) {
        CollectionItem i = new CollectionItem(entryId, 0, addedAt);
        i.setRemovedAt(removedAt);
        i.setPosition(null);
        return i;
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
    // ─── H2: union by right date (D-20 / S-04) ────────────────────

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Test
    void d20_itemPresentAtPurchaseThenRemoved_buyerRetains() {
        Collection c = paidCollection(CollectionStatus.PUBLISHED, removedItem(ENTRY, null, T0.plusDays(10)));
        stubCollectionPurchase(BUYER, c, T0);
        ContentAccessPolicy.Result r = policy.evaluate(TENANT, paid(EntryStatus.PUBLISHED), BUYER);
        assertThat(r.decision()).isEqualTo(AccessDecision.ALLOWED);
        assertThat(r.entitled()).isTrue();
    }

    @Test
    void d20_itemAddedAfterPurchaseThenRemoved_buyerRetains() {
        // the case the snapshot model could not cover
        Collection c = paidCollection(CollectionStatus.PUBLISHED, removedItem(ENTRY, T0.plusDays(5), T0.plusDays(10)));
        stubCollectionPurchase(BUYER, c, T0);
        ContentAccessPolicy.Result r = policy.evaluate(TENANT, paid(EntryStatus.PUBLISHED), BUYER);
        assertThat(r.decision()).isEqualTo(AccessDecision.ALLOWED);
        assertThat(r.rightStart()).isEqualTo(T0.plusDays(5)); // max(grantedAt, addedAt)
    }

    @Test
    void d20_itemRemovedBeforePurchase_laterBuyerDoesNotAcquire() {
        Collection c = paidCollection(CollectionStatus.PUBLISHED, removedItem(ENTRY, null, T0.minusDays(1)));
        stubCollectionPurchase(BUYER, c, T0);
        assertThat(policy.evaluate(TENANT, paid(EntryStatus.PUBLISHED), BUYER).decision())
                .isEqualTo(AccessDecision.DENIED_LOCKED);
    }

    @Test
    void d20_removalAtExactlyGrantedAt_favoursTheBuyer() {
        Collection c = paidCollection(CollectionStatus.PUBLISHED, removedItem(ENTRY, null, T0));
        stubCollectionPurchase(BUYER, c, T0);
        assertThat(policy.evaluate(TENANT, paid(EntryStatus.PUBLISHED), BUYER).allowed()).isTrue();
    }

    @Test
    void d20_itemRemovedAndReAdded_everyBuyerHasAccess() {
        Collection c = paidCollection(CollectionStatus.PUBLISHED,
                removedItem(ENTRY, null, T0.minusDays(1)), new CollectionItem(ENTRY, 0, T0.plusDays(3)));
        stubCollectionPurchase(BUYER, c, T0); // bought while removed, before re-add
        ContentAccessPolicy.Result r = policy.evaluate(TENANT, paid(EntryStatus.PUBLISHED), BUYER);
        assertThat(r.allowed()).isTrue();
        assertThat(r.rightStart()).isEqualTo(T0.plusDays(3));
    }

    @ParameterizedTest
    @EnumSource(value = CollectionStatus.class)
    void d20_collectionInAnyStatusKeepsGrantingToItsBuyer(CollectionStatus status) {
        Collection c = paidCollection(status, new CollectionItem(ENTRY, 0));
        stubCollectionPurchase(BUYER, c, T0);
        assertThat(policy.evaluate(TENANT, paid(EntryStatus.PUBLISHED), BUYER).allowed()).as(status.name()).isTrue();
        assertThat(policy.evaluateCollection(TENANT, c, BUYER).allowed()).as(status.name()).isTrue();
    }

    @Test
    void d20_historicalRecordsWithoutDatesMeanSinceForever() {
        Collection c = paidCollection(CollectionStatus.ARCHIVED, new CollectionItem(ENTRY, 0));
        stubCollectionPurchase(BUYER, c, null);
        ContentAccessPolicy.Result r = policy.evaluate(TENANT, paid(EntryStatus.PUBLISHED), BUYER);
        assertThat(r.allowed()).isTrue();
        assertThat(r.rightStart()).isEqualTo(ContentAccessPolicy.SINCE_FOREVER);
    }

    @Test
    void d20_evaluationNeverWritesEntitlements() {
        Collection c = paidCollection(CollectionStatus.PUBLISHED, removedItem(ENTRY, null, T0.plusDays(1)));
        stubCollectionPurchase(BUYER, c, T0);
        policy.evaluate(TENANT, paid(EntryStatus.PUBLISHED), BUYER);
        verify(entitlements, never()).save(any());
        verify(entitlements, never()).upsertPurchase(any());
        verify(collections, never()).save(any());
    }

    // ─── N8-lite: right start feeds the purchased version (S-07) ─────

    private static Entry versioned(LocalDateTime v2ApprovedAt) {
        Entry e = new Entry();
        e.setId(ENTRY);
        e.setTenantId(TENANT);
        e.setUserId(OWNER);
        e.setType(EntryType.RESOURCE);
        e.setStatus(EntryStatus.PUBLISHED);
        e.setPaid(true);
        EntryVersion v1 = new EntryVersion(); v1.setVersion(1); v1.setApprovedAt(T0.minusDays(30));
        EntryVersion v2 = new EntryVersion(); v2.setVersion(2); v2.setApprovedAt(v2ApprovedAt);
        e.setVersions(new java.util.ArrayList<>(List.of(v1, v2)));
        e.setCurrentVersion(2);
        return e;
    }

    @Test
    void s07_unversionedEntry_directRightShortCircuits_noCollectionLookup() {
        stubDirectPurchase(BUYER, true);
        ContentAccessPolicy.Result r = policy.evaluate(TENANT, paid(EntryStatus.PUBLISHED), BUYER);
        assertThat(r.entitled()).isTrue();
        verify(collections, never()).findByTenantIdAndItemsEntryId(any(), any());
    }

    @Test
    void s07_sameEntryByTwoPaths_rightStartIsTheEarliest() {
        stubDirectPurchase(BUYER, true); // grantedAt null in this stub → set it
        Entitlement direct = entitlements.findByTenantIdAndUserIdAndEntryIdAndStatus(TENANT, BUYER, ENTRY, EntitlementStatus.ACTIVE).get();
        direct.setGrantedAt(T0.plusDays(20));
        Collection c = paidCollection(CollectionStatus.PUBLISHED, new CollectionItem(ENTRY, 0, T0.minusDays(1)));
        stubCollectionPurchase(BUYER, c, T0);

        ContentAccessPolicy.Result r = policy.evaluate(TENANT, versioned(T0.plusDays(10)), BUYER);

        assertThat(r.entitled()).isTrue();
        assertThat(r.rightStart()).isEqualTo(T0); // collection right came first
        assertThat(ContentVersionService.purchasedVersion(versioned(T0.plusDays(10)), r.rightStart()).getVersion()).isEqualTo(1);
    }

    @Test
    void s07_itemAddedToCollectionAfterV2_buyerAcquiresV2() {
        Collection c = paidCollection(CollectionStatus.PUBLISHED, new CollectionItem(ENTRY, 0, T0.plusDays(15)));
        stubCollectionPurchase(BUYER, c, T0);
        Entry e = versioned(T0.plusDays(10));
        ContentAccessPolicy.Result r = policy.evaluate(TENANT, e, BUYER);
        assertThat(r.rightStart()).isEqualTo(T0.plusDays(15));
        assertThat(ContentVersionService.purchasedVersion(e, r.rightStart())).isNull(); // v2 == current ⇒ live
    }}
