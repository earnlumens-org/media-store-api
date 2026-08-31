package org.earnlumens.mediastore.infrastructure.tenant.read;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the custom-domain resolution gate (custom-domain-upgrade
 * 2A.2): a custom domain only resolves while it is ACTIVE, the tenant is
 * ACTIVE and the Pro plan (incl. persisted grace) is running. Everything
 * else — pending, suspended, expired plan, unknown host — is empty, never a
 * fallback to a default tenant.
 */
@ExtendWith(MockitoExtension.class)
class TenantConfigServiceTest {

    @Mock private TenantReadRepository repository;

    private TenantConfigService service;

    @BeforeEach
    void setUp() {
        service = new TenantConfigService(repository);
    }

    private static TenantReadModel tenant(String domainStatus, boolean pro) {
        TenantReadModel t = new TenantReadModel();
        t.setSubdomain("acme");
        t.setStatus("ACTIVE");
        t.setCustomDomain("shop.example.com");
        t.setCustomDomainStatus(domainStatus);
        t.setPlan(pro ? "PRO" : "FREE");
        if (pro) {
            t.setPlanExpiresAt(Instant.now().plus(Duration.ofDays(30)));
            t.setPlanGraceUntil(Instant.now().plus(Duration.ofDays(37)));
        }
        return t;
    }

    @Test
    void activeProDomain_resolves() {
        when(repository.findByCustomDomain("shop.example.com"))
                .thenReturn(Optional.of(tenant("ACTIVE", true)));

        Optional<TenantReadModel> result = service.findActiveByCustomDomain("Shop.Example.COM ");

        assertTrue(result.isPresent());
        assertEquals("acme", result.get().getSubdomain());
    }

    @Test
    void pendingOrSuspendedDomain_doesNotResolve() {
        for (String status : new String[]{"PENDING_DNS", "PENDING_SSL", "SUSPENDED", "FAILED", "NONE"}) {
            TenantConfigService fresh = new TenantConfigService(repository);
            when(repository.findByCustomDomain("shop.example.com"))
                    .thenReturn(Optional.of(tenant(status, true)));
            assertTrue(fresh.findActiveByCustomDomain("shop.example.com").isEmpty(),
                    "must not resolve when status=" + status);
        }
    }

    @Test
    void expiredProPlan_doesNotResolve() {
        TenantReadModel t = tenant("ACTIVE", true);
        t.setPlanExpiresAt(Instant.now().minus(Duration.ofDays(10)));
        t.setPlanGraceUntil(Instant.now().minus(Duration.ofDays(3)));
        when(repository.findByCustomDomain("shop.example.com")).thenReturn(Optional.of(t));

        assertTrue(service.findActiveByCustomDomain("shop.example.com").isEmpty());
    }

    @Test
    void freeTenant_doesNotResolve() {
        when(repository.findByCustomDomain("shop.example.com"))
                .thenReturn(Optional.of(tenant("ACTIVE", false)));
        assertTrue(service.findActiveByCustomDomain("shop.example.com").isEmpty());
    }

    @Test
    void blockedTenant_doesNotResolve() {
        TenantReadModel t = tenant("ACTIVE", true);
        t.setStatus("BLOCKED");
        when(repository.findByCustomDomain("shop.example.com")).thenReturn(Optional.of(t));
        assertTrue(service.findActiveByCustomDomain("shop.example.com").isEmpty());
    }

    @Test
    void unknownHost_isEmpty_andNegativeResultIsCached() {
        when(repository.findByCustomDomain("nope.example.com")).thenReturn(Optional.empty());

        assertTrue(service.findActiveByCustomDomain("nope.example.com").isEmpty());
        assertTrue(service.findActiveByCustomDomain("nope.example.com").isEmpty());

        verify(repository, times(1)).findByCustomDomain("nope.example.com");
    }

    @Test
    void cacheHit_reevaluatesTimeGate() {
        // Plan expires between the first and second read within the TTL:
        // the cached entry must stop resolving immediately.
        TenantReadModel t = tenant("ACTIVE", true);
        t.setPlanExpiresAt(Instant.now().plusMillis(150));
        t.setPlanGraceUntil(Instant.now().plusMillis(150));
        when(repository.findByCustomDomain("shop.example.com")).thenReturn(Optional.of(t));

        assertTrue(service.findActiveByCustomDomain("shop.example.com").isPresent());
        try { Thread.sleep(200); } catch (InterruptedException ignored) { }
        assertTrue(service.findActiveByCustomDomain("shop.example.com").isEmpty());
        verify(repository, times(1)).findByCustomDomain("shop.example.com");
    }

    @Test
    void invalidateBySubdomain_dropsCustomDomainEntry() {
        when(repository.findByCustomDomain("shop.example.com"))
                .thenReturn(Optional.of(tenant("ACTIVE", true)));

        service.findActiveByCustomDomain("shop.example.com");
        service.invalidate("acme");
        service.findActiveByCustomDomain("shop.example.com");

        verify(repository, times(2)).findByCustomDomain("shop.example.com");
    }

    @Test
    void nullAndBlankHost_areEmpty() {
        assertTrue(service.findActiveByCustomDomain(null).isEmpty());
        assertTrue(service.findActiveByCustomDomain("  ").isEmpty());
        verify(repository, never()).findByCustomDomain(anyString());
    }
}
