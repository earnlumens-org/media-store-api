package org.earnlumens.mediastore.web.franchise;

import jakarta.validation.Valid;
import org.earnlumens.mediastore.application.franchise.FranchiseBrandingUpdate;
import org.earnlumens.mediastore.application.franchise.FranchiseConfigView;
import org.earnlumens.mediastore.application.franchise.FranchiseErrorCode;
import org.earnlumens.mediastore.application.franchise.FranchiseException;
import org.earnlumens.mediastore.application.franchise.FranchiseManagementService;
import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.earnlumens.mediastore.web.franchise.dto.CreateFranchiseRequest;
import org.earnlumens.mediastore.web.franchise.dto.FranchiseConfigResponse;
import org.earnlumens.mediastore.web.franchise.dto.FranchiseImageUploadRequest;
import org.earnlumens.mediastore.web.franchise.dto.FranchiseImageUploadResponse;
import org.earnlumens.mediastore.web.franchise.dto.ManagedFranchiseResponse;
import org.earnlumens.mediastore.web.franchise.dto.UpdateFranchiseRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Franchisee self-service for the public storefront: a logged-in user creates
 * and manages their own franchise under the current tenant.
 *
 * <p>Path is {@code /api/franchises/**} which is <b>not</b> under
 * {@code /public/**}, so {@code WebSecurityConfig}'s
 * {@code anyRequest().authenticated()} guarantees a valid logged-in caller. The
 * tenant is resolved from {@link TenantContext} (request host), never from the
 * client, and the service re-checks tenant ownership + eligibility against the
 * database on every call so revocations take effect immediately.
 *
 * <p>Franchisor governance (enabling the model, default commission, disabling a
 * franchise, banning a user) is NOT here — it lives in admin-api.
 */
@RestController
@RequestMapping("/api/franchises")
public class FranchiseManagementController {

    private final FranchiseManagementService service;

    public FranchiseManagementController(FranchiseManagementService service) {
        this.service = service;
    }

    /** Whether sign-ups are open on this storefront, and the commission. */
    @GetMapping("/config")
    public FranchiseConfigResponse config() {
        String tenantId = TenantContext.require();
        String caller = requireCaller();
        FranchiseConfigView view = service.getConfig(tenantId, caller);
        return FranchiseConfigResponse.of(view);
    }

    /** Franchises owned by the caller under the current tenant. */
    @GetMapping("/me")
    public List<ManagedFranchiseResponse> listMine() {
        String tenantId = TenantContext.require();
        String caller = requireCaller();
        return service.listMine(tenantId, caller).stream()
            .map(ManagedFranchiseResponse::of)
            .toList();
    }

    /** Create a franchise under the current tenant for the caller. */
    @PostMapping
    public ManagedFranchiseResponse create(@Valid @RequestBody CreateFranchiseRequest req) {
        String tenantId = TenantContext.require();
        String caller = requireCaller();
        String username = principalAttr("username");
        String displayName = principalAttr("name");
        return ManagedFranchiseResponse.of(service.createFranchise(
            tenantId, caller, username, displayName,
            req.getSlug(), req.getPayoutWallet(),
            req.getTitle(), req.getDescription(), req.getAccentColor()));
    }

    /** Edit the caller's own franchise branding. */
    @PatchMapping("/me/{franchiseId}")
    public ManagedFranchiseResponse updateMine(@PathVariable String franchiseId,
                                               @Valid @RequestBody UpdateFranchiseRequest req) {
        String tenantId = TenantContext.require();
        String caller = requireCaller();
        FranchiseBrandingUpdate update = new FranchiseBrandingUpdate(
            req.getTitle(), req.getDescription(), req.getLogoR2Key(),
            req.getCoverR2Key(), req.getAccentColor());
        return ManagedFranchiseResponse.of(
            service.updateOwnFranchise(tenantId, caller, franchiseId, update));
    }

    /**
     * Mint a presigned PUT URL to upload a branding image (logo or cover) for
     * the caller's own franchise. The client uploads directly to R2 and then
     * persists the returned {@code r2Key} via {@link #updateMine}.
     */
    @PostMapping("/me/{franchiseId}/branding-image")
    public FranchiseImageUploadResponse presignBrandingImage(
            @PathVariable String franchiseId,
            @Valid @RequestBody FranchiseImageUploadRequest req) {
        String tenantId = TenantContext.require();
        String caller = requireCaller();
        return FranchiseImageUploadResponse.of(service.presignBrandingImage(
            tenantId, caller, franchiseId,
            req.getSlot(), req.getContentType(), req.getFileSizeBytes()));
    }

    // ===================== plumbing =====================

    private static String requireCaller() {
        String id = principalAttr("id");
        if (id == null || id.isBlank()) {
            throw new FranchiseException(FranchiseErrorCode.FORBIDDEN, 401);
        }
        return id;
    }

    private static String principalAttr(String attribute) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof OAuth2User principal)) return null;
        Object value = principal.getAttribute(attribute);
        return value != null ? value.toString() : null;
    }

    @ExceptionHandler(FranchiseException.class)
    public ResponseEntity<Map<String, String>> handleFranchise(FranchiseException ex) {
        return ResponseEntity.status(ex.getHttpStatus())
            .body(Map.of("error", ex.getErrorCode().code()));
    }
}
