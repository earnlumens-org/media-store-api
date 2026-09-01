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
}
