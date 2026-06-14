package org.earnlumens.mediastore.web.media;

import jakarta.servlet.http.HttpServletRequest;
import org.earnlumens.mediastore.application.media.ReportService;
import org.earnlumens.mediastore.domain.media.model.ReportReason;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.ReportEntity;
import org.earnlumens.mediastore.infrastructure.tenant.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for user content reports.
 *
 * <ul>
 *   <li>{@code POST /api/reports/{entryId}} — submit a report</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private static final Logger logger = LoggerFactory.getLogger(ReportController.class);

    private final TenantResolver tenantResolver;
    private final ReportService reportService;

    public ReportController(TenantResolver tenantResolver, ReportService reportService) {
        this.tenantResolver = tenantResolver;
        this.reportService = reportService;
    }

    /**
     * Submit a report against a published entry.
     * Body: { "reason": "SCAM", "comment": "optional text" }
     */
    @PostMapping("/{entryId}")
    public ResponseEntity<?> submitReport(
            @PathVariable("entryId") String entryId,
            @RequestBody SubmitReportRequest body,
            HttpServletRequest request) {

        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        String tenantId = tenantResolver.resolve(request);
        String username = extractUsername();

        // Validate reason
        ReportReason reason;
        try {
            reason = ReportReason.valueOf(body.reason().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "INVALID_REASON"));
        }

        try {
            ReportEntity report = reportService.submitReport(
                    tenantId, userId, username, entryId, reason, body.comment());

            return ResponseEntity.status(201).body(Map.of(
                    "id", report.getId(),
                    "severity", report.getSeverity(),
                    "message", "Report submitted"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            String msg = e.getMessage();
            if ("DAILY_REPORT_LIMIT_REACHED".equals(msg)) {
                return ResponseEntity.status(429).body(Map.of("error", msg));
            }
            // ALREADY_REPORTED
            return ResponseEntity.status(409).body(Map.of("error", msg));
        }
    }

    /**
     * Submit a report against a published collection.
     * Body: { "reason": "SCAM", "comment": "optional text" }
     */
    @PostMapping("/collection/{collectionId}")
    public ResponseEntity<?> submitCollectionReport(
            @PathVariable("collectionId") String collectionId,
            @RequestBody SubmitReportRequest body,
            HttpServletRequest request) {

        String userId = extractUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        String tenantId = tenantResolver.resolve(request);
        String username = extractUsername();

        // Validate reason
        ReportReason reason;
        try {
            reason = ReportReason.valueOf(body.reason().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "INVALID_REASON"));
        }

        try {
            ReportEntity report = reportService.submitCollectionReport(
                    tenantId, userId, username, collectionId, reason, body.comment());

            return ResponseEntity.status(201).body(Map.of(
                    "id", report.getId(),
                    "severity", report.getSeverity(),
                    "message", "Report submitted"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            String msg = e.getMessage();
            if ("DAILY_REPORT_LIMIT_REACHED".equals(msg)) {
                return ResponseEntity.status(429).body(Map.of("error", msg));
            }
            // ALREADY_REPORTED
            return ResponseEntity.status(409).body(Map.of("error", msg));
        }
    }

    private String extractUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof OAuth2User principal)) return null;
        Object idAttr = principal.getAttribute("id");
        return idAttr != null ? idAttr.toString() : null;
    }

    private String extractUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof OAuth2User principal)) return null;
        Object attr = principal.getAttribute("username");
        return attr != null ? attr.toString() : null;
    }

    public record SubmitReportRequest(String reason, String comment) {}
}
