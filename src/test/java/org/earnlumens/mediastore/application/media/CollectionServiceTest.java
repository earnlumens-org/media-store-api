package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.application.payment.StellarTransactionService;
import org.earnlumens.mediastore.application.user.UserBadgeService;
import org.earnlumens.mediastore.domain.media.dto.response.CollectionDetailResponse;
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
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.media.repository.OrderRepository;
import org.earnlumens.mediastore.domain.user.repository.UserRepository;
import org.earnlumens.mediastore.infrastructure.r2.R2PresignedUrlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H2 (MYCELIUM-RESILIENCE.md, D-20 / S-04): membership history in
 * {@code Collection.items[]}, 409 on hard delete with buyers, and the buyer's
 * collection page in any status with the items they retain.
 */
class CollectionServiceTest {

    private static final String TENANT = "t1";
    private static final String OWNER = "owner";
    private static final String BUYER = "buyer";
    private static final String STRANGER = "stranger";
    private static final String COLL = "coll-1";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 0, 0);

    private CollectionRepository collections;
    private EntryRepository entries;
    private EntitlementRepository entitlements;
    private OrderRepository orders;
    private CollectionService service;
    private Collection collection;

    @BeforeEach
    void setUp() {
        collections = mock(CollectionRepository.class);
        entries = mock(EntryRepository.class);
        entitlements = mock(EntitlementRepository.class);
        orders = mock(OrderRepository.class);
        when(collections.save(any(Collection.class))).thenAnswer(inv -> inv.getArgument(0));
        when(entitlements.findByTenantIdAndUserIdAndCollectionIdsAndStatus(any(), any(), anyList(), any()))
                .thenReturn(List.of());
        when(entitlements.findEntitledEntryIds(any(), any(), anyList(), any())).thenReturn(Set.of());
        service = new CollectionService(collections, entries, entitlements, mock(UserRepository.class),
                mock(R2PresignedUrlService.class), mock(UserBadgeService.class), mock(ModerationJobService.class),
                mock(StellarTransactionService.class), new ContentAccessPolicy(entitlements, collections, orders));

        collection = new Collection();
        collection.setId(COLL);
        collection.setTenantId(TENANT);
        collection.setUserId(OWNER);
        collection.setPaid(true);
        collection.setStatus(CollectionStatus.PUBLISHED);
        collection.setItems(new ArrayList<>(List.of(new CollectionItem("A", 0), new CollectionItem("B", 1))));
        when(collections.findByTenantIdAndId(TENANT, COLL)).thenReturn(Optional.of(collection));

        when(entries.findByTenantIdAndId(eq(TENANT), any())).thenAnswer(inv -> Optional.of(entry(inv.getArgument(1))));
        when(entries.findByTenantIdAndIdIn(eq(TENANT), anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(1);
            return ids.stream().map(CollectionServiceTest::entry).toList();
        });
    }

    private static Entry entry(String id) {
        Entry e = new Entry();
        e.setId(id);
        e.setTenantId(TENANT);
        e.setUserId(OWNER);
        e.setType(EntryType.IMAGE);
        e.setStatus(EntryStatus.PUBLISHED);
        e.setPaid(true);
        e.setTitle("Entry " + id);
        return e;
    }

    private void stubBuyer(LocalDateTime grantedAt) {
        Entitlement ent = new Entitlement();
        ent.setId("ent-c");
        ent.setTenantId(TENANT);
        ent.setUserId(BUYER);
        ent.setCollectionId(COLL);
        ent.setTargetType(TargetType.COLLECTION);
        ent.setGrantType(GrantType.PURCHASE);
        ent.setOrderId("o-c");
        ent.setStatus(EntitlementStatus.ACTIVE);
        ent.setGrantedAt(grantedAt);
        when(entitlements.findByTenantIdAndUserIdAndCollectionIdsAndStatus(eq(TENANT), eq(BUYER), anyList(), eq(EntitlementStatus.ACTIVE)))
                .thenReturn(List.of(ent));
        Order o = new Order();
        o.setId("o-c");
        o.setStatus(OrderStatus.COMPLETED);
        o.setStellarTxHash("h");
        when(orders.findByTenantIdAndId(TENANT, "o-c")).thenReturn(Optional.of(o));
    }

    private CollectionItem record(String entryId) {
        return collection.getItems().stream().filter(i -> i.getEntryId().equals(entryId)).findFirst().orElseThrow();
    }

    // ─── membership history ─────────────────────────────────────

    @Test
    void removeItem_keepsTheRecordWithRemovedAt_andReindexesActiveOnes() {
        assertThat(service.removeItem(TENANT, OWNER, COLL, "A")).isTrue();

        assertThat(collection.getItems()).hasSize(2);
        CollectionItem a = record("A");
        assertThat(a.getRemovedAt()).isNotNull();
        assertThat(a.getPosition()).isNull();
        assertThat(a.isActive()).isFalse();
        assertThat(collection.activeItems()).extracting(CollectionItem::getEntryId).containsExactly("B");
        assertThat(record("B").getPosition()).isZero();
        assertThat(collection.activeItemCount()).isEqualTo(1);
        // removing again is a no-op (nothing active to remove)
        assertThat(service.removeItem(TENANT, OWNER, COLL, "A")).isFalse();
    }

    @Test
    void addItem_appendsWithAddedAt_andReAddingARemovedItemCreatesANewRecord() {
        service.removeItem(TENANT, OWNER, COLL, "B");
        LocalDateTime before = LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(1);

        assertThat(service.addItem(TENANT, OWNER, COLL, "B")).isTrue();

        List<CollectionItem> bRecords = collection.getItems().stream().filter(i -> i.getEntryId().equals("B")).toList();
        assertThat(bRecords).hasSize(2);
        assertThat(bRecords.get(0).isActive()).isFalse();            // history preserved
        assertThat(bRecords.get(1).isActive()).isTrue();
        assertThat(bRecords.get(1).getAddedAt()).isAfterOrEqualTo(before);
        assertThat(bRecords.get(1).getPosition()).isEqualTo(1);        // after A
        // adding an active item again is idempotent
        assertThat(service.addItem(TENANT, OWNER, COLL, "B")).isTrue();
        assertThat(collection.getItems()).hasSize(3);
    }

    @Test
    void reorderItems_touchesActiveRecordsOnly_andNeverDiscardsRemovedOnes() {
        service.removeItem(TENANT, OWNER, COLL, "A");
        service.addItem(TENANT, OWNER, COLL, "C");

        assertThat(service.reorderItems(TENANT, OWNER, COLL, List.of("C", "B"))).isTrue();

        assertThat(collection.getItems()).hasSize(3);
        assertThat(record("A").isActive()).isFalse();
        assertThat(collection.activeItems()).extracting(CollectionItem::getEntryId).containsExactly("C", "B");
        // the removed id is not part of the reorder contract
        assertThatThrownBy(() -> service.reorderItems(TENANT, OWNER, COLL, List.of("A", "B", "C")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void itemOperationsNeverWriteEntitlements() {
        service.removeItem(TENANT, OWNER, COLL, "A");
        service.addItem(TENANT, OWNER, COLL, "C");
        service.reorderItems(TENANT, OWNER, COLL, List.of("C", "B"));
        verify(entitlements, never()).save(any());
        verify(entitlements, never()).upsertPurchase(any());
    }

    @Test
    void maxItemsCountsActiveRecordsOnly() {
        List<CollectionItem> items = new ArrayList<>();
        for (int i = 0; i < Collection.MAX_ITEMS; i++) {
            CollectionItem removed = new CollectionItem("r" + i, 0);
            removed.setRemovedAt(T0);
            items.add(removed);
        }
        items.add(new CollectionItem("live", 0));
        Collection c = new Collection();
        c.setItems(items); // 1000 removed + 1 active must be accepted
        assertThat(c.activeItemCount()).isEqualTo(1);
    }

    // ─── hard delete ────────────────────────────────────────────

    @Test
    void deleteCollectionWithBuyers_is409_andTouchesNothing() {
        collection.setStatus(CollectionStatus.REJECTED);
        when(entitlements.existsByTenantIdAndCollectionId(TENANT, COLL)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteCollection(TENANT, OWNER, COLL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(CollectionService.COLLECTION_HAS_BUYERS);
        verify(collections, never()).deleteByTenantIdAndId(any(), any());
        verify(collections, never()).save(any());
    }

    @Test
    void deleteCollectionWithoutBuyers_stillWorksFromDraft() {
        collection.setStatus(CollectionStatus.DRAFT);
        when(entitlements.existsByTenantIdAndCollectionId(TENANT, COLL)).thenReturn(false);
        assertThat(service.deleteCollection(TENANT, OWNER, COLL)).isTrue();
        verify(collections).deleteByTenantIdAndId(TENANT, COLL);
    }

    // ─── buyer's collection page ────────────────────────────────

    @ParameterizedTest
    @EnumSource(CollectionStatus.class)
    void buyerOpensTheCollectionInAnyStatus_strangerOnlyWhenPublished(CollectionStatus status) {
        collection.setStatus(status);
        stubBuyer(T0);

        Optional<CollectionDetailResponse> buyer = service.getCollectionDetail(TENANT, COLL, BUYER);
        assertThat(buyer).as("buyer " + status).isPresent();
        assertThat(buyer.get().unlocked()).isTrue();
        assertThat(buyer.get().items()).extracting(CollectionDetailResponse.CollectionEntryItem::entryId)
                .containsExactly("A", "B");

        Optional<CollectionDetailResponse> stranger = service.getCollectionDetail(TENANT, COLL, STRANGER);
        assertThat(stranger.isPresent()).as("stranger " + status).isEqualTo(status == CollectionStatus.PUBLISHED);
        assertThat(service.getCollectionDetail(TENANT, COLL, OWNER)).isPresent();
    }

    @Test
    void buyerSeesRetainedItems_separately_laterBuyerDoesNot() {
        // B present at purchase, removed on day 10; D added on day 3, removed on day 20
        collection.setItems(new ArrayList<>(List.of(
                new CollectionItem("A", 0),
                removed("B", null, T0.plusDays(10)),
                removed("D", T0.plusDays(3), T0.plusDays(20)),
                removed("E", null, T0.minusDays(1)))));  // removed before the purchase
        stubBuyer(T0);

        CollectionDetailResponse page = service.getCollectionDetail(TENANT, COLL, BUYER).orElseThrow();

        assertThat(page.itemCount()).isEqualTo(1);
        assertThat(page.items()).extracting(CollectionDetailResponse.CollectionEntryItem::entryId).containsExactly("A");
        assertThat(page.retainedItems()).extracting(CollectionDetailResponse.CollectionEntryItem::entryId)
                .containsExactlyInAnyOrder("B", "D");
        assertThat(page.retainedItems()).allSatisfy(i -> {
            assertThat(i.retained()).isTrue();
            assertThat(i.unlocked()).isTrue();
            assertThat(i.locked()).isFalse();
        });

        // a stranger (and a buyer from after the removals) sees only the live item
        collection.setStatus(CollectionStatus.PUBLISHED);
        CollectionDetailResponse publicPage = service.getCollectionDetail(TENANT, COLL, STRANGER).orElseThrow();
        assertThat(publicPage.items()).extracting(CollectionDetailResponse.CollectionEntryItem::entryId).containsExactly("A");
        assertThat(publicPage.retainedItems()).isEmpty();
    }

    @Test
    void blockedCollectionIsHiddenFromEveryone() {
        collection.setModerationBlock(new ModerationBlock("case", "r", "mod", LocalDateTime.now()));
        stubBuyer(T0);
        assertThat(service.getCollectionDetail(TENANT, COLL, BUYER)).isEmpty();
        assertThat(service.getCollectionDetail(TENANT, COLL, OWNER)).isEmpty();
    }

    /** §0.0 P30-2 (CAP-84): M…, C… and W… never overwrite the seller wallet. */
    @ParameterizedTest
    @MethodSource("org.earnlumens.mediastore.application.payment.StellarAddressTypeRejectionTest#nonAccountAddresses")
    void updateCollection_rejectsNonAccountStellarAddresses(String address) {
        var request = new org.earnlumens.mediastore.domain.media.dto.request.UpdateCollectionRequest(
                null, null, null, null, null, null, null, address);

        assertThatThrownBy(() -> service.updateCollection(TENANT, OWNER, COLL, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid Stellar public key");

        assertThat(collection.getSellerWallet()).isNull();
        verify(collections, never()).save(any());
    }

    private static CollectionItem removed(String entryId, LocalDateTime addedAt, LocalDateTime removedAt) {
        CollectionItem i = new CollectionItem(entryId, 0, addedAt);
        i.setRemovedAt(removedAt);
        i.setPosition(null);
        return i;
    }
}
