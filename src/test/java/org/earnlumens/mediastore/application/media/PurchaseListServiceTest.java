package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.response.PurchasedEntryPageResponse;
import org.earnlumens.mediastore.domain.media.dto.response.PurchasedEntryResponse;
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
import org.earnlumens.mediastore.domain.media.model.TargetType;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "My purchases" (DATA-MODEL.md §9.5) under L1: collection entitlements expand
 * by the union rule (H2 / D-20) and every row shows the version the buyer
 * acquired (N8-lite / S-07).
 */
class PurchaseListServiceTest {

    private static final String TENANT = "t1";
    private static final String BUYER = "buyer";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 0, 0);

    private EntitlementRepository entitlements;
    private EntryRepository entries;
    private CollectionRepository collections;
    private PurchaseListService service;
    private final List<Entitlement> book = new ArrayList<>();
    private final Map<String, Entry> entryMap = new java.util.HashMap<>();
    private final Map<String, Collection> collMap = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        entitlements = mock(EntitlementRepository.class);
        entries = mock(EntryRepository.class);
        collections = mock(CollectionRepository.class);
        when(entitlements.findByTenantIdAndUserIdAndStatus(eq(TENANT), eq(BUYER), eq(EntitlementStatus.ACTIVE), any()))
                .thenAnswer(inv -> new PageImpl<>(book, PageRequest.of(0, 24), book.size()));
        when(entries.findByTenantIdAndIdIn(eq(TENANT), anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(1);
            return ids.stream().map(entryMap::get).filter(e -> e != null).toList();
        });
        when(collections.findByTenantIdAndIdIn(eq(TENANT), anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(1);
            return ids.stream().map(collMap::get).filter(c -> c != null).toList();
        });
        service = new PurchaseListService(entitlements, entries, collections);
    }

    private Entry entry(String id, String title) {
        Entry e = new Entry();
        e.setId(id);
        e.setTenantId(TENANT);
        e.setUserId("creator");
        e.setType(EntryType.RESOURCE);
        e.setStatus(EntryStatus.PUBLISHED);
        e.setPaid(true);
        e.setTitle(title);
        e.setThumbnailR2Key("public/media/" + id + "/thumbnail/live.jpg");
        e.setThumbnailVariantsPrefix("public/media/" + id + "/thumbnail/variants");
        entryMap.put(id, e);
        return e;
    }

    private Entitlement collectionRight(String collId, LocalDateTime grantedAt) {
        Entitlement ent = new Entitlement();
        ent.setId("ent-" + collId);
        ent.setTenantId(TENANT);
        ent.setUserId(BUYER);
        ent.setTargetType(TargetType.COLLECTION);
        ent.setCollectionId(collId);
        ent.setGrantType(GrantType.PURCHASE);
        ent.setStatus(EntitlementStatus.ACTIVE);
        ent.setGrantedAt(grantedAt);
        book.add(ent);
        return ent;
    }

    private Entitlement entryRight(String entryId, LocalDateTime grantedAt) {
        Entitlement ent = collectionRight("x", grantedAt);
        ent.setId("ent-" + entryId);
        ent.setTargetType(TargetType.ENTRY);
        ent.setCollectionId(null);
        ent.setEntryId(entryId);
        return ent;
    }

    private Collection collection(String id, CollectionItem... items) {
        Collection c = new Collection();
        c.setId(id);
        c.setTenantId(TENANT);
        c.setUserId("creator");
        c.setPaid(true);
        c.setStatus(CollectionStatus.ARCHIVED); // status is irrelevant for the buyer (H2)
        c.setItems(new ArrayList<>(List.of(items)));
        collMap.put(id, c);
        return c;
    }

    private static CollectionItem removed(String entryId, LocalDateTime addedAt, LocalDateTime removedAt) {
        CollectionItem i = new CollectionItem(entryId, 0, addedAt);
        i.setRemovedAt(removedAt);
        i.setPosition(null);
        return i;
    }

    private Map<String, PurchasedEntryResponse> list() {
        PurchasedEntryPageResponse page = service.listPurchases(TENANT, BUYER, 0, 24);
        return page.content().stream().collect(Collectors.toMap(PurchasedEntryResponse::id, Function.identity()));
    }

    @Test
    void collectionExpansionFollowsTheUnionRule_d20() {
        entry("A", "A"); entry("B", "B"); entry("D", "D"); entry("E", "E");
        collection("c1",
                new CollectionItem("A", 0),                       // live
                removed("B", null, T0.plusDays(10)),              // removed after purchase ⇒ retained
                removed("D", T0.plusDays(3), T0.plusDays(20)),    // added then removed after purchase ⇒ retained
                removed("E", null, T0.minusDays(1)));             // removed before purchase ⇒ never acquired
        collectionRight("c1", T0);

        assertThat(list().keySet()).containsExactlyInAnyOrder("A", "B", "D");
    }

    @Test
    void blockedCollectionGrantsNothingInTheLibrary() {
        entry("A", "A");
        Collection c = collection("c1", new CollectionItem("A", 0));
        c.setModerationBlock(new org.earnlumens.mediastore.domain.media.model.ModerationBlock("case", "r", "m", T0));
        collectionRight("c1", T0);
        assertThat(list()).isEmpty();
    }

    @Test
    void rowsShowThePurchasedVersion_perPath_s07() {
        Entry a = entry("A", "A v2");
        EntryVersion v1 = new EntryVersion();
        v1.setVersion(1); v1.setApprovedAt(T0.minusDays(30)); v1.setTitle("A v1");
        v1.setThumbnailR2Key("public/media/A/thumbnail/v1.jpg");
        EntryVersion v2 = new EntryVersion();
        v2.setVersion(2); v2.setApprovedAt(T0.plusDays(10)); v2.setTitle("A v2");
        v2.setThumbnailR2Key("public/media/A/thumbnail/live.jpg");
        a.setVersions(new ArrayList<>(List.of(v1, v2)));
        a.setCurrentVersion(2);
        entry("Z", "Z"); // unversioned: untouched

        // direct purchase before v2 ⇒ v1 (and its own thumbnail, no live variants)
        entryRight("A", T0);
        entryRight("Z", T0);
        Map<String, PurchasedEntryResponse> direct = list();
        assertThat(direct.get("A").title()).isEqualTo("A v1");
        assertThat(direct.get("A").thumbnailR2Key()).isEqualTo("public/media/A/thumbnail/v1.jpg");
        assertThat(direct.get("A").thumbnailVariantsPrefix()).isNull();
        assertThat(direct.get("Z").title()).isEqualTo("Z");
        assertThat(direct.get("Z").thumbnailVariantsPrefix()).isNotNull();

        // via a collection where A was added after v2 ⇒ right starts at addedAt ⇒ v2 (live)
        book.clear();
        collection("c1", new CollectionItem("A", 0, T0.plusDays(15)));
        collectionRight("c1", T0);
        assertThat(list().get("A").title()).isEqualTo("A v2");

        // same collection bought before v2 with A present since forever ⇒ v1
        book.clear();
        collection("c2", new CollectionItem("A", 0));
        collectionRight("c2", T0);
        assertThat(list().get("A").title()).isEqualTo("A v1");
    }

    // ── Unified feed (/api/purchases/feed): same purchased-version rule (L2) ──

    private org.bson.Document feedDoc(String id, String title, int versionCount) {
        org.bson.Document d = new org.bson.Document();
        d.put("_id", id);
        d.put("kind", "entry");
        d.put("type", "RESOURCE");
        d.put("title", title);
        d.put("description", "live description");
        d.put("isPaid", true);
        d.put("thumbnailR2Key", "public/media/" + id + "/thumbnail/live.jpg");
        d.put("thumbnailVariantsPrefix", "public/media/" + id + "/thumbnail/variants");
        d.put("versionCount", versionCount);
        return d;
    }

    @Test
    void unifiedFeedCardsShowThePurchasedVersion_andSkipTheLookupWhenUnversioned() {
        Entry a = entry("A", "A v2");
        a.setDescription("live description");
        EntryVersion v1 = new EntryVersion();
        v1.setVersion(1); v1.setApprovedAt(T0.minusDays(30)); v1.setTitle("A v1"); v1.setDescription("v1 description");
        v1.setThumbnailR2Key("public/media/A/thumbnail/v1.jpg");
        EntryVersion v2 = new EntryVersion();
        v2.setVersion(2); v2.setApprovedAt(T0.plusDays(10)); v2.setTitle("A v2"); v2.setDescription("live description");
        v2.setThumbnailR2Key("public/media/A/thumbnail/live.jpg");
        a.setVersions(new ArrayList<>(List.of(v1, v2)));
        a.setCurrentVersion(2);
        entry("Z", "Z");

        Entitlement rightA = entryRight("A", T0);
        Entitlement rightZ = entryRight("Z", T0);
        when(entitlements.findAllEntryEntitlements(TENANT, BUYER, EntitlementStatus.ACTIVE))
                .thenReturn(List.of(rightA, rightZ));
        when(entitlements.findAllEntitledCollectionIds(TENANT, BUYER, EntitlementStatus.ACTIVE))
                .thenReturn(java.util.Set.of());
        when(entries.findPurchasedFeedItems(eq(TENANT), any(), any(), any(), any(), any(), eq(0), eq(24)))
                .thenReturn(List.of(feedDoc("A", "A v2", 2), feedDoc("Z", "Z", 0)));
        when(entries.countPurchasedFeedItems(eq(TENANT), any(), any(), any(), any())).thenReturn(2L);

        var page = service.getUnifiedPurchases(TENANT, BUYER, null, null, null, 0, 24);

        var byId = page.content().stream().collect(Collectors.toMap(i -> i.id(), Function.identity()));
        assertThat(byId.get("A").title()).isEqualTo("A v1");
        assertThat(byId.get("A").description()).isEqualTo("v1 description");
        assertThat(byId.get("A").thumbnailR2Key()).isEqualTo("public/media/A/thumbnail/v1.jpg");
        assertThat(byId.get("A").thumbnailVariantsPrefix()).isNull();
        assertThat(byId.get("Z").title()).isEqualTo("Z");
        assertThat(byId.get("Z").thumbnailVariantsPrefix()).isNotNull();
        // Only the versioned entry is re-read; the unversioned one never hits the repository.
        org.mockito.Mockito.verify(entries).findByTenantIdAndIdIn(TENANT, List.of("A"));
    }

    @Test
    void unifiedFeedWithoutVersionedEntries_doesNotReloadEntries() {
        entry("Z", "Z");
        Entitlement rightZ = entryRight("Z", T0);
        when(entitlements.findAllEntryEntitlements(TENANT, BUYER, EntitlementStatus.ACTIVE))
                .thenReturn(List.of(rightZ));
        when(entitlements.findAllEntitledCollectionIds(TENANT, BUYER, EntitlementStatus.ACTIVE))
                .thenReturn(java.util.Set.of());
        when(entries.findPurchasedFeedItems(eq(TENANT), any(), any(), any(), any(), any(), eq(0), eq(24)))
                .thenReturn(List.of(feedDoc("Z", "Z", 0)));
        when(entries.countPurchasedFeedItems(eq(TENANT), any(), any(), any(), any())).thenReturn(1L);

        var page = service.getUnifiedPurchases(TENANT, BUYER, null, null, null, 0, 24);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).title()).isEqualTo("Z");
        org.mockito.Mockito.verify(entries, org.mockito.Mockito.never()).findByTenantIdAndIdIn(eq(TENANT), anyList());
    }
}
