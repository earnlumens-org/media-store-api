package org.earnlumens.mediastore.application.plan;

import org.earnlumens.mediastore.application.payment.StellarTransactionService;
import org.earnlumens.mediastore.infrastructure.billing.read.PlatformBillingConfigReadModel;
import org.earnlumens.mediastore.infrastructure.billing.read.PlatformBillingConfigReadRepository;
import org.earnlumens.mediastore.infrastructure.config.PlatformConfig;
import org.earnlumens.mediastore.infrastructure.config.StellarConfig;
import org.earnlumens.mediastore.infrastructure.external.admin.PlanApplyClient;
import org.earnlumens.mediastore.infrastructure.external.pricing.PriceSnapshot;
import org.earnlumens.mediastore.infrastructure.external.pricing.PriceUpdateMode;
import org.earnlumens.mediastore.infrastructure.external.pricing.XlmUsdPriceService;
import org.earnlumens.mediastore.infrastructure.persistence.plan.PlanOrderDocument;
import org.earnlumens.mediastore.infrastructure.persistence.plan.PlanOrderMongoRepository;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantConfigService;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantReadModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the Pro plan purchase flow (custom-domain-upgrade 1B):
 * owner-only gating, server-side pricing, and TenantReadModel.isPro().
 */
class PlanOrderServiceTest {

    private static final String TENANT = "acme";
    private static final String OWNER = "owner-1";
    private static final String WALLET = "GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF";
    private static final String PLATFORM_WALLET = "GBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBFR7";

    private PlanOrderMongoRepository repository;
    private MongoTemplate mongoTemplate;
    private TenantConfigService tenantConfigService;
    private PlatformBillingConfigReadRepository billingRepo;
    private StellarTransactionService stellarTxService;
    private PlatformConfig platformConfig;
    private XlmUsdPriceService priceService;
    private PlanApplyClient planApplyClient;
    private PlanOrderService service;

    @BeforeEach
    void setUp() {
        repository = mock(PlanOrderMongoRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        tenantConfigService = mock(TenantConfigService.class);
        billingRepo = mock(PlatformBillingConfigReadRepository.class);
        stellarTxService = mock(StellarTransactionService.class);
        platformConfig = mock(PlatformConfig.class);
        priceService = mock(XlmUsdPriceService.class);
        planApplyClient = mock(PlanApplyClient.class);
        service = new PlanOrderService(repository, mongoTemplate, tenantConfigService,
                billingRepo, stellarTxService, new StellarConfig(), platformConfig,
                priceService, planApplyClient);
    }

    private TenantReadModel activeTenant(String owner) {
        TenantReadModel t = new TenantReadModel();
        t.setSubdomain(TENANT);
        t.setOwnerOauthUserId(owner);
        t.setStatus("ACTIVE");
        return t;
    }

    private void wirePricingAndWallet() {
        when(billingRepo.findById(any())).thenReturn(Optional.empty());
        when(priceService.getPrice()).thenReturn(new PriceSnapshot(
                new BigDecimal("0.40"), Instant.now(), "test", PriceUpdateMode.INITIAL_LOAD));
        when(platformConfig.getWallet()).thenReturn(PLATFORM_WALLET);
        when(stellarTxService.isAccountActiveCached(PLATFORM_WALLET)).thenReturn(true);
        when(stellarTxService.buildTransaction(anyString(), any(), anyList(), anyString()))
                .thenReturn(new StellarTransactionService.BuildResult("xdr", "integrity", "hash"));
        when(repository.findByTenantIdAndOwnerOauthUserIdAndStatusIn(anyString(), anyString(), anyList()))
                .thenReturn(List.of());
        when(repository.save(any())).thenAnswer(inv -> {
            PlanOrderDocument doc = inv.getArgument(0);
            doc.setId("order-1");
            return doc;
        });
    }

    @Test
    void prepare_rejectsNonOwner() {
        when(tenantConfigService.findActiveBySubdomain(TENANT))
                .thenReturn(Optional.of(activeTenant("someone-else")));
        var e = assertThrows(IllegalArgumentException.class,
                () -> service.prepare(TENANT, OWNER, "MONTHLY", WALLET));
        assertEquals("PLAN_OWNER_ONLY", e.getMessage());
    }

    @Test
    void prepare_rejectsUnknownTenant() {
        when(tenantConfigService.findActiveBySubdomain(TENANT)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.prepare(TENANT, OWNER, "MONTHLY", WALLET));
    }

    @Test
    void prepare_rejectsInvalidPeriodAndWallet() {
        assertThrows(IllegalArgumentException.class,
                () -> service.prepare(TENANT, OWNER, "WEEKLY", WALLET));
        assertThrows(IllegalArgumentException.class,
                () -> service.prepare(TENANT, OWNER, "MONTHLY", "not-a-wallet"));
    }

    @Test
    void prepare_monthly_usesDefaultPriceAndPlatformSplit() {
        when(tenantConfigService.findActiveBySubdomain(TENANT))
                .thenReturn(Optional.of(activeTenant(OWNER)));
        wirePricingAndWallet();

        var resp = service.prepare(TENANT, OWNER, "MONTHLY", WALLET);

        assertEquals("order-1", resp.orderId());
        assertEquals("MONTHLY", resp.period());
        assertEquals(new BigDecimal("4.99"), resp.amountUsd());
        // 4.99 / 0.40 = 12.475 XLM (ceiling at 7 decimals)
        assertEquals(0, new BigDecimal("12.4750000").compareTo(resp.amountXlm()));
        assertEquals("xdr", resp.unsignedXdr());
        verify(stellarTxService).buildTransaction(eq(WALLET), any(), argThat(splits ->
                splits.size() == 1 && PLATFORM_WALLET.equals(splits.get(0).getWallet())), anyString());
    }

    @Test
    void prepare_yearly_usesYearlyPrice() {
        when(tenantConfigService.findActiveBySubdomain(TENANT))
                .thenReturn(Optional.of(activeTenant(OWNER)));
        wirePricingAndWallet();

        var resp = service.prepare(TENANT, OWNER, "YEARLY", WALLET);
        assertEquals(new BigDecimal("49.00"), resp.amountUsd());
    }

    @Test
    void tenantReadModel_isPro_semantics() {
        Instant now = Instant.parse("2026-08-29T12:00:00Z");
        TenantReadModel t = new TenantReadModel();
        assertFalse(t.isPro(now));                       // legacy: no plan field

        t.setPlan("PRO");
        assertFalse(t.isPro(now));                       // PRO without dates

        t.setPlanGraceUntil(now.plusSeconds(60));
        assertTrue(t.isPro(now));                        // inside grace

        t.setPlanGraceUntil(now.minusSeconds(60));
        assertFalse(t.isPro(now));                       // grace elapsed

        t.setPlanGraceUntil(null);
        t.setPlanExpiresAt(now.plusSeconds(60));
        assertTrue(t.isPro(now));                        // fallback to expiry

        t.setPlan("FREE");
        assertFalse(t.isPro(now));
    }
}
