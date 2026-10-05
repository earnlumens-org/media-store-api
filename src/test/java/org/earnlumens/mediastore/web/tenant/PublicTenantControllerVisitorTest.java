package org.earnlumens.mediastore.web.tenant;

import org.earnlumens.mediastore.infrastructure.tenant.read.TenantConfigService;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantReadModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link PublicTenantController} focused on the visitor
 * endpoint's host branching — especially the custom-domain semantics added in
 * custom-domain-upgrade 3.2: ACTIVE custom domain ⇒ {@code kind:"tenant"},
 * unknown custom domain ⇒ 404 {@code tenant_not_found} (never platform).
 */
class PublicTenantControllerVisitorTest {

    private MockMvc mockMvc;
    private TenantConfigService tenantConfigService;

    @BeforeEach
    void setUp() {
        tenantConfigService = mock(TenantConfigService.class);
        PublicTenantController controller = new PublicTenantController(tenantConfigService);
        ReflectionTestUtils.setField(controller, "rootDomain", "earnlumens.org");
        lenient().when(tenantConfigService.findActiveBySubdomain(anyString())).thenReturn(Optional.empty());
        lenient().when(tenantConfigService.findActiveByCustomDomain(anyString())).thenReturn(Optional.empty());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private TenantReadModel tenant(String sub) {
        TenantReadModel t = new TenantReadModel();
        t.setSubdomain(sub);
        t.setStatus("ACTIVE");
        t.setTitle("Alice Store");
        return t;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder visitor(String host) {
        return get("/public/tenant/visitor").with(req -> {
            req.setServerName(host);
            return req;
        });
    }

    @Test
    void apex_returnsPlatform() throws Exception {
        mockMvc.perform(visitor("earnlumens.org"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("platform"));
    }

    @Test
    void activeSubdomain_returnsTenant() throws Exception {
        when(tenantConfigService.findActiveBySubdomain("alice")).thenReturn(Optional.of(tenant("alice")));
        mockMvc.perform(visitor("alice.earnlumens.org"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("tenant"))
                .andExpect(jsonPath("$.subdomain").value("alice"));
    }

    @Test
    void unknownSubdomain_returns404() throws Exception {
        mockMvc.perform(visitor("ghost.earnlumens.org"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("tenant_not_found"));
    }

    @Test
    void activeCustomDomain_returnsTenantConfig() throws Exception {
        when(tenantConfigService.findActiveByCustomDomain("shop.example.com"))
                .thenReturn(Optional.of(tenant("alice")));
        mockMvc.perform(visitor("shop.example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("tenant"))
                .andExpect(jsonPath("$.subdomain").value("alice"))
                .andExpect(jsonPath("$.brandText").value("Alice Store"));
    }

    @Test
    void unknownCustomDomain_returns404_notPlatform() throws Exception {
        mockMvc.perform(visitor("unknown.example.com"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("tenant_not_found"))
                .andExpect(jsonPath("$.host").value("unknown.example.com"));
    }

    @Test
    void nonServableCustomDomain_returns404() throws Exception {
        // Pending/suspended/plan-expired domains resolve to empty in
        // findActiveByCustomDomain — same 404 as unknown hosts.
        when(tenantConfigService.findActiveByCustomDomain("pending.example.com"))
                .thenReturn(Optional.empty());
        mockMvc.perform(visitor("pending.example.com"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("tenant_not_found"));
    }

    @Test
    void cloudRunHost_returnsPlatform() throws Exception {
        mockMvc.perform(visitor("media-store-api-owuexaao5a-ew.a.run.app"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("platform"));
    }

    // ---- installOffered (PWA CTA policy) ---------------------------------

    @Test
    void installOffered_apexTrue_subdomainFalse_customDomainTrue() throws Exception {
        when(tenantConfigService.findActiveBySubdomain("alice")).thenReturn(Optional.of(tenant("alice")));
        when(tenantConfigService.findActiveByCustomDomain("shop.example.com"))
                .thenReturn(Optional.of(tenant("alice")));
        mockMvc.perform(visitor("earnlumens.org"))
                .andExpect(jsonPath("$.installOffered").value(true));
        mockMvc.perform(visitor("alice.earnlumens.org"))
                .andExpect(jsonPath("$.installOffered").value(false));
        mockMvc.perform(visitor("shop.example.com"))
                .andExpect(jsonPath("$.installOffered").value(true));
    }

    @Test
    void visitor_pwaIconKey_onlyOnCustomDomain() throws Exception {
        TenantReadModel t = tenant("alice");
        t.setPwaIconR2Key("public/tenants/alice/appicon/abc.png");
        when(tenantConfigService.findActiveBySubdomain("alice")).thenReturn(Optional.of(t));
        when(tenantConfigService.findActiveByCustomDomain("shop.example.com")).thenReturn(Optional.of(t));
        mockMvc.perform(visitor("alice.earnlumens.org"))
                .andExpect(jsonPath("$.pwaIconR2Key").doesNotExist());
        mockMvc.perform(visitor("shop.example.com"))
                .andExpect(jsonPath("$.pwaIconR2Key").value("public/tenants/alice/appicon/abc.png"));
    }

    // ---- /manifest -------------------------------------------------------

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder manifest(String host) {
        return get("/public/tenant/manifest").with(req -> {
            req.setServerName(host);
            return req;
        });
    }

    @Test
    void manifest_apex_isOfficialPlatformManifest() throws Exception {
        mockMvc.perform(manifest("earnlumens.org"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/manifest+json"))
                .andExpect(jsonPath("$.id").value("/"))
                .andExpect(jsonPath("$.scope").value("/"))
                .andExpect(jsonPath("$.start_url").value("/?source=pwa"))
                .andExpect(jsonPath("$.name").value("Earn Lumens"))
                .andExpect(jsonPath("$.icons[0].src").value("/pwa/pwa-192.png"))
                .andExpect(jsonPath("$.related_applications[0].url")
                        .value("https://earnlumens.org/manifest.webmanifest"));
    }

    @Test
    void manifest_subdomain_usesTenantNameAndBadgeIcons_evenWhenTenantHasOwnIcon() throws Exception {
        TenantReadModel t = tenant("alice");
        t.setPwaIconR2Key("public/tenants/alice/appicon/abc.png");
        when(tenantConfigService.findActiveBySubdomain("alice")).thenReturn(Optional.of(t));
        mockMvc.perform(manifest("alice.earnlumens.org"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alice Store"))
                .andExpect(jsonPath("$.short_name").value("Alice Store"))
                .andExpect(jsonPath("$.icons[0].src").value("/pwa/pwa-tenant-192.png"))
                .andExpect(jsonPath("$.icons[2].purpose").value("maskable"))
                .andExpect(jsonPath("$.related_applications[0].url")
                        .value("https://alice.earnlumens.org/manifest.webmanifest"));
    }

    @Test
    void manifest_customDomain_usesOwnIconWhenUploaded() throws Exception {
        TenantReadModel t = tenant("alice");
        t.setBrowserTitle("Alice's Marketplace of Wonders");
        t.setPwaIconR2Key("public/tenants/alice/appicon/abc-123.png");
        when(tenantConfigService.findActiveByCustomDomain("shop.example.com")).thenReturn(Optional.of(t));
        mockMvc.perform(manifest("shop.example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alice's Marketplace of Wonders"))
                .andExpect(jsonPath("$.short_name").value("Alice's Mark"))
                .andExpect(jsonPath("$.icons[0].src").value("/cdn/public/tenants/alice/appicon/abc-123.png"))
                .andExpect(jsonPath("$.icons[1].purpose").value("maskable"))
                .andExpect(jsonPath("$.related_applications[0].url")
                        .value("https://shop.example.com/manifest.webmanifest"));
    }

    @Test
    void manifest_customDomain_withoutIcon_fallsBackToBadge_neverBarePlatformIcon() throws Exception {
        when(tenantConfigService.findActiveByCustomDomain("shop.example.com"))
                .thenReturn(Optional.of(tenant("alice")));
        mockMvc.perform(manifest("shop.example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.icons[0].src").value("/pwa/pwa-tenant-192.png"));
    }

    @Test
    void manifest_customDomain_rejectsIconKeyOutsideAppiconNamespace() throws Exception {
        TenantReadModel t = tenant("alice");
        t.setPwaIconR2Key("public/tenants/alice/logo/evil.png");
        when(tenantConfigService.findActiveByCustomDomain("shop.example.com")).thenReturn(Optional.of(t));
        mockMvc.perform(manifest("shop.example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.icons[0].src").value("/pwa/pwa-tenant-192.png"));
    }

    @Test
    void manifest_unknownHost_returns404() throws Exception {
        mockMvc.perform(manifest("ghost.earnlumens.org"))
                .andExpect(status().isNotFound());
        mockMvc.perform(manifest("unknown.example.com"))
                .andExpect(status().isNotFound());
    }
}
