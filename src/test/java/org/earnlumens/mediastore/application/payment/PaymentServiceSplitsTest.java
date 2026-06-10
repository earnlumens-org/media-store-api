package org.earnlumens.mediastore.application.payment;

import org.earnlumens.mediastore.domain.media.model.PaymentSplit;
import org.earnlumens.mediastore.domain.media.model.SplitRole;
import org.earnlumens.mediastore.infrastructure.config.PlatformConfig;
import org.earnlumens.mediastore.infrastructure.franchise.read.FranchiseReadModel;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantConfigService;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantReadModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PaymentService#buildFullSplits(String, java.util.List)}
 * covering the three-way (PLATFORM + TENANT + SELLER) payment split math and
 * the safe two-way fallback when the tenant has no doc or is blocked.
 */
class PaymentServiceSplitsTest {

    private static final String PLATFORM_WALLET = "GPLATFORM111";
    private static final String TENANT_WALLET = "GTENANTAAA";
    private static final String SELLER_WALLET = "GSELLER222";

    private PaymentService service;
    private TenantConfigService tenantConfigService;

    @BeforeEach
    void setUp() {
        PlatformConfig platformConfig = new PlatformConfig();
        platformConfig.setWallet(PLATFORM_WALLET);
        platformConfig.setFeePercent(new BigDecimal("10.00"));

        tenantConfigService = mock(TenantConfigService.class);

        service = new PaymentService(
                null, null, null, null, null, null,
                platformConfig,
                null,
                tenantConfigService,
                null);
    }

    private List<PaymentSplit> invoke(String tenantId, List<PaymentSplit> entrySplits) {
        return ReflectionTestUtils.invokeMethod(
                service, "buildFullSplits", tenantId, entrySplits, null);
    }

    private List<PaymentSplit> invoke(String tenantId, List<PaymentSplit> entrySplits,
                                      FranchiseReadModel franchise) {
        return ReflectionTestUtils.invokeMethod(
                service, "buildFullSplits", tenantId, entrySplits, franchise);
    }

    private FranchiseReadModel franchise(String commissionPct, String wallet) {
        FranchiseReadModel f = new FranchiseReadModel();
        ReflectionTestUtils.setField(f, "status", "ACTIVE");
        if (commissionPct != null) {
            ReflectionTestUtils.setField(f, "commissionPercent", new BigDecimal(commissionPct));
        }
        ReflectionTestUtils.setField(f, "payoutWallet", wallet);
        return f;
    }

    private List<PaymentSplit> sellerOnly() {
        // Entry splits as stored: seller gets the full non-platform remainder (90%).
        return List.of(new PaymentSplit(SELLER_WALLET, SplitRole.SELLER, new BigDecimal("90.00")));
    }

    private TenantReadModel tenant(String sub, String platformPct, String tenantPct, String wallet) {
        TenantReadModel t = new TenantReadModel();
        t.setSubdomain(sub);
        t.setStatus("ACTIVE");
        if (platformPct != null) t.setPlatformFeePercent(new BigDecimal(platformPct));
        if (tenantPct != null) t.setTenantFeePercent(new BigDecimal(tenantPct));
        t.setTenantWallet(wallet);
        return t;
    }

    @Test
    void noTenantDoc_fallsBackToTwoWaySplit() {
        when(tenantConfigService.findActiveBySubdomain("earnlumens")).thenReturn(Optional.empty());

        List<PaymentSplit> result = invoke("earnlumens", sellerOnly());

        assertEquals(2, result.size());
        assertEquals(SplitRole.PLATFORM, result.get(0).getRole());
        assertEquals(new BigDecimal("10.00"), result.get(0).getPercent());
        assertEquals(SplitRole.SELLER, result.get(1).getRole());
        assertEquals(new BigDecimal("90.00"), result.get(1).getPercent());
        assertTrue(sumTo100(result));
    }

    @Test
    void tenantDocWithZeroTenantFee_isTwoWaySplit() {
        when(tenantConfigService.findActiveBySubdomain("alice"))
                .thenReturn(Optional.of(tenant("alice", "10.00", "0.00", TENANT_WALLET)));

        List<PaymentSplit> result = invoke("alice", sellerOnly());

        assertEquals(2, result.size());
        assertEquals(SplitRole.PLATFORM, result.get(0).getRole());
        assertEquals(SplitRole.SELLER, result.get(1).getRole());
        assertTrue(sumTo100(result));
    }

    @Test
    void tenantDocWithNullWallet_isTwoWaySplit() {
        when(tenantConfigService.findActiveBySubdomain("alice"))
                .thenReturn(Optional.of(tenant("alice", "10.00", "5.00", null)));

        List<PaymentSplit> result = invoke("alice", sellerOnly());

        // TENANT split is skipped because no wallet is configured.
        assertEquals(2, result.size());
        assertEquals(SplitRole.PLATFORM, result.get(0).getRole());
        assertEquals(SplitRole.SELLER, result.get(1).getRole());
    }

    @Test
    void tenantDocWithTenantFee_producesThreeWaySplit() {
        when(tenantConfigService.findActiveBySubdomain("alice"))
                .thenReturn(Optional.of(tenant("alice", "10.00", "5.00", TENANT_WALLET)));

        List<PaymentSplit> result = invoke("alice", sellerOnly());

        assertEquals(3, result.size());
        assertEquals(SplitRole.PLATFORM, result.get(0).getRole());
        assertEquals(new BigDecimal("10.00"), result.get(0).getPercent());
        assertEquals(PLATFORM_WALLET, result.get(0).getWallet());

        assertEquals(SplitRole.TENANT, result.get(1).getRole());
        assertEquals(new BigDecimal("5.00"), result.get(1).getPercent());
        assertEquals(TENANT_WALLET, result.get(1).getWallet());

        assertEquals(SplitRole.SELLER, result.get(2).getRole());
        assertEquals(new BigDecimal("85.00"), result.get(2).getPercent());
        assertEquals(SELLER_WALLET, result.get(2).getWallet());

        assertTrue(sumTo100(result));
    }

    @Test
    void tenantOverridesPlatformFee() {
        // Tenant-negotiated platform fee of 7% (instead of global 10%).
        when(tenantConfigService.findActiveBySubdomain("alice"))
                .thenReturn(Optional.of(tenant("alice", "7.00", "3.00", TENANT_WALLET)));

        List<PaymentSplit> result = invoke("alice", sellerOnly());

        assertEquals(3, result.size());
        assertEquals(new BigDecimal("7.00"), result.get(0).getPercent());
        assertEquals(new BigDecimal("3.00"), result.get(1).getPercent());
        assertEquals(new BigDecimal("90.00"), result.get(2).getPercent());
        assertTrue(sumTo100(result));
    }

    @Test
    void multiCollaboratorEntry_isRescaledProportionally() {
        // Entry stored as 60/30 between seller and collaborator (sum 90).
        List<PaymentSplit> entrySplits = List.of(
                new PaymentSplit(SELLER_WALLET, SplitRole.SELLER, new BigDecimal("60.00")),
                new PaymentSplit("GCOLLAB333", SplitRole.COLLABORATOR, new BigDecimal("30.00"))
        );
        when(tenantConfigService.findActiveBySubdomain("alice"))
                .thenReturn(Optional.of(tenant("alice", "10.00", "5.00", TENANT_WALLET)));

        List<PaymentSplit> result = invoke("alice", entrySplits);

        assertEquals(4, result.size());
        // Remaining 85% is split 60:30 (=2:1) -> 56.67 / 28.33
        assertEquals(new BigDecimal("56.67"), result.get(2).getPercent());
        assertEquals(new BigDecimal("28.33"), result.get(3).getPercent());
    }

    @Test
    void excessiveFees_throws() {
        // 60 + 50 > 100 — misconfiguration must fail loudly, not overflow into sellers.
        when(tenantConfigService.findActiveBySubdomain("evil"))
                .thenReturn(Optional.of(tenant("evil", "60.00", "50.00", TENANT_WALLET)));

        assertThrows(IllegalStateException.class, () -> invoke("evil", sellerOnly()));
    }

    @Test
    void franchiseSale_carvesCommissionOutOfTenantShare() {
        // Tenant fee 10%; franchise commission 40% of that -> 4% franchise, 6% tenant.
        when(tenantConfigService.findActiveBySubdomain("alice"))
                .thenReturn(Optional.of(tenant("alice", "10.00", "10.00", TENANT_WALLET)));

        List<PaymentSplit> result = invoke("alice", sellerOnly(), franchise("40.00", "GFRANCHISE9"));

        assertEquals(4, result.size());
        assertEquals(SplitRole.PLATFORM, result.get(0).getRole());
        assertEquals(new BigDecimal("10.00"), result.get(0).getPercent());
        assertEquals(SplitRole.TENANT, result.get(1).getRole());
        assertEquals(new BigDecimal("6.00"), result.get(1).getPercent());
        assertEquals(SplitRole.FRANCHISE, result.get(2).getRole());
        assertEquals(new BigDecimal("4.00"), result.get(2).getPercent());
        assertEquals("GFRANCHISE9", result.get(2).getWallet());
        assertEquals(SplitRole.SELLER, result.get(3).getRole());
        // Seller share is unchanged by the franchise carve-out (it only splits
        // the tenant's 10%): 100 - 10 platform - 10 tenant-share = 80%.
        assertEquals(new BigDecimal("80.00"), result.get(3).getPercent());
        assertTrue(sumTo100(result));
    }

    @Test
    void franchiseSale_withZeroTenantFee_earnsNothing() {
        // No tenant fee means no profit to share: no FRANCHISE split is added.
        when(tenantConfigService.findActiveBySubdomain("alice"))
                .thenReturn(Optional.of(tenant("alice", "10.00", "0.00", TENANT_WALLET)));

        List<PaymentSplit> result = invoke("alice", sellerOnly(), franchise("40.00", "GFRANCHISE9"));

        assertEquals(2, result.size());
        assertEquals(SplitRole.PLATFORM, result.get(0).getRole());
        assertEquals(SplitRole.SELLER, result.get(1).getRole());
        assertTrue(sumTo100(result));
    }

    /** Sum of all split percents must equal 100.00 (within 0.03 tolerance for rounding). */
    private boolean sumTo100(List<PaymentSplit> splits) {
        BigDecimal total = splits.stream()
                .map(PaymentSplit::getPercent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.subtract(new BigDecimal("100.00")).abs().compareTo(new BigDecimal("0.03")) <= 0;
    }
}
