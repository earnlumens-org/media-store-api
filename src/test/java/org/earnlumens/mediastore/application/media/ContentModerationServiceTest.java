package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.model.Collection;
import org.earnlumens.mediastore.domain.media.model.CollectionItem;
import org.earnlumens.mediastore.domain.media.model.CollectionStatus;
import org.earnlumens.mediastore.domain.media.model.Dispute;
import org.earnlumens.mediastore.domain.media.model.DisputeStatus;
import org.earnlumens.mediastore.domain.media.model.Entitlement;
import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.GrantType;
import org.earnlumens.mediastore.domain.media.model.ModerationCase;
import org.earnlumens.mediastore.domain.media.model.ModerationCaseKind;
import org.earnlumens.mediastore.domain.media.model.ModerationCaseResolution;
import org.earnlumens.mediastore.domain.media.model.TargetType;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.domain.media.repository.DisputeRepository;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.media.repository.ModerationCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H3 / D-15 / D-09 acceptance for {@link ContentModerationService}: union of
 * access paths, one dispute per purchase, grouping by case, idempotency,
 * entitlements never revoked, no automatic resolution, reinstatement.
 */
class ContentModerationServiceTest {

    private static final String TENANT = "t1";
    private static final String A = "entry-A";
    private static final String B = "entry-B";
    private static final String C = "entry-C";
    private static final String COLL = "coll-1";
    private static final String U1 = "user-1";
    private static final String U2 = "user-2";
    private static final String U3 = "user-3";

    private final Map<String, Entry> entries = new HashMap<>();
    private final Map<String, Collection> collections = new HashMap<>();
    private final Map<String, ModerationCase> cases = new LinkedHashMap<>();
    private final Map<String, Dispute> disputes = new LinkedHashMap<>();
    private final List<Entitlement> entitlementBook = new ArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();

    private ContentModerationService service;

    @BeforeEach
    void setUp() {
        EntryRepository entryRepository = mock(EntryRepository.class);
        when(entryRepository.findByTenantIdAndId(any(), any())).thenAnswer(inv ->
                Optional.ofNullable(entries.get(inv.getArgument(1))).filter(e -> TENANT.equals(inv.getArgument(0))));
        when(entryRepository.save(any(Entry.class))).thenAnswer(inv -> {
            Entry e = inv.getArgument(0);
            entries.put(e.getId(), e);
            return e;
        });

        CollectionRepository collectionRepository = mock(CollectionRepository.class);
        when(collectionRepository.findByTenantIdAndId(any(), any())).thenAnswer(inv ->
                Optional.ofNullable(collections.get(inv.getArgument(1))));
        when(collectionRepository.findByTenantIdAndItemsEntryId(any(), any())).thenAnswer(inv -> {
            String entryId = inv.getArgument(1);
            return collections.values().stream()
                    .filter(c -> c.getItems().stream().anyMatch(i -> i.getEntryId().equals(entryId)))
                    .toList();
        });
        when(collectionRepository.save(any(Collection.class))).thenAnswer(inv -> {
            Collection c = inv.getArgument(0);
            collections.put(c.getId(), c);
            return c;
        });

        EntitlementRepository entitlementRepository = mock(EntitlementRepository.class);
        when(entitlementRepository.findActivePurchasesByEntryId(any(), any())).thenAnswer(inv -> {
            String entryId = inv.getArgument(1);
            return entitlementBook.stream()
                    .filter(e -> e.getTargetType() == TargetType.ENTRY && entryId.equals(e.getEntryId()))
                    .filter(e -> e.getStatus() == EntitlementStatus.ACTIVE && e.getGrantType() == GrantType.PURCHASE)
                    .toList();
        });
        when(entitlementRepository.findActivePurchasesByCollectionIds(any(), any())).thenAnswer(inv -> {
            List<String> collIds = inv.getArgument(1);
            return entitlementBook.stream()
                    .filter(e -> e.getTargetType() == TargetType.COLLECTION && collIds.contains(e.getCollectionId()))
                    .filter(e -> e.getStatus() == EntitlementStatus.ACTIVE && e.getGrantType() == GrantType.PURCHASE)
                    .toList();
        });

        ModerationCaseRepository caseRepository = mock(ModerationCaseRepository.class);
        when(caseRepository.save(any(ModerationCase.class))).thenAnswer(inv -> {
            ModerationCase c = inv.getArgument(0);
            if (c.getId() == null) c.setId("case-" + ids.incrementAndGet());
            cases.put(c.getId(), c);
            return c;
        });
        when(caseRepository.findByTenantIdAndId(any(), any())).thenAnswer(inv ->
                Optional.ofNullable(cases.get(inv.getArgument(1))));
        when(caseRepository.findAllUnresolved(org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv ->
                cases.values().stream().filter(c -> c.getResolvedAt() == null).toList());

        // In-memory dispute book with the semantics of the unique index + $setOnInsert
        DisputeRepository disputeRepository = mock(DisputeRepository.class);
        when(disputeRepository.openIfAbsent(any(Dispute.class))).thenAnswer(inv -> {
            Dispute d = inv.getArgument(0);
            String key = d.getTenantId() + "|" + d.getModerationCaseId() + "|" + d.getEntitlementId();
            if (disputes.containsKey(key)) return false;
            d.setId("dispute-" + ids.incrementAndGet());
            disputes.put(key, d);
            return true;
        });

        service = new ContentModerationService(entryRepository, collectionRepository,
                entitlementRepository, caseRepository, disputeRepository);
    }

    // ─── fixtures ───────────────────────────────────────────────

    private Entry entry(String id, EntryStatus status) {
        Entry e = new Entry();
        e.setId(id);
        e.setTenantId(TENANT);
        e.setUserId("creator");
        e.setType(EntryType.VIDEO);
        e.setStatus(status);
        e.setPaid(true);
        entries.put(id, e);
        return e;
    }

    private Collection collection(String id, String... entryIds) {
        Collection c = new Collection();
        c.setId(id);
        c.setTenantId(TENANT);
        c.setUserId("creator");
        c.setPaid(true);
        c.setStatus(CollectionStatus.PUBLISHED);
        List<CollectionItem> items = new ArrayList<>();
        for (int i = 0; i < entryIds.length; i++) items.add(new CollectionItem(entryIds[i], i));
        c.setItems(items);
        collections.put(id, c);
        return c;
    }

    private Entitlement purchaseEntry(String userId, String entryId) {
        Entitlement e = base(userId);
        e.setTargetType(TargetType.ENTRY);
        e.setEntryId(entryId);
        entitlementBook.add(e);
        return e;
    }

    private Entitlement purchaseCollection(String userId, String collectionId) {
        Entitlement e = base(userId);
        e.setTargetType(TargetType.COLLECTION);
        e.setCollectionId(collectionId);
        entitlementBook.add(e);
        return e;
    }

    private Entitlement base(String userId) {
        Entitlement e = new Entitlement();
        e.setId("ent-" + ids.incrementAndGet());
        e.setTenantId(TENANT);
        e.setUserId(userId);
        e.setGrantType(GrantType.PURCHASE);
        e.setStatus(EntitlementStatus.ACTIVE);
        e.setOrderId("order-" + e.getId());
        return e;
    }

    private List<Dispute> disputesOf(String userId) {
        return disputes.values().stream().filter(d -> d.getUserId().equals(userId)).toList();
    }

    // ─── Takedown of one entry: direct buyers ───────────────────

    @Test
    void takedownBlocksForEveryoneOpensOneDisputePerBuyerAndKeepsEntitlementsActive() {
        Entry a = entry(A, EntryStatus.PUBLISHED);
        Entitlement e1 = purchaseEntry(U1, A);
        Entitlement e2 = purchaseEntry(U2, A);
        purchaseEntry(U3, B); // unrelated purchase

        ModerationCase c = service.takedownEntry(TENANT, A, "Stolen content", "mod-1");

        assertThat(c.getKind()).isEqualTo(ModerationCaseKind.TAKEDOWN);
        assertThat(c.getEntryIds()).containsExactly(A);
        assertThat(a.isBlocked()).isTrue();
        assertThat(a.getModerationBlock().getCaseId()).isEqualTo(c.getId());
        assertThat(a.getModerationBlock().getBy()).isEqualTo("mod-1");
        assertThat(a.getStatus()).isEqualTo(EntryStatus.SUSPENDED);
        assertThat(a.getPreviousStatus()).isEqualTo(EntryStatus.PUBLISHED);
        assertThat(a.getStatusHistory()).hasSize(1);
        assertThat(a.getStatusHistory().get(0).getActor()).isEqualTo("mod-1");

        assertThat(disputes).hasSize(2);
        assertThat(disputesOf(U1)).singleElement().satisfies(d -> {
            assertThat(d.getStatus()).isEqualTo(DisputeStatus.OPEN);
            assertThat(d.getEntitlementId()).isEqualTo(e1.getId());
            assertThat(d.getOrderId()).isEqualTo(e1.getOrderId());
            assertThat(d.getEntryId()).isEqualTo(A);
            assertThat(d.getAffectedEntryIds()).containsExactly(A);
            assertThat(d.getModerationCaseId()).isEqualTo(c.getId());
            assertThat(d.getReason()).isEqualTo("Stolen content");
        });
        assertThat(disputesOf(U2)).singleElement().extracting(Dispute::getEntitlementId).isEqualTo(e2.getId());
        assertThat(disputesOf(U3)).isEmpty();

        // Entitlements are never revoked (§8.8)
        assertThat(entitlementBook).allMatch(e -> e.getStatus() == EntitlementStatus.ACTIVE);
    }

    @Test
    void takedownRequiresJustification() {
        entry(A, EntryStatus.PUBLISHED);
        assertThatThrownBy(() -> service.takedownEntry(TENANT, A, " ", "mod"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(entries.get(A).isBlocked()).isFalse();
    }

    @Test
    void takedownIsIdempotentAndReusesTheCase() {
        entry(A, EntryStatus.PUBLISHED);
        purchaseEntry(U1, A);
        ModerationCase first = service.takedownEntry(TENANT, A, "Illegal", "mod");
        ModerationCase second = service.takedownEntry(TENANT, A, "Illegal again", "mod");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(cases).hasSize(1);
        assertThat(disputes).hasSize(1);
    }

    // ─── D-15: union of access paths and grouping ───────────────

    @Test
    void disputesCoverCollectionBuyersAndGroupSeveralEntriesIntoOneDisputePerPurchase() {
        entry(A, EntryStatus.PUBLISHED);
        entry(B, EntryStatus.PUBLISHED);
        entry(C, EntryStatus.PUBLISHED);
        collection(COLL, A, B, C);
        Entitlement u1Coll = purchaseCollection(U1, COLL);   // U1 bought the collection
        Entitlement u2Entry = purchaseEntry(U2, A);          // U2 bought A alone
        purchaseEntry(U3, C);                                 // U3 bought C — not affected

        // One moderation action blocks A and B together
        ModerationCase c = new ModerationCase();
        c.setTenantId(TENANT);
        c.setKind(ModerationCaseKind.TAKEDOWN);
        c.setEntryIds(new ArrayList<>(List.of(A, B)));
        c.setReason("Stolen");
        c.setActor("mod");
        c.setCreatedAt(java.time.LocalDateTime.now());
        c.setId("case-X");
        cases.put(c.getId(), c);

        int created = service.openDisputes(c);

        assertThat(created).isEqualTo(2);
        assertThat(disputesOf(U1)).singleElement().satisfies(d -> {
            assertThat(d.getEntitlementId()).isEqualTo(u1Coll.getId());
            assertThat(d.getCollectionId()).isEqualTo(COLL);
            assertThat(d.getAffectedEntryIds()).containsExactly(A, B);
            assertThat(d.getEntryId()).isEqualTo(A);
            assertThat(d.getModerationCaseId()).isEqualTo("case-X");
        });
        assertThat(disputesOf(U2)).singleElement().satisfies(d -> {
            assertThat(d.getEntitlementId()).isEqualTo(u2Entry.getId());
            assertThat(d.getAffectedEntryIds()).containsExactly(A);
            assertThat(d.getModerationCaseId()).isEqualTo("case-X");
        });
        assertThat(disputesOf(U3)).isEmpty();

        // Re-running the trigger (retry / sweep) creates nothing new
        assertThat(service.openDisputes(c)).isZero();
        assertThat(service.sweepUnresolvedCases(100)).isZero();
        assertThat(disputes).hasSize(2);
    }

    @Test
    void sameEntryByTwoPathsYieldsOneDisputePerPurchaseNotPerPath() {
        entry(A, EntryStatus.PUBLISHED);
        collection(COLL, A);
        purchaseEntry(U1, A);
        purchaseCollection(U1, COLL);

        service.takedownEntry(TENANT, A, "Illegal", "mod");

        // Two purchases ⇒ two disputes (one per purchase), both for U1
        assertThat(disputesOf(U1)).hasSize(2);
        assertThat(disputesOf(U1)).extracting(Dispute::getEntitlementId).doesNotHaveDuplicates();
    }

    // ─── H2: path (b) follows the membership history (D-20) ────────

    @Test
    void collectionBuyerWhoRetainsARemovedItemGetsADispute_laterBuyerDoesNot() {
        LocalDateTime t0 = LocalDateTime.of(2026, 1, 1, 0, 0);
        entry(A, EntryStatus.PUBLISHED);
        Collection c = collection(COLL, B);               // A is no longer listed…
        CollectionItem removedA = new CollectionItem(A, 0, null);
        removedA.setRemovedAt(t0.plusDays(10));           // …removed on day 10
        removedA.setPosition(null);
        c.getItems().add(removedA);
        purchaseCollection(U1, COLL).setGrantedAt(t0);               // bought before removal ⇒ retains A
        purchaseCollection(U2, COLL).setGrantedAt(t0.plusDays(20));  // bought after removal ⇒ never had A

        service.takedownEntry(TENANT, A, "Illegal", "mod");

        assertThat(disputesOf(U1)).hasSize(1);
        assertThat(disputesOf(U1).get(0).getAffectedEntryIds()).containsExactly(A);
        assertThat(disputesOf(U2)).isEmpty();
    }

    @Test
    void collectionTakedownListsOnlyTheItemsEachBuyersRightCovers() {
        LocalDateTime t0 = LocalDateTime.of(2026, 1, 1, 0, 0);
        entry(A, EntryStatus.PUBLISHED);
        entry(B, EntryStatus.PUBLISHED);
        Collection c = collection(COLL, A);
        CollectionItem removedB = new CollectionItem(B, 1, t0.plusDays(2));
        removedB.setRemovedAt(t0.plusDays(5));
        removedB.setPosition(null);
        c.getItems().add(removedB);
        purchaseCollection(U1, COLL).setGrantedAt(t0);              // had B between day 2 and 5 ⇒ retains it
        purchaseCollection(U2, COLL).setGrantedAt(t0.plusDays(6));  // never had B

        service.takedownCollection(TENANT, COLL, "Illegal", "mod");

        assertThat(disputesOf(U1).get(0).getAffectedEntryIds()).containsExactlyInAnyOrder(A, B);
        assertThat(disputesOf(U2).get(0).getAffectedEntryIds()).containsExactly(A);
    }

    @Test
    void sweepOpensDisputesMissedByACrashAfterTheBlock() {
        Entry a = entry(A, EntryStatus.PUBLISHED);
        purchaseEntry(U1, A);
        ModerationCase c = service.takedownEntry(TENANT, A, "Illegal", "mod");
        assertThat(disputes).hasSize(1);

        // A purchase completed after the block (or missed by a crash)
        purchaseEntry(U2, A);
        assertThat(service.sweepUnresolvedCases(100)).isEqualTo(1);
        assertThat(disputesOf(U2)).singleElement().extracting(Dispute::getModerationCaseId).isEqualTo(c.getId());
        assertThat(a.isBlocked()).isTrue();
    }

    // ─── Preventive block ───────────────────────────────────────

    @Test
    void preventiveBlockMovesToInReviewAndOpensDisputes() {
        Entry a = entry(A, EntryStatus.PUBLISHED);
        purchaseEntry(U1, A);

        ModerationCase c = service.preventiveBlockEntry(TENANT, a, "High-severity report", "EarnLumens");

        assertThat(c.getKind()).isEqualTo(ModerationCaseKind.PREVENTIVE);
        assertThat(a.getStatus()).isEqualTo(EntryStatus.IN_REVIEW);
        assertThat(a.getPreviousStatus()).isEqualTo(EntryStatus.PUBLISHED);
        assertThat(a.isBlocked()).isTrue();
        assertThat(disputesOf(U1)).hasSize(1);
    }

    // ─── Reinstatement (§8.8) ───────────────────────────────────

    @Test
    void reinstateClearsBlockRestoresStatusAndLeavesDisputesToTheAdministrator() {
        Entry a = entry(A, EntryStatus.PUBLISHED);
        purchaseEntry(U1, A);
        ModerationCase c = service.takedownEntry(TENANT, A, "Mistake", "mod");

        Entry back = service.reinstateEntry(TENANT, A, "mod-2");

        assertThat(back.isBlocked()).isFalse();
        assertThat(back.getStatus()).isEqualTo(EntryStatus.PUBLISHED);
        assertThat(back.getPreviousStatus()).isNull();
        assertThat(back.getModerationFeedback()).isNull();
        assertThat(cases.get(c.getId()).getResolvedAt()).isNotNull();
        assertThat(cases.get(c.getId()).getResolution()).isEqualTo(ModerationCaseResolution.REINSTATED);
        // No code path resolves disputes: still OPEN for the administrator (D-09)
        assertThat(disputes.values()).allMatch(d -> d.getStatus() == DisputeStatus.OPEN);
        assertThat(entitlementBook).allMatch(e -> e.getStatus() == EntitlementStatus.ACTIVE);
    }

    @Test
    void reinstateOfUnblockedEntryIsRejected() {
        entry(A, EntryStatus.PUBLISHED);
        assertThatThrownBy(() -> service.reinstateEntry(TENANT, A, "mod"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void caseStaysOpenWhileAnotherEntryOfTheSameCaseIsStillBlocked() {
        Entry a = entry(A, EntryStatus.PUBLISHED);
        Entry b = entry(B, EntryStatus.PUBLISHED);
        ModerationCase c = new ModerationCase();
        c.setTenantId(TENANT);
        c.setKind(ModerationCaseKind.TAKEDOWN);
        c.setEntryIds(new ArrayList<>(List.of(A, B)));
        c.setReason("r");
        c.setActor("mod");
        c.setCreatedAt(java.time.LocalDateTime.now());
        c.setId("case-AB");
        cases.put(c.getId(), c);
        a.setModerationBlock(new org.earnlumens.mediastore.domain.media.model.ModerationBlock("case-AB", "r", "mod", c.getCreatedAt()));
        b.setModerationBlock(new org.earnlumens.mediastore.domain.media.model.ModerationBlock("case-AB", "r", "mod", c.getCreatedAt()));

        service.reinstateEntry(TENANT, A, "mod");
        assertThat(cases.get("case-AB").getResolvedAt()).isNull();
        service.reinstateEntry(TENANT, B, "mod");
        assertThat(cases.get("case-AB").getResolvedAt()).isNotNull();
    }

    // ─── Collection takedown ────────────────────────────────────

    @Test
    void collectionTakedownBlocksTheCollectionAndOpensDisputesForItsBuyers() {
        entry(A, EntryStatus.PUBLISHED);
        entry(B, EntryStatus.PUBLISHED);
        Collection coll = collection(COLL, A, B);
        Entitlement u1 = purchaseCollection(U1, COLL);
        purchaseEntry(U2, A); // direct buyer of A: the entry itself is not blocked

        ModerationCase c = service.takedownCollection(TENANT, COLL, "Illegal compilation", "mod");

        assertThat(coll.isBlocked()).isTrue();
        assertThat(c.getCollectionIds()).containsExactly(COLL);
        assertThat(c.getEntryIds()).isEmpty();
        assertThat(entries.get(A).isBlocked()).isFalse();
        assertThat(disputesOf(U1)).singleElement().satisfies(d -> {
            assertThat(d.getEntitlementId()).isEqualTo(u1.getId());
            assertThat(d.getCollectionId()).isEqualTo(COLL);
            assertThat(d.getAffectedEntryIds()).containsExactly(A, B);
        });
        assertThat(disputesOf(U2)).isEmpty();

        Collection back = service.reinstateCollection(TENANT, COLL, "mod");
        assertThat(back.isBlocked()).isFalse();
        assertThat(cases.get(c.getId()).getResolution()).isEqualTo(ModerationCaseResolution.REINSTATED);
    }
}
