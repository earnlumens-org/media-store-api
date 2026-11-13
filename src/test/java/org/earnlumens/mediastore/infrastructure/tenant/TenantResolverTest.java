package org.earnlumens.mediastore.infrastructure.tenant;

import jakarta.servlet.http.HttpServletRequest;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantConfigService;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantReadModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenantResolver}: subdomain parsing, reserved-words
 * denylist, RFC-1123 regex, tenant gating (H1: unknown ⇒ null, BLOCKED ⇒
 * read-only), custom-domain resolution (custom-domain-upgrade 3.1: hit ⇒
 * tenant, miss ⇒ null — no default fallback).
 */
class TenantResolverTest {

    private TenantConfigService tenantConfigService;
    private TenantResolver resolver;

    @BeforeEach
    void setUp() {
        tenantConfigService = mock(TenantConfigService.class);
        resolver = new TenantResolver(tenantConfigService);
        ReflectionTestUtils.setField(resolver, "rootDomain", "earnlumens.org");
        lenient().when(tenantConfigService.findBySubdomain(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        lenient().when(tenantConfigService.findActiveByCustomDomain(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
    }

    private HttpServletRequest req(String host) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getServerName()).thenReturn(host);
        return r;
    }

    private TenantReadModel activeTenant(String sub) {
        TenantReadModel t = new TenantReadModel();
        t.setSubdomain(sub);
        t.setStatus("ACTIVE");
        return t;
    }

    private TenantReadModel blockedTenant(String sub) {
        TenantReadModel t = activeTenant(sub);
        t.setStatus("BLOCKED");
        return t;
    }

    @Test
    void localhost_returnsDefault() {
        assertEquals("earnlumens", resolver.resolve(req("localhost")));
    }

    @Test
    void loopbackIp_returnsDefault() {
        assertEquals("earnlumens", resolver.resolve(req("127.0.0.1")));
    }

    @Test
    void rootDomain_returnsDefault() {
        assertEquals("earnlumens", resolver.resolve(req("earnlumens.org")));
    }

    @Test
    void reservedSubdomain_returnsDefault() {
        assertEquals("earnlumens", resolver.resolve(req("api.earnlumens.org")));
        assertEquals("earnlumens", resolver.resolve(req("www.earnlumens.org")));
        assertEquals("earnlumens", resolver.resolve(req("admin.earnlumens.org")));
    }

    @Test
    void invalidSubdomainSyntax_isNotATenant() {
        // Underscore / leading hyphen / too short can never be a tenant: H1
        // action 2 — refuse (404) instead of leaking the default storefront.
        assertNull(resolver.resolve(req("al_ice.earnlumens.org")));
        assertNull(resolver.resolve(req("-alice.earnlumens.org")));
        assertNull(resolver.resolve(req("a.earnlumens.org")));
    }

    @Test
    void multiLabelSubdomain_isNotATenant() {
        assertNull(resolver.resolve(req("api.staging.earnlumens.org")));
    }

    @Test
    void unknownSubdomain_isNotATenant_neverDefault() {
        when(tenantConfigService.findBySubdomain("ghost"))
                .thenReturn(Optional.empty());
        assertNull(resolver.resolve(req("ghost.earnlumens.org")));
        assertNull(resolver.resolveTenant(req("ghost.earnlumens.org")));
    }

    @Test
    void activeSubdomain_returnsSubdomain() {
        when(tenantConfigService.findBySubdomain("alice"))
                .thenReturn(Optional.of(activeTenant("alice")));
        assertEquals("alice", resolver.resolve(req("alice.earnlumens.org")));
        TenantResolver.ResolvedTenant resolved = resolver.resolveTenant(req("alice.earnlumens.org"));
        assertEquals("alice", resolved.tenantId());
        assertFalse(resolved.suspended());
    }

    @Test
    void blockedSubdomain_resolvesAsItselfSuspended() {
        // H1: a BLOCKED tenant is served read-only as itself — never the
        // default tenant (that would hide every buyer's library).
        when(tenantConfigService.findBySubdomain("alice"))
                .thenReturn(Optional.of(blockedTenant("alice")));
        assertEquals("alice", resolver.resolve(req("alice.earnlumens.org")));
        TenantResolver.ResolvedTenant resolved = resolver.resolveTenant(req("alice.earnlumens.org"));
        assertEquals("alice", resolved.tenantId());
        assertTrue(resolved.suspended());
    }

    @Test
    void blockedTenantCustomDomain_isNotServed() {
        // D-04 / H1 decision (b): the custom domain of a blocked tenant is a
        // 404 at the edge; findActiveByCustomDomain already filters it out.
        when(tenantConfigService.findActiveByCustomDomain("shop.example.com"))
                .thenReturn(Optional.empty());
        assertNull(resolver.resolve(req("shop.example.com")));
    }

    @Test
    void platformHosts_areNeverSuspended() {
        assertFalse(resolver.resolveTenant(req("earnlumens.org")).suspended());
        assertFalse(resolver.resolveTenant(req("localhost")).suspended());
        assertFalse(resolver.resolveTenant(req("media-store-api-owuexaao5a-ew.a.run.app")).suspended());
        assertFalse(resolver.resolveTenant(req("www.earnlumens.org")).suspended());
    }

    @Test
    void customDomainActive_returnsTenant() {
        when(tenantConfigService.findActiveByCustomDomain("my-store.example.com"))
                .thenReturn(Optional.of(activeTenant("alice")));
        assertEquals("alice", resolver.resolve(req("my-store.example.com")));
    }

    @Test
    void customDomainUnknown_returnsNull() {
        // Unknown third-party host must NOT fall back to the default tenant.
        assertNull(resolver.resolve(req("my-store.example.com")));
    }

    @Test
    void customDomainNotServable_returnsNull() {
        // Pending/suspended/plan-expired domains: findActiveByCustomDomain
        // already filters them out, so the resolver sees empty ⇒ no tenant.
        when(tenantConfigService.findActiveByCustomDomain("pending.example.com"))
                .thenReturn(Optional.empty());
        assertNull(resolver.resolve(req("pending.example.com")));
    }

    @Test
    void customDomainMixedCase_isLowercased() {
        when(tenantConfigService.findActiveByCustomDomain("my-store.example.com"))
                .thenReturn(Optional.of(activeTenant("alice")));
        assertEquals("alice", resolver.resolve(req("My-Store.EXAMPLE.com")));
    }

    @Test
    void cloudRunHost_returnsDefault() {
        // Health probes / internal jobs hit .run.app directly — keep default.
        assertEquals("earnlumens", resolver.resolve(req("media-store-api-owuexaao5a-ew.a.run.app")));
    }

    @Test
    void portInHost_isStripped() {
        when(tenantConfigService.findBySubdomain("alice"))
                .thenReturn(Optional.of(activeTenant("alice")));
        // getServerName() normally returns no port, but handle it defensively.
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getServerName()).thenReturn("alice.earnlumens.org:8443");
        assertEquals("alice", resolver.resolve(r));
    }

    @Test
    void mixedCaseHost_isLowercased() {
        when(tenantConfigService.findBySubdomain("alice"))
                .thenReturn(Optional.of(activeTenant("alice")));
        assertEquals("alice", resolver.resolve(req("ALICE.Earnlumens.ORG")));
    }

    @Test
    void nullHost_returnsDefault() {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getServerName()).thenReturn(null);
        assertEquals("earnlumens", resolver.resolve(r));
    }
}
