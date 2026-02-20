package org.earnlumens.mediastore.web.media;

// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║  ⚠️  TEMPORARY CONTROLLER — DELETE WHEN ADMIN/MODERATION SYSTEM EXISTS  ⚠️  ║
// ║                                                                              ║
// ║  This controller exists ONLY to bypass the moderation step (Step 6) during   ║
// ║  development. It auto-approves and auto-publishes all entries so the          ║
// ║  purchase flow (Step 7) can be tested end-to-end.                            ║
// ║                                                                              ║
// ║  TODO: Remove this entire file when implementing:                            ║
// ║    - dash.earnlumens.org admin panel                                         ║
// ║    - Role-based access (superadmin, tenant admin, moderator)                 ║
// ║    - Per-tenant moderation queues                                            ║
// ║    - Rejection reasons & audit log                                           ║
// ║                                                                              ║
// ║  Also remove from WebSecurityConfig permitAll: "/api/mock/**"                ║
// ║  (already there for other mock endpoints, but review when cleaning up)       ║
// ╚══════════════════════════════════════════════════════════════════════════════╝

import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ⚠️ TEMPORARY — Auto-approves and publishes all pending entries.
 * <p>
 * DELETE THIS FILE when the admin/moderation system is implemented.
 * <p>
 * Endpoints (no auth required — under /api/mock/**):
 * <ul>
 *   <li>{@code POST /api/mock/approve-all} — DRAFT/IN_REVIEW → APPROVED → PUBLISHED</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/mock")
public class TempAutoApproveController {

    // ⚠️ TEMPORARY — DELETE THIS ENTIRE FILE WHEN MODERATION SYSTEM EXISTS

    private static final Logger logger = LoggerFactory.getLogger(TempAutoApproveController.class);

    private final EntryRepository entryRepository;

    public TempAutoApproveController(EntryRepository entryRepository) {
        this.entryRepository = entryRepository;
    }

    /**
     * ⚠️ TEMPORARY — Approves and publishes ALL entries in DRAFT or IN_REVIEW status.
     * Sets status to PUBLISHED, visibility to PUBLIC, and publishedAt to now.
     * <p>
     * No authentication required. No tenant filtering.
     * DELETE when real moderation exists.
     */
    @PostMapping("/approve-all")
    public ResponseEntity<?> approveAll() {
        // ⚠️ TEMPORARY — no auth, no tenant check, approves everything

        List<Entry> drafts = entryRepository.findByStatus(EntryStatus.DRAFT);
        List<Entry> inReview = entryRepository.findByStatus(EntryStatus.IN_REVIEW);
        List<Entry> approved = entryRepository.findByStatus(EntryStatus.APPROVED);

        List<Entry> toPublish = new ArrayList<>();
        toPublish.addAll(drafts);
        toPublish.addAll(inReview);
        toPublish.addAll(approved);

        int count = 0;
        Map<String, Integer> byPreviousStatus = new HashMap<>();

        for (Entry entry : toPublish) {
            String previousStatus = entry.getStatus().name();

            entry.setStatus(EntryStatus.PUBLISHED);
            entry.setPublishedAt(LocalDateTime.now());
            entryRepository.save(entry);

            byPreviousStatus.merge(previousStatus, 1, Integer::sum);
            count++;

            logger.info("⚠️ TEMP auto-approve: entry={}, previous={}, title='{}'",
                    entry.getId(), previousStatus, entry.getTitle());
        }

        logger.info("⚠️ TEMP auto-approve complete: {} entries published — {}", count, byPreviousStatus);

        // ⚠️ TEMPORARY — this response format is for debugging only
        return ResponseEntity.ok(Map.of(
                "published", count,
                "byPreviousStatus", byPreviousStatus,
                "message", "⚠️ TEMPORARY — delete TempAutoApproveController when moderation system exists"
        ));
    }
}
