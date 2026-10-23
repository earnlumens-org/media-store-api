package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.model.Dispute;
import org.earnlumens.mediastore.domain.media.model.DisputeStatus;
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
import org.earnlumens.mediastore.domain.media.repository.AssetRepository;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.domain.media.repository.DisputeRepository;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H3 parity: the public detail page and the CDN entitlement check share
 * {@link ContentAccessPolicy}; there is no state in which one answers 200
 * and the other 403 for the same (entry, viewer).
 */
class PublicEntryServiceAccessTest {

    private static final String TENANT = "t1";
    private static final String OWNER = "owner";
    private static final String BUYER = "buyer";
    private static final String ENTRY = "e1";

    private EntryRepository entries;
    private EntitlementRepository entitlements;
    private OrderRepository orders;
    private DisputeRepository disputes;
    private PublicEntryService publicService;
    private MediaEntitlementService cdnService;
    private Entry entry;

    @BeforeEach
    void setUp() {
        entries = mock(EntryRepository.class);
        entitlements = mock(EntitlementRepository.class);
        orders = mock(OrderRepository.class);
        disputes = mock(DisputeRepository.class);
        CollectionRepository collections = mock(CollectionRepository.class);
        AssetRepository assets = mock(AssetRepository.class);
        when(entitlements.findByTenantIdAndUserIdAndEntryIdAndStatus(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(entitlements.findByTenantIdAndUserIdAndCollectionIdsAndStatus(any(), any(), any(), any())).thenReturn(List.of());
        when(collections.findByTenantIdAndStatusAndItemsEntryId(any(), any(), any())).thenReturn(List.of());
        when(assets.findByTenantIdAndEntryIdAndKindAndStatus(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(disputes.findByTenantIdAndUserIdAndStatus(any(), any(), any())).thenReturn(List.of());

        ContentAccessPolicy policy = new ContentAccessPolicy(entitlements, collections, orders);
        publicService = new PublicEntryService(entries, assets, entitlements, collections, policy, disputes);
        cdnService = new MediaEntitlementService(entries, assets, policy);

        entry = new Entry();
        entry.setId(ENTRY);
        entry.setTenantId(TENANT);
        entry.setUserId(OWNER);
        entry.setType(EntryType.RESOURCE); // text-only: CDN grants without a FULL asset
        entry.setTitle("T");
        entry.setPaid(true);
        entry.setStatus(EntryStatus.PUBLISHED);
        when(entries.findByTenantIdAndId(TENANT, ENTRY)).thenReturn(Optional.of(entry));
    }

    private void stubPurchase() {
        Entitlement ent = new Entitlement();
        ent.setId("ent-1");
        ent.setTenantId(TENANT);
        ent.setUserId(BUYER);
        ent.setEntryId(ENTRY);
        ent.setTargetType(TargetType.ENTRY);
        ent.setGrantType(GrantType.PURCHASE);
        ent.setOrderId("o1");
        ent.setStatus(EntitlementStatus.ACTIVE);
        when(entitlements.findByTenantIdAndUserIdAndEntryIdAndStatus(TENANT, BUYER, ENTRY, EntitlementStatus.ACTIVE))
                .thenReturn(Optional.of(ent));
        Order o = new Order();
        o.setId("o1");
        o.setStatus(OrderStatus.COMPLETED);
        o.setStellarTxHash("h");
        when(orders.findByTenantIdAndId(TENANT, "o1")).thenReturn(Optional.of(o));
    }

    @ParameterizedTest
    @EnumSource(EntryStatus.class)
    void buyerSeesPageAndCdnServesInEveryStatus(EntryStatus status) {
        stubPurchase();
        entry.setStatus(status);

        PublicEntryService.Lookup page = publicService.lookupEntry(TENANT, ENTRY, BUYER);
        MediaEntitlementService.Check cdn = cdnService.check(TENANT, BUYER, ENTRY);

        assertThat(page.entry()).as("UI " + status).isPresent();
        assertThat(page.entry().get().viewerAccess()).isEqualTo("ENTITLED");
        assertThat(page.entry().get().status()).isEqualTo(status.name());
        assertThat(cdn.response()).as("CDN " + status).isPresent();
    }

    @ParameterizedTest
    @EnumSource(EntryStatus.class)
    void blockedEntry_uiReportsBlockedAndCdnDenies_forEveryone(EntryStatus status) {
        stubPurchase();
        entry.setStatus(status);
        entry.setModerationBlock(new ModerationBlock("case-1", "illegal", "mod", LocalDateTime.now()));
        Dispute d = new Dispute();
        d.setId("dispute-9");
        d.setUserId(BUYER);
        d.setStatus(DisputeStatus.OPEN);
        d.setAffectedEntryIds(List.of(ENTRY));
        when(disputes.findByTenantIdAndUserIdAndStatus(TENANT, BUYER, DisputeStatus.OPEN)).thenReturn(List.of(d));

        for (String viewer : new String[]{BUYER, OWNER, null}) {
            PublicEntryService.Lookup page = publicService.lookupEntry(TENANT, ENTRY, viewer);
            MediaEntitlementService.Check cdn = cdnService.check(TENANT, viewer, ENTRY);
            assertThat(page.entry()).isEmpty();
            assertThat(page.blocked()).isTrue();
            assertThat(cdn.decision()).isEqualTo(AccessDecision.DENIED_BLOCKED);
        }
        assertThat(publicService.lookupEntry(TENANT, ENTRY, BUYER).disputeId()).isEqualTo("dispute-9");
        assertThat(publicService.lookupEntry(TENANT, ENTRY, null).disputeId()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = EntryStatus.class, names = {"DRAFT", "IN_REVIEW", "APPROVED", "REJECTED", "SUSPENDED", "ARCHIVED", "DELETED"})
    void anonymousOnNonPublicEntry_bothDeny(EntryStatus status) {
        entry.setStatus(status);
        assertThat(publicService.lookupEntry(TENANT, ENTRY, null).entry()).isEmpty();
        assertThat(publicService.lookupEntry(TENANT, ENTRY, null).blocked()).isFalse();
        assertThat(cdnService.check(TENANT, null, ENTRY).decision()).isEqualTo(AccessDecision.DENIED_HIDDEN);
    }

    @Test
    void anonymousOnPublicPaidEntry_seesPaywallPage_cdnDeniesLocked() {
        PublicEntryService.Lookup page = publicService.lookupEntry(TENANT, ENTRY, null);
        assertThat(page.entry()).isPresent();
        assertThat(page.entry().get().viewerAccess()).isEqualTo("PUBLIC");
        assertThat(cdnService.check(TENANT, null, ENTRY).decision()).isEqualTo(AccessDecision.DENIED_LOCKED);
    }

    @ParameterizedTest
    @EnumSource(value = EntryStatus.class, names = {"DRAFT", "ARCHIVED", "DELETED", "SUSPENDED", "IN_REVIEW"})
    void freeNonPublicEntry_hiddenOnBothPoints_forNonOwner(EntryStatus status) {
        entry.setPaid(false);
        entry.setStatus(status);
        assertThat(publicService.lookupEntry(TENANT, ENTRY, null).entry()).isEmpty();
        assertThat(cdnService.check(TENANT, null, ENTRY).decision()).isEqualTo(AccessDecision.DENIED_HIDDEN);
        assertThat(publicService.lookupEntry(TENANT, ENTRY, OWNER).entry()).isPresent();
        assertThat(cdnService.check(TENANT, OWNER, ENTRY).response()).isPresent();
    }
}
