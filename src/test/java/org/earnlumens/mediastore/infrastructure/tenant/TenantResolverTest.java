package org.earnlumens.mediastore.infrastructure.tenant;

import jakarta.servlet.http.HttpServletRequest;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantConfigService;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantReadModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenantResolver}: subdomain parsing, reserved-words
 * denylist, RFC-1123 regex, ACTIVE-tenant gating.
 */
class TenantResolverTest {

    private TenantConfigService tenantConfigService;
    private TenantResolver resolver;

    @BeforeEach
    void setUp() {
        tenantConfigService = mock(TenantConfigService.class);
        resolver = new TenantResolver(tenantConfigService);
        ReflectionTestUtils.setField(resolver, "rootDomain", "earnlumens.org");
        lenient().when(tenantConfigService.findActiveBySubdomain(org.mockito.ArgumentMatchers.anyString()))
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
    void invalidSubdomainSyntax_returnsDefault() {
        // Uppercase / underscore / starts-with-hyphen — all invalid per RFC-1123 regex
        assertEquals("earnlumens", resolver.resolve(req("Alice.earnlumens.org")));
        assertEquals("earnlumens", resolver.resolve(req("-alice.earnlumens.org")));
        assertEquals("earnlumens", resolver.resolve(req("a.earnlumens.org"))); // too short
    }

    @Test
    void unknownActiveSubdomain_returnsDefault() {
        when(tenantConfigService.findActiveBySubdomain("ghost"))
                .thenReturn(Optional.empty());
        assertEquals("earnlumens", resolver.resolve(req("ghost.earnlumens.org")));
    }

    @Test
    void activeSubdomain_returnsSubdomain() {
        when(tenantConfigService.findActiveBySubdomain("alice"))
                .thenReturn(Optional.of(activeTenant("alice")));
        assertEquals("alice", resolver.resolve(req("alice.earnlumens.org")));
    }

    @Test
    void customDomain_returnsDefault() {
        // Third-party host, not under root — we cannot infer the tenant yet.
        assertEquals("earnlumens", resolver.resolve(req("my-store.example.com")));
    }

    @Test
    void portInHost_isStripped() {
        when(tenantConfigService.findActiveBySubdomain("alice"))
                .thenReturn(Optional.of(activeTenant("alice")));
        // getServerName() normally returns no port, but handle it defensively.
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getServerName()).thenReturn("alice.earnlumens.org:8443");
        assertEquals("alice", resolver.resolve(r));
    }

    @Test
    void mixedCaseHost_isLowercased() {
        when(tenantConfigService.findActiveBySubdomain("alice"))
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
