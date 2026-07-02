package org.earnlumens.mediastore.web.reseller;

import jakarta.validation.Valid;
import org.earnlumens.mediastore.application.reseller.ResellerErrorCode;
import org.earnlumens.mediastore.application.reseller.ResellerException;
import org.earnlumens.mediastore.application.reseller.ResellerService;
import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.earnlumens.mediastore.web.reseller.dto.CreateResellerLinkRequest;
import org.earnlumens.mediastore.web.reseller.dto.ResellerEntryInfoResponse;
import org.earnlumens.mediastore.web.reseller.dto.ResellerLinkResponse;
import org.earnlumens.mediastore.web.reseller.dto.UpdateResellerWalletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Reseller self-service: a logged-in user generates and manages links that earn
 * them a commission for distributing another creator's paid content.
 *
 * <p>The public read ({@code GET /public/resellers/entries/{id}}) powers the
 * "EARN LUMENS" modal and is reachable without login; the caller id is read
 * opportunistically to flag own content. All mutating endpoints live under
 * {@code /api/resellers/**}, which {@code WebSecurityConfig} guards with
 * {@code anyRequest().authenticated()}. The tenant is always resolved from the
 * request host via {@link TenantContext}, never from the client.
 */
@RestController
public class ResellerController {

    private final ResellerService service;

    public ResellerController(ResellerService service) {
        this.service = service;
    }

    /** Whether/how an entry can be resold, plus its current price. Public. */
    @GetMapping("/public/resellers/entries/{entryId}")
    public ResellerEntryInfoResponse entryInfo(@PathVariable String entryId) {
        String tenantId = TenantContext.require();
        String caller = optionalCaller();
        return ResellerEntryInfoResponse.of(service.getEntryResellerInfo(tenantId, caller, entryId));
    }

    /** Generate (or fetch the existing) reseller link for an entry. */
    @PostMapping("/api/resellers/links")
    public ResellerLinkResponse create(@Valid @RequestBody CreateResellerLinkRequest req) {
        String tenantId = TenantContext.require();
        String caller = requireCaller();
        return ResellerLinkResponse.of(
                service.getOrCreateLink(tenantId, caller, req.entryId(), req.wallet()));
    }

    /** All reseller links owned by the caller under the current tenant. */
    @GetMapping("/api/resellers/links")
    public List<ResellerLinkResponse> listMine() {
        String tenantId = TenantContext.require();
        String caller = requireCaller();
        return service.listMyLinks(tenantId, caller).stream()
                .map(ResellerLinkResponse::of)
                .toList();
    }

    /** Change the payout wallet of an owned reseller link. */
    @PatchMapping("/api/resellers/links/{linkId}/wallet")
    public ResellerLinkResponse updateWallet(@PathVariable String linkId,
                                             @Valid @RequestBody UpdateResellerWalletRequest req) {
        String tenantId = TenantContext.require();
        String caller = requireCaller();
        return ResellerLinkResponse.of(
                service.updateLinkWallet(tenantId, caller, linkId, req.wallet()));
    }

    // ===================== plumbing =====================

    private static String requireCaller() {
        String id = principalAttr("id");
        if (id == null || id.isBlank()) {
            throw new ResellerException(ResellerErrorCode.FORBIDDEN, 401);
        }
        return id;
    }

    private static String optionalCaller() {
        return principalAttr("id");
    }

    private static String principalAttr(String attribute) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof OAuth2User principal)) return null;
        Object value = principal.getAttribute(attribute);
        return value != null ? value.toString() : null;
    }

    @ExceptionHandler(ResellerException.class)
    public ResponseEntity<Map<String, String>> handleReseller(ResellerException ex) {
        return ResponseEntity.status(ex.getHttpStatus())
                .body(Map.of("error", ex.getErrorCode().code()));
    }
}
