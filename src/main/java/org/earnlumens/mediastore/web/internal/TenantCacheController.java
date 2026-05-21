package org.earnlumens.mediastore.web.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Internal cache-invalidation endpoint for the in-memory tenant config cache.
 *
 * <p>Called by admin-api after every tenant mutation so the storefront sees
 * the new values immediately instead of waiting for the {@code TenantConfigService}
 * TTL to elapse (~5 min). Without this, the owner experiences a confusing
 * "save did nothing" delay after editing branding or the hero banner.
 *
 * <p><b>Security:</b>
 * <ul>
 *   <li>Mounted under {@code /api/internal/**} which is permitAll in
 *       {@code WebSecurityConfig} (no OAuth user); access is gated by a
 *       shared secret in {@code X-Internal-Secret} compared with
 *       {@link MessageDigest#isEqual(byte[], byte[])} to defeat timing
 *       attacks.</li>
 *   <li>Boots refuse to invalidate when the configured secret is missing or
 *       still the {@code CHANGE_ME_IN_ENV} placeholder — fail-closed so a
 *       misconfigured prod can never accept "" as a valid secret.</li>
 *   <li>The payload's {@code subdomain} is restricted to the same charset
 *       {@code TenantConfigService} accepts; anything else is rejected with
 *       400 without touching the cache.</li>
 *   <li>Worst case (header leaks) the attacker can only force re-reads from
 *       Mongo — no data is mutated or exposed by this endpoint.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/internal/tenant-cache")
public class TenantCacheController {

    private static final Logger logger = LoggerFactory.getLogger(TenantCacheController.class);
    private static final String SECRET_PLACEHOLDER = "CHANGE_ME_IN_ENV";

    private final TenantConfigService tenantConfigService;
    private final String sharedSecret;

    public TenantCacheController(
            TenantConfigService tenantConfigService,
            @Value("${mediastore.internal.tenantCacheSecret:}") String sharedSecret
    ) {
        this.tenantConfigService = tenantConfigService;
        this.sharedSecret = sharedSecret == null ? "" : sharedSecret;
    }

    @PostMapping("/invalidate")
    public ResponseEntity<?> invalidate(
            @RequestHeader(value = "X-Internal-Secret", required = false) String presented,
            @Valid @RequestBody InvalidateRequest body
    ) {
        // Fail-closed when the secret is unconfigured or still the placeholder.
        if (sharedSecret.isBlank() || SECRET_PLACEHOLDER.equals(sharedSecret)) {
            logger.error("Tenant cache invalidate: refusing — mediastore.internal.tenantCacheSecret is not configured");
            return ResponseEntity.status(503).body(Map.of("error", "tenant_cache_invalidation_disabled"));
        }
        if (presented == null || !constantTimeEquals(presented, sharedSecret)) {
            // Do NOT echo the subdomain back so a fuzzing attempt can't use
            // this endpoint as an oracle for "does this header match?".
            logger.warn("Tenant cache invalidate: rejected — invalid or missing X-Internal-Secret");
            return ResponseEntity.status(403).body(Map.of("error", "forbidden"));
        }

        String subdomain = body.subdomain().trim().toLowerCase();
        tenantConfigService.invalidate(subdomain);
        logger.info("Tenant cache invalidated for subdomain={}", subdomain);
        return ResponseEntity.ok(Map.of("status", "invalidated", "subdomain", subdomain));
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ba, bb);
    }

    /**
     * Bean Validation handles the basic shape so the controller body stays
     * focused on the security check. {@code subdomain} mirrors the regex
     * used by {@code PublicTenantController.SUBDOMAIN}.
     */
    public record InvalidateRequest(
            @NotBlank
            @Size(max = 63)
            @Pattern(regexp = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
            String subdomain
    ) {}
}
