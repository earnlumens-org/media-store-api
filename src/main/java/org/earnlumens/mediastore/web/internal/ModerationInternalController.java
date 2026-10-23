package org.earnlumens.mediastore.web.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.earnlumens.mediastore.application.media.ModerationActionService;
import org.earnlumens.mediastore.application.media.ContentModerationService;
import org.earnlumens.mediastore.domain.media.model.Dispute;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.ModerationCase;
import org.earnlumens.mediastore.domain.media.repository.DisputeRepository;
import org.earnlumens.mediastore.domain.media.repository.ModerationCaseRepository;
import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Internal moderation surface for admin-api (MYCELIUM-RESILIENCE.md H3 / N9).
 *
 * <p>admin-api authenticates the moderator and forwards the action here so
 * that every administrative write goes through the full {@code Entry} model
 * and the single implementation of blocks, cases, disputes and pending
 * versions. Before H3 admin-api saved its own partial document model
 * straight into {@code entries}, which replaced the whole document and
 * dropped every field that model did not declare.
 *
 * <p><b>Security:</b> mounted under {@code /api/internal/**} (permitAll,
 * exempt from edge auth — reached directly on {@code .run.app}); gated by
 * the shared {@code X-Internal-Secret} compared in constant time and
 * fail-closed while unconfigured (same contract as
 * {@link TenantCacheController}). Every request names its tenant and the
 * services re-scope every query by it.
 */
@RestController
@RequestMapping("/api/internal/moderation")
public class ModerationInternalController {

    private static final Logger logger = LoggerFactory.getLogger(ModerationInternalController.class);
    private static final String SECRET_PLACEHOLDER = "CHANGE_ME_IN_ENV";
    private static final String TENANT_PATTERN = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$";

    public record ActionRequest(
            @NotBlank @Size(max = 63) @Pattern(regexp = TENANT_PATTERN) String tenantId,
            @NotBlank @Size(max = 200) String actor,
            @Size(max = 2000) String justification) {}

    private final ModerationActionService actions;
    private final ContentModerationService contentModeration;
    private final ModerationCaseRepository caseRepository;
    private final DisputeRepository disputeRepository;
    private final String sharedSecret;

    public ModerationInternalController(ModerationActionService actions,
                                        ContentModerationService contentModeration,
                                        ModerationCaseRepository caseRepository,
                                        DisputeRepository disputeRepository,
                                        @Value("${mediastore.internal.adminSecret:}") String sharedSecret) {
        this.actions = actions;
        this.contentModeration = contentModeration;
        this.caseRepository = caseRepository;
        this.disputeRepository = disputeRepository;
        this.sharedSecret = sharedSecret == null ? "" : sharedSecret;
    }

    // ─── Content ─────────────────────────────────────────────────

    @PostMapping("/entries/{entryId}/approve")
    public ResponseEntity<?> approve(@RequestHeader(value = "X-Internal-Secret", required = false) String secret,
                                     @PathVariable String entryId, @Valid @RequestBody ActionRequest req) {
        return guarded(secret, req.tenantId(), () -> entryBody(actions.approveContent(req.tenantId(), entryId, req.actor())));
    }

    @PostMapping("/entries/{entryId}/reject")
    public ResponseEntity<?> reject(@RequestHeader(value = "X-Internal-Secret", required = false) String secret,
                                    @PathVariable String entryId, @Valid @RequestBody ActionRequest req) {
        return guarded(secret, req.tenantId(), () -> entryBody(
                actions.rejectContent(req.tenantId(), entryId, req.justification(), req.actor())));
    }

    @PostMapping("/entries/{entryId}/takedown")
    public ResponseEntity<?> takedown(@RequestHeader(value = "X-Internal-Secret", required = false) String secret,
                                      @PathVariable String entryId, @Valid @RequestBody ActionRequest req) {
        return guarded(secret, req.tenantId(), () -> caseBody(
                actions.takedown(req.tenantId(), entryId, req.justification(), req.actor())));
    }

    @PostMapping("/entries/{entryId}/reinstate")
    public ResponseEntity<?> reinstate(@RequestHeader(value = "X-Internal-Secret", required = false) String secret,
                                       @PathVariable String entryId, @Valid @RequestBody ActionRequest req) {
        return guarded(secret, req.tenantId(), () -> entryBody(actions.reinstate(req.tenantId(), entryId, req.actor())));
    }

    // ─── Pending version ─────────────────────────────────────────

    @PostMapping("/entries/{entryId}/pending-version/approve")
    public ResponseEntity<?> approvePending(@RequestHeader(value = "X-Internal-Secret", required = false) String secret,
                                            @PathVariable String entryId, @Valid @RequestBody ActionRequest req) {
        return guarded(secret, req.tenantId(), () -> entryBody(
                actions.approvePendingVersion(req.tenantId(), entryId, req.actor())));
    }

    @PostMapping("/entries/{entryId}/pending-version/reject")
    public ResponseEntity<?> rejectPending(@RequestHeader(value = "X-Internal-Secret", required = false) String secret,
                                           @PathVariable String entryId, @Valid @RequestBody ActionRequest req) {
        return guarded(secret, req.tenantId(), () -> entryBody(
                actions.rejectPendingVersion(req.tenantId(), entryId, req.justification(), req.actor())));
    }

    // ─── Collections ─────────────────────────────────────────────

    @PostMapping("/collections/{collectionId}/takedown")
    public ResponseEntity<?> takedownCollection(@RequestHeader(value = "X-Internal-Secret", required = false) String secret,
                                                @PathVariable String collectionId, @Valid @RequestBody ActionRequest req) {
        return guarded(secret, req.tenantId(), () -> caseBody(
                contentModeration.takedownCollection(req.tenantId(), collectionId, req.justification(), req.actor())));
    }

    @PostMapping("/collections/{collectionId}/reinstate")
    public ResponseEntity<?> reinstateCollection(@RequestHeader(value = "X-Internal-Secret", required = false) String secret,
                                                 @PathVariable String collectionId, @Valid @RequestBody ActionRequest req) {
        return guarded(secret, req.tenantId(), () -> {
            var c = contentModeration.reinstateCollection(req.tenantId(), collectionId, req.actor());
            return Map.of("id", c.getId(), "status", c.getStatus().name(), "blocked", c.isBlocked());
        });
    }

    // ─── Cases & disputes (read) ─────────────────────────────────

    @GetMapping("/cases")
    public ResponseEntity<?> cases(@RequestHeader(value = "X-Internal-Secret", required = false) String secret,
                                   @RequestParam @Pattern(regexp = TENANT_PATTERN) String tenantId,
                                   @RequestParam(defaultValue = "100") int limit) {
        return guarded(secret, tenantId, () -> caseRepository.findByTenantId(tenantId, Math.min(Math.max(limit, 1), 500))
                .stream().map(ModerationInternalController::caseBody).toList());
    }

    @GetMapping("/cases/{caseId}/disputes")
    public ResponseEntity<?> disputes(@RequestHeader(value = "X-Internal-Secret", required = false) String secret,
                                      @RequestParam @Pattern(regexp = TENANT_PATTERN) String tenantId,
                                      @PathVariable String caseId) {
        return guarded(secret, tenantId, () -> disputeRepository.findByTenantIdAndModerationCaseId(tenantId, caseId)
                .stream().map(ModerationInternalController::disputeBody).toList());
    }

    // ─── Plumbing ────────────────────────────────────────────────

    private ResponseEntity<?> guarded(String presented, String tenantId, Supplier<Object> action) {
        if (sharedSecret.isBlank() || SECRET_PLACEHOLDER.equals(sharedSecret)) {
            logger.error("Internal moderation: refusing — mediastore.internal.adminSecret is not configured");
            return ResponseEntity.status(503).body(Map.of("error", "internal_moderation_disabled"));
        }
        if (presented == null || !MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), sharedSecret.getBytes(StandardCharsets.UTF_8))) {
            logger.warn("Internal moderation: rejected — invalid or missing X-Internal-Secret");
            return ResponseEntity.status(403).body(Map.of("error", "forbidden"));
        }
        try {
            Object[] out = new Object[1];
            TenantContext.runWithoutTenant(() -> out[0] = action.get());
            return ResponseEntity.ok(out[0]);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Internal moderation action failed (tenant={}): {}", tenantId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal error"));
        }
    }

    private static Map<String, Object> entryBody(Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("status", e.getStatus() != null ? e.getStatus().name() : null);
        m.put("previousStatus", e.getPreviousStatus() != null ? e.getPreviousStatus().name() : null);
        m.put("blocked", e.isBlocked());
        m.put("caseId", e.isBlocked() ? e.getModerationBlock().getCaseId() : null);
        m.put("hasPendingVersion", e.getPendingVersion() != null);
        m.put("currentVersion", e.currentVersionOrOne());
        m.put("moderationFeedback", e.getModerationFeedback());
        return m;
    }

    private static Map<String, Object> caseBody(ModerationCase c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("tenantId", c.getTenantId());
        m.put("kind", c.getKind() != null ? c.getKind().name() : null);
        m.put("entryIds", c.getEntryIds());
        m.put("collectionIds", c.getCollectionIds());
        m.put("reason", c.getReason());
        m.put("actor", c.getActor());
        m.put("createdAt", c.getCreatedAt());
        m.put("resolvedAt", c.getResolvedAt());
        m.put("resolution", c.getResolution() != null ? c.getResolution().name() : null);
        return m;
    }

    private static Map<String, Object> disputeBody(Dispute d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("moderationCaseId", d.getModerationCaseId());
        m.put("entitlementId", d.getEntitlementId());
        m.put("orderId", d.getOrderId());
        m.put("userId", d.getUserId());
        m.put("entryId", d.getEntryId());
        m.put("affectedEntryIds", d.getAffectedEntryIds());
        m.put("collectionId", d.getCollectionId());
        m.put("reason", d.getReason());
        m.put("status", d.getStatus() != null ? d.getStatus().name() : null);
        m.put("createdAt", d.getCreatedAt());
        m.put("resolvedAt", d.getResolvedAt());
        m.put("resolvedBy", d.getResolvedBy());
        return m;
    }
}
