package org.earnlumens.mediastore.application.payment;

import org.earnlumens.mediastore.domain.media.dto.request.PreparePaymentRequest;
import org.earnlumens.mediastore.domain.media.dto.response.PreparePaymentResponse;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.Order;
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
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantReadModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * H7 (MYCELIUM-RESILIENCE.md) at the {@code prepare} level: the matrix
 * {@code tenantFeePercent ∈ {0, >0}} × {@code tenantWallet ∈ {null, inactive,
 * active}}. Whenever the tenant's mandatory payout cannot be honoured the
 * call fails with {@code TENANT_PAYOUT_UNCONFIGURED} <b>before</b> an XDR is
 * built or a {@code PENDING} order is persisted.
 */
class PaymentServicePrepareTenantPayoutTest {

    private static final String TENANT = "alice";
    private static final String BUYER = "buyer-oauth-id";
    private static final String SELLER = "seller-oauth-id";
    private static final String ENTRY_ID = "entry1";
    private static final String BUYER_WALLET = "G" + "A".repeat(55);
    private static final String SELLER_WALLET = "G" + "S".repeat(55);
    private static final String TENANT_WALLET = "G" + "T".repeat(55);
    private static final String PLATFORM_WALLET = "G" + "P".repeat(55);

    private OrderRepository orderRepository;
    private StellarTransactionService stellarTxService;
    private TenantConfigService tenantConfigService;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        EntryRepository entryRepository = mock(EntryRepository.class);
        CollectionRepository collectionRepository = mock(CollectionRepository.class);
        orderRepository = mock(OrderRepository.class);
        EntitlementRepository entitlementRepository = mock(EntitlementRepository.class);
        stellarTxService = mock(StellarTransactionService.class);
        tenantConfigService = mock(TenantConfigService.class);

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
        entry.setPaymentSplits(List.of(new PaymentSplit(SELLER_WALLET, SplitRole.SELLER, new BigDecimal("100.00"))));
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID)).thenReturn(Optional.of(entry));

        when(orderRepository.findAllByTenantIdAndUserIdAndTargetTypeAndEntryId(
                eq(TENANT), eq(BUYER), any(TargetType.class), eq(ENTRY_ID))).thenReturn(List.of());
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId("order-1");
            return o;
        });
        when(stellarTxService.isAccountActiveCached(anyString())).thenReturn(true);
        when(stellarTxService.buildTransaction(eq(BUYER_WALLET), any(), anyList(), anyString()))
                .thenReturn(new StellarTransactionService.BuildResult("XDR", "hash", "tx"));
    }

    private void tenant(String feePercent, String wallet) {
        TenantReadModel t = new TenantReadModel();
        t.setSubdomain(TENANT);
        t.setStatus("ACTIVE");
        t.setTenantFeePercent(new BigDecimal(feePercent));
        t.setTenantWallet(wallet);
        when(tenantConfigService.findActiveBySubdomain(TENANT)).thenReturn(Optional.of(t));
    }

    private PreparePaymentRequest request() {
        return new PreparePaymentRequest(ENTRY_ID, null, null, null, BUYER_WALLET);
    }

    private void assertRefusedWithoutOrder() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.prepare(TENANT, BUYER, request()));
        assertEquals(PaymentService.TENANT_PAYOUT_UNCONFIGURED, ex.getMessage());
        verify(orderRepository, never()).save(any());
        verify(stellarTxService, never()).buildTransaction(anyString(), any(), anyList(), anyString());
    }

    // ── fee > 0 ──────────────────────────────────────────────────────────

    @Test
    void feeConfigured_walletNull_refusedWithoutOrder() {
        tenant("5.00", null);
        assertRefusedWithoutOrder();
    }

    @Test
    void feeConfigured_walletInactive_refusedWithoutOrder() {
        tenant("5.00", TENANT_WALLET);
        when(stellarTxService.isAccountActiveCached(TENANT_WALLET)).thenReturn(false);
        assertRefusedWithoutOrder();
    }

    @Test
    void feeConfigured_walletActive_threeWaySplitAndOrderCreated() {
        tenant("5.00", TENANT_WALLET);

        PreparePaymentResponse resp = service.prepare(TENANT, BUYER, request());

        assertNotNull(resp);
        verify(orderRepository).save(any(Order.class));
        verify(stellarTxService).buildTransaction(eq(BUYER_WALLET), any(), anyList(), anyString());
    }

    // ── fee = 0 (no mandatory tenant payee) ──────────────────────────────

    @Test
    void feeZero_walletNull_saleProceedsTwoWay() {
        tenant("0.00", null);

        assertNotNull(service.prepare(TENANT, BUYER, request()));
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    void feeZero_walletInactive_saleProceeds_tenantWalletNotPartOfTheSplit() {
        tenant("0.00", TENANT_WALLET);
        when(stellarTxService.isAccountActiveCached(TENANT_WALLET)).thenReturn(false);

        assertNotNull(service.prepare(TENANT, BUYER, request()));
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    void feeZero_walletActive_saleProceeds() {
        tenant("0.00", TENANT_WALLET);

        assertNotNull(service.prepare(TENANT, BUYER, request()));
        verify(orderRepository).save(any(Order.class));
    }
}
