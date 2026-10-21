package org.earnlumens.mediastore.application.payment;

import org.earnlumens.mediastore.domain.media.dto.request.PreparePaymentRequest;
import org.earnlumens.mediastore.domain.media.dto.response.PreparePaymentResponse;
import org.earnlumens.mediastore.domain.media.model.Collection;
import org.earnlumens.mediastore.domain.media.model.CollectionStatus;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.Order;
import org.earnlumens.mediastore.domain.media.model.OrderStatus;
import org.earnlumens.mediastore.domain.media.model.PaymentSplit;
import org.earnlumens.mediastore.domain.media.model.SplitRole;
import org.earnlumens.mediastore.domain.media.model.TargetType;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.media.repository.OrderRepository;
import org.earnlumens.mediastore.infrastructure.config.PlatformConfig;
import org.earnlumens.mediastore.infrastructure.config.StellarConfig;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H10 (MYCELIUM-RESILIENCE.md): a {@code TIP} order carries the tipped
 * entry/collection id as context and must never be mistaken for a purchase
 * by {@code prepare()}. The order repository is stubbed as an in-memory ledger
 * that honours the {@code targetType} filter exactly like the derived Mongo
 * query does (proven against a real mongod in
 * {@code OrderRepositoryImplIntegrationTest}).
 */
class PaymentServicePrepareTipIsolationTest {

    private static final String TENANT = "earnlumens";
    private static final String BUYER = "buyer-oauth-id";
    private static final String SELLER = "seller-oauth-id";
    private static final String ENTRY_ID = "entry1";
    private static final String COLLECTION_ID = "coll1";
    private static final String BUYER_WALLET = "G" + "A".repeat(55);
    private static final String SELLER_WALLET = "G" + "S".repeat(55);
    private static final String PLATFORM_WALLET = "G" + "P".repeat(55);
    private static final String PURCHASE_XDR = "XDR-OF-THE-NEW-PURCHASE";
    private static final String TIP_XDR = "XDR-OF-THE-ABANDONED-TIP";

    private final List<Order> ledger = new ArrayList<>();

    private OrderRepository orderRepository;
    private EntitlementRepository entitlementRepository;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        EntryRepository entryRepository = mock(EntryRepository.class);
        CollectionRepository collectionRepository = mock(CollectionRepository.class);
        orderRepository = mock(OrderRepository.class);
        entitlementRepository = mock(EntitlementRepository.class);
        StellarTransactionService stellarTxService = mock(StellarTransactionService.class);
        TenantConfigService tenantConfigService = mock(TenantConfigService.class);

        PlatformConfig platformConfig = new PlatformConfig();
        platformConfig.setWallet(PLATFORM_WALLET);
        platformConfig.setFeePercent(new BigDecimal("10.00"));

        service = new PaymentService(
                entryRepository, collectionRepository, orderRepository, entitlementRepository,
                stellarTxService, new StellarConfig(), platformConfig, null,
                tenantConfigService, null, null, null);

        Entry entry = new Entry();
        entry.setId(ENTRY_ID);
        entry.setUserId(SELLER);
        entry.setPaid(true);
        entry.setStatus(EntryStatus.PUBLISHED);
        entry.setPriceXlm(new BigDecimal("10"));
        entry.setPaymentSplits(sellerOnly());
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID)).thenReturn(Optional.of(entry));

        Collection collection = new Collection();
        collection.setUserId(SELLER);
        collection.setPaid(true);
        collection.setStatus(CollectionStatus.PUBLISHED);
        collection.setPriceXlm(new BigDecimal("25"));
        collection.setPaymentSplits(sellerOnly());
        when(collectionRepository.findByTenantIdAndId(TENANT, COLLECTION_ID)).thenReturn(Optional.of(collection));

        when(tenantConfigService.findActiveBySubdomain(TENANT)).thenReturn(Optional.empty());
        when(stellarTxService.isAccountActiveCached(anyString())).thenReturn(true);
        when(stellarTxService.buildTransaction(eq(BUYER_WALLET), any(), anyList(), anyString()))
                .thenReturn(new StellarTransactionService.BuildResult(PURCHASE_XDR, "hash", "tx"));

        // In-memory ledger honouring the targetType filter, as Mongo does.
        when(orderRepository.findAllByTenantIdAndUserIdAndTargetTypeAndEntryId(
                eq(TENANT), eq(BUYER), any(TargetType.class), eq(ENTRY_ID)))
                .thenAnswer(inv -> ledger.stream()
                        .filter(o -> o.getTargetType() == inv.<TargetType>getArgument(2)
                                && ENTRY_ID.equals(o.getEntryId()))
                        .toList());
        when(orderRepository.findAllByTenantIdAndUserIdAndTargetTypeAndCollectionId(
                eq(TENANT), eq(BUYER), any(TargetType.class), eq(COLLECTION_ID)))
                .thenAnswer(inv -> ledger.stream()
                        .filter(o -> o.getTargetType() == inv.<TargetType>getArgument(2)
                                && COLLECTION_ID.equals(o.getCollectionId()))
                        .toList());
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(UUID.randomUUID().toString());
            return o;
        });
    }

    // ── Tips must not contaminate the purchase flow ──

    @Test
    void completedTipOnEntryDoesNotBlockItsPurchase() {
        ledger.add(order(TargetType.TIP, ENTRY_ID, null, OrderStatus.COMPLETED, TIP_XDR));

        PreparePaymentResponse resp = service.prepare(TENANT, BUYER, entryRequest());

        assertEquals(PURCHASE_XDR, resp.unsignedXdr());
        Order saved = savedOrder();
        assertEquals(TargetType.ENTRY, saved.getTargetType());
        assertEquals(ENTRY_ID, saved.getEntryId());
        assertEquals(OrderStatus.PENDING, saved.getStatus());
        // the tip was never looked at: no "self-heal" entitlement check, no grant
        verify(entitlementRepository, never()).findOrderIdsWithEntitlements(any());
        verify(entitlementRepository, never()).save(any());
    }

    @Test
    void pendingTipOnEntryIsNotReturnedAsTheReusablePurchaseOrder() {
        ledger.add(order(TargetType.TIP, ENTRY_ID, null, OrderStatus.PENDING, TIP_XDR));

        PreparePaymentResponse resp = service.prepare(TENANT, BUYER, entryRequest());

        assertEquals(PURCHASE_XDR, resp.unsignedXdr());
        assertNotEquals(TIP_XDR, resp.unsignedXdr());
        assertEquals(TargetType.ENTRY, savedOrder().getTargetType());
        // the abandoned tip is left alone (not expired, not reused)
        verify(orderRepository, never()).tryTransitionStatus(anyString(), anyString(), any(), any());
    }

    @Test
    void completedTipOnCollectionDoesNotBlockItsPurchase() {
        ledger.add(order(TargetType.TIP, null, COLLECTION_ID, OrderStatus.COMPLETED, TIP_XDR));
        ledger.add(order(TargetType.TIP, null, COLLECTION_ID, OrderStatus.PENDING, TIP_XDR));

        PreparePaymentResponse resp = service.prepare(TENANT, BUYER, collectionRequest());

        assertEquals(PURCHASE_XDR, resp.unsignedXdr());
        Order saved = savedOrder();
        assertEquals(TargetType.COLLECTION, saved.getTargetType());
        assertEquals(COLLECTION_ID, saved.getCollectionId());
    }

    @Test
    void previousOrderLookupsAreRestrictedToThePurchaseTargetType() {
        service.prepare(TENANT, BUYER, entryRequest());
        verify(orderRepository).findAllByTenantIdAndUserIdAndTargetTypeAndEntryId(
                TENANT, BUYER, TargetType.ENTRY, ENTRY_ID);

        service.prepare(TENANT, BUYER, collectionRequest());
        verify(orderRepository).findAllByTenantIdAndUserIdAndTargetTypeAndCollectionId(
                TENANT, BUYER, TargetType.COLLECTION, COLLECTION_ID);
    }

    // ── Regression: real purchases keep their semantics ──

    @Test
    void completedPurchaseStillRejectsAsAlreadyPurchased() {
        Order purchase = order(TargetType.ENTRY, ENTRY_ID, null, OrderStatus.COMPLETED, "old");
        ledger.add(purchase);
        ledger.add(order(TargetType.TIP, ENTRY_ID, null, OrderStatus.COMPLETED, TIP_XDR));
        when(entitlementRepository.findOrderIdsWithEntitlements(List.of(purchase.getId())))
                .thenReturn(Set.of(purchase.getId()));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.prepare(TENANT, BUYER, entryRequest()));

        assertEquals("Content already purchased", ex.getMessage());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void livePendingPurchaseIsStillReused() {
        Order pending = order(TargetType.ENTRY, ENTRY_ID, null, OrderStatus.PENDING, "pending-purchase-xdr");
        ledger.add(pending);
        ledger.add(order(TargetType.TIP, ENTRY_ID, null, OrderStatus.PENDING, TIP_XDR));

        PreparePaymentResponse resp = service.prepare(TENANT, BUYER, entryRequest());

        assertEquals(pending.getId(), resp.orderId());
        assertEquals("pending-purchase-xdr", resp.unsignedXdr());
        verify(orderRepository, never()).save(any());
    }

    // ── helpers ──

    private static List<PaymentSplit> sellerOnly() {
        return List.of(new PaymentSplit(SELLER_WALLET, SplitRole.SELLER, new BigDecimal("90.00")));
    }

    private static PreparePaymentRequest entryRequest() {
        return new PreparePaymentRequest(ENTRY_ID, null, null, null, BUYER_WALLET);
    }

    private static PreparePaymentRequest collectionRequest() {
        return new PreparePaymentRequest(null, COLLECTION_ID, null, null, BUYER_WALLET);
    }

    private static Order order(TargetType type, String entryId, String collectionId,
                               OrderStatus status, String xdr) {
        Order o = new Order();
        o.setId(UUID.randomUUID().toString());
        o.setTenantId(TENANT);
        o.setUserId(BUYER);
        o.setSellerId(SELLER);
        o.setTargetType(type);
        o.setEntryId(entryId);
        o.setCollectionId(collectionId);
        o.setStatus(status);
        o.setUnsignedXdr(xdr);
        o.setBuyerWallet(BUYER_WALLET);
        o.setAmountXlm(BigDecimal.ONE);
        o.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        return o;
    }

    private Order savedOrder() {
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        return captor.getValue();
    }
}
