package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.user.repository.UserRepository;
import org.earnlumens.mediastore.infrastructure.original.OriginalClaimDocument;
import org.earnlumens.mediastore.infrastructure.original.OriginalClaimRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Original First — automatic content attribution.
 *
 * <p>Fully automatic, transparent, no tickets and no bureaucracy:
 * <ul>
 *   <li><b>Detection</b> — at upload-finalize time the FULL asset's fingerprint
 *       is matched against the tenant's catalog. A duplicate of another user's
 *       earlier upload publishes normally but is flagged as a <b>Remix</b>
 *       (visible badge, royalty to the original, never blocked).</li>
 *   <li><b>Reassignment</b> — when the real creator uploads <em>after</em> a
 *       copier (or presses "Claim as Original"), a deterministic score decides
 *       ownership and, when granted, the whole fingerprint group is rebased to
 *       the new canonical root in one bulk update.</li>
 *   <li><b>Demotion</b> — accounts whose published catalog is predominantly
 *       pure remixes (≥{@value #DEMOTION_REMIX_RATIO} with at least
 *       {@value #DEMOTION_MIN_ENTRIES} items) get their remix entries demoted
 *       in the default Explore ordering. They are never banned or hidden.</li>
 * </ul>
 *
 * <p><b>Ownership score</b> (max 100, breakdown persisted on every claim):
 * <ul>
 *   <li>Upload priority — {@value #SCORE_UPLOAD_PRIORITY} pts to the party with
 *       the earlier entry. First-to-upload remains the strongest signal.</li>
 *   <li>Creator trust — up to {@value #SCORE_CREATOR_TRUST} pts scaled by
 *       (1 − remixRatio): a catalog of original work signals a real creator,
 *       a catalog of copies signals a freeloader.</li>
 *   <li>Account seniority — {@value #SCORE_ACCOUNT_SENIORITY} pts to the older
 *       account (harder to fake than fresh sockpuppets).</li>
 * </ul>
 * The claimant only wins with a margin of {@value #CLAIM_WIN_MARGIN} pts over
 * the current holder, so credit never ping-pongs between equal parties.
 * Watermarks, EXIF metadata and external platform links are deliberately kept
 * out of the automatic score (all three are trivially forged or stripped);
 * they are documented as future manual-evidence inputs in ORIGINAL-FIRST.md.
 */
@Service
public class OriginalAttributionService {

    private static final Logger logger = LoggerFactory.getLogger(OriginalAttributionService.class);

    // ── Ownership score weights ─────────────────────────────────────────────
    static final double SCORE_UPLOAD_PRIORITY = 50;
    static final double SCORE_CREATOR_TRUST = 30;
    static final double SCORE_ACCOUNT_SENIORITY = 20;
    /** Claimant must beat the holder by this many points to flip ownership. */
    static final double CLAIM_WIN_MARGIN = 15;

    // ── Anti-abuse limits ───────────────────────────────────────────────────
    static final int DAILY_CLAIM_LIMIT = 5;

    // ── Visibility demotion thresholds ──────────────────────────────────────
    static final double DEMOTION_REMIX_RATIO = 0.70;
    static final int DEMOTION_MIN_ENTRIES = 5;

    /** Statuses excluded from remix-ratio math (dead content says nothing). */
    private static final List<EntryStatus> RATIO_EXCLUDED_STATUSES =
            List.of(EntryStatus.DELETED, EntryStatus.REJECTED);

    private final EntryRepository entryRepository;
    private final UserRepository userRepository;
    private final OriginalClaimRepository originalClaimRepository;

    public OriginalAttributionService(EntryRepository entryRepository,
                                      UserRepository userRepository,
                                      OriginalClaimRepository originalClaimRepository) {
        this.entryRepository = entryRepository;
        this.userRepository = userRepository;
        this.originalClaimRepository = originalClaimRepository;
    }

    // ── Detection at upload time ────────────────────────────────────────────

    /**
     * Runs duplicate detection for a freshly fingerprinted entry and mutates it
     * in place (remix flag + original attribution). The caller persists the
     * entry. Best-effort: any failure leaves the entry untouched — detection
     * must never break an upload.
     *
     * @return true when the entry was flagged as a remix
     */
    public boolean applyRemixDetection(String tenantId, Entry entry) {
        if (entry.getContentFingerprint() == null || entry.getContentFingerprint().isBlank()) {
            return false;
        }
        try {
            List<Entry> group = entryRepository
                    .findByTenantIdAndContentFingerprint(tenantId, entry.getContentFingerprint())
                    .stream()
                    .filter(e -> !e.getId().equals(entry.getId()))
                    .filter(e -> e.getStatus() != EntryStatus.DELETED)
                    .toList();
            if (group.isEmpty()) {
                return false;
            }

            Entry canonical = resolveCanonical(group);
            if (canonical.getUserId().equals(entry.getUserId())) {
                // Re-upload of the user's own content — a duplicate, not a remix.
                return false;
            }

            entry.setRemix(true);
            entry.setOriginalEntryId(canonical.getId());
            entry.setOriginalUserId(canonical.getUserId());
            entry.setOriginalAuthorUsername(canonical.getAuthorUsername());
            entry.setRemixDetectedAt(LocalDateTime.now());
            entry.setVisibilityDemoted(shouldDemote(tenantId, entry.getUserId(), 1));

            logger.info("originalFirst: remix detected entry={} original={} originalUser={} tenant={}",
                    entry.getId(), canonical.getId(), canonical.getUserId(), tenantId);
            return true;
        } catch (RuntimeException e) {
            logger.warn("originalFirst: detection failed for entry={} tenant={}: {}",
                    entry.getId(), tenantId, e.getMessage());
            return false;
        }
    }

    /**
     * Canonical root of a fingerprint group: the earliest-created entry that is
     * currently credited as original; falls back to the earliest entry overall
     * (defensive — a well-formed group always has exactly one original).
     */
    private Entry resolveCanonical(List<Entry> group) {
        return group.stream()
                .filter(e -> !e.isRemix())
                .min(Comparator.comparing(Entry::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElseGet(() -> group.stream()
                        .min(Comparator.comparing(Entry::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .orElseThrow());
    }

    // ── Claim as Original ───────────────────────────────────────────────────

    /** Outcome of a processed claim, returned to the API layer. */
    public record ClaimResult(boolean granted, double claimantScore, double holderScore,
                              String scoreBreakdown, String newOriginalEntryId) {}

    /**
     * Processes a "Claim as Original" request filed from any entry of a
     * fingerprint group. Fully automatic: scores both parties, persists the
     * audit record and — when granted — rebases the whole group atomically.
     *
     * @param entryId the entry the claimant pressed the button on (any group member)
     * @throws IllegalArgumentException NO_FINGERPRINT, NO_MATCHING_ENTRY, ALREADY_ORIGINAL, ENTRY_NOT_FOUND
     * @throws IllegalStateException    ALREADY_CLAIMED, DAILY_CLAIM_LIMIT_REACHED
     */
    public ClaimResult claimAsOriginal(String tenantId, String claimantUserId, String entryId) {
        Entry target = entryRepository.findByTenantIdAndId(tenantId, entryId)
                .orElseThrow(() -> new IllegalArgumentException("ENTRY_NOT_FOUND"));
        String fingerprint = target.getContentFingerprint();
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("NO_FINGERPRINT");
        }

        // Anti-abuse: rolling daily quota + one claim per user per group, ever.
        long claimsToday = originalClaimRepository.countByTenantIdAndClaimantUserIdAndCreatedAtAfter(
                tenantId, claimantUserId, LocalDateTime.now().minusHours(24));
        if (claimsToday >= DAILY_CLAIM_LIMIT) {
            throw new IllegalStateException("DAILY_CLAIM_LIMIT_REACHED");
        }
        if (originalClaimRepository.existsByTenantIdAndFingerprintAndClaimantUserId(
                tenantId, fingerprint, claimantUserId)) {
            throw new IllegalStateException("ALREADY_CLAIMED");
        }

        List<Entry> group = entryRepository.findByTenantIdAndContentFingerprint(tenantId, fingerprint)
                .stream()
                .filter(e -> e.getStatus() != EntryStatus.DELETED)
                .toList();

        Entry canonical = resolveCanonical(group);
        if (canonical.getUserId().equals(claimantUserId)) {
            throw new IllegalArgumentException("ALREADY_ORIGINAL");
        }

        // The claimant's evidence: their own earliest entry inside the group.
        Entry claimantEntry = group.stream()
                .filter(e -> e.getUserId().equals(claimantUserId))
                .min(Comparator.comparing(Entry::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElseThrow(() -> new IllegalArgumentException("NO_MATCHING_ENTRY"));

        // ── Deterministic ownership score ──────────────────────────────────
        StringBuilder breakdown = new StringBuilder();
        double claimantScore = 0;
        double holderScore = 0;

        // 1. Upload priority (strongest signal: who published these bytes first)
        boolean claimantEarlier = isEarlier(claimantEntry.getCreatedAt(), canonical.getCreatedAt());
        if (claimantEarlier) claimantScore += SCORE_UPLOAD_PRIORITY; else holderScore += SCORE_UPLOAD_PRIORITY;
        breakdown.append("uploadPriority(").append(SCORE_UPLOAD_PRIORITY).append("): ")
                .append(claimantEarlier ? "claimant" : "holder");

        // 2. Creator trust: original-work ratio of each catalog
        double claimantTrust = SCORE_CREATOR_TRUST * (1.0 - remixRatio(tenantId, claimantUserId));
        double holderTrust = SCORE_CREATOR_TRUST * (1.0 - remixRatio(tenantId, canonical.getUserId()));
        claimantScore += claimantTrust;
        holderScore += holderTrust;
        breakdown.append("; creatorTrust: claimant=").append(round1(claimantTrust))
                .append(" holder=").append(round1(holderTrust));

        // 3. Account seniority
        LocalDateTime claimantSince = accountCreatedAt(claimantUserId);
        LocalDateTime holderSince = accountCreatedAt(canonical.getUserId());
        boolean claimantOlder = isEarlier(claimantSince, holderSince);
        if (claimantOlder) claimantScore += SCORE_ACCOUNT_SENIORITY; else holderScore += SCORE_ACCOUNT_SENIORITY;
        breakdown.append("; accountSeniority(").append(SCORE_ACCOUNT_SENIORITY).append("): ")
                .append(claimantOlder ? "claimant" : "holder");

        boolean granted = claimantScore >= holderScore + CLAIM_WIN_MARGIN;
        breakdown.append("; total: claimant=").append(round1(claimantScore))
                .append(" holder=").append(round1(holderScore))
                .append("; margin required=").append(CLAIM_WIN_MARGIN);

        // ── Persist the audit record first (unique index = idempotency guard) ──
        OriginalClaimDocument claim = new OriginalClaimDocument();
        claim.setTenantId(tenantId);
        claim.setFingerprint(fingerprint);
        claim.setClaimantUserId(claimantUserId);
        claim.setClaimantEntryId(claimantEntry.getId());
        claim.setHolderUserId(canonical.getUserId());
        claim.setHolderEntryId(canonical.getId());
        claim.setOutcome(granted ? "GRANTED" : "REJECTED");
        claim.setClaimantScore(claimantScore);
        claim.setHolderScore(holderScore);
        claim.setScoreBreakdown(breakdown.toString());
        originalClaimRepository.save(claim);

        if (!granted) {
            logger.info("originalFirst: claim REJECTED tenant={} fingerprint={} claimant={} ({} vs {})",
                    tenantId, fingerprint, claimantUserId, round1(claimantScore), round1(holderScore));
            return new ClaimResult(false, claimantScore, holderScore, breakdown.toString(), canonical.getId());
        }

        // ── Granted: reassign credit and rebase the whole group ────────────
        claimantEntry.setRemix(false);
        claimantEntry.setOriginalEntryId(null);
        claimantEntry.setOriginalUserId(null);
        claimantEntry.setOriginalAuthorUsername(null);
        claimantEntry.setRemixDetectedAt(null);
        claimantEntry.setUpdatedAt(LocalDateTime.now());
        entryRepository.save(claimantEntry);

        long rebased = entryRepository.rebaseRemixGroup(tenantId, fingerprint,
                claimantEntry.getId(), claimantUserId, claimantEntry.getAuthorUsername());

        // Recompute demotion for both parties (their remix ratios just changed).
        recomputeVisibilityDemotion(tenantId, claimantUserId);
        recomputeVisibilityDemotion(tenantId, canonical.getUserId());

        logger.info("originalFirst: claim GRANTED tenant={} fingerprint={} newOriginal={} rebased={} ({} vs {})",
                tenantId, fingerprint, claimantEntry.getId(), rebased,
                round1(claimantScore), round1(holderScore));
        return new ClaimResult(true, claimantScore, holderScore, breakdown.toString(), claimantEntry.getId());
    }

    // ── Visibility demotion ─────────────────────────────────────────────────

    /**
     * Recomputes and applies the demotion flag on all of a user's remix entries.
     * Called after detection and after claim swaps.
     */
    public void recomputeVisibilityDemotion(String tenantId, String userId) {
        try {
            entryRepository.updateVisibilityDemotedForUserRemixes(
                    tenantId, userId, shouldDemote(tenantId, userId, 0));
        } catch (RuntimeException e) {
            logger.warn("originalFirst: demotion recompute failed user={} tenant={}: {}",
                    tenantId, userId, e.getMessage());
        }
    }

    /**
     * @param pendingRemixes remixes not yet persisted (the entry being
     *                       finalized) to include in the ratio
     */
    private boolean shouldDemote(String tenantId, String userId, int pendingRemixes) {
        long total = entryRepository.countByTenantIdAndUserIdAndStatusNotIn(
                tenantId, userId, RATIO_EXCLUDED_STATUSES) + pendingRemixes;
        if (total < DEMOTION_MIN_ENTRIES) return false;
        long remixes = entryRepository.countRemixesByTenantIdAndUserIdAndStatusNotIn(
                tenantId, userId, RATIO_EXCLUDED_STATUSES) + pendingRemixes;
        return ((double) remixes / total) >= DEMOTION_REMIX_RATIO;
    }

    private double remixRatio(String tenantId, String userId) {
        long total = entryRepository.countByTenantIdAndUserIdAndStatusNotIn(
                tenantId, userId, RATIO_EXCLUDED_STATUSES);
        if (total == 0) return 0.0;
        long remixes = entryRepository.countRemixesByTenantIdAndUserIdAndStatusNotIn(
                tenantId, userId, RATIO_EXCLUDED_STATUSES);
        return (double) remixes / total;
    }

    private LocalDateTime accountCreatedAt(String oauthUserId) {
        return userRepository.findByOauthUserId(oauthUserId)
                .map(u -> u.getCreatedAt())
                .orElse(null);
    }

    /** True when {@code a} is strictly earlier than {@code b}; nulls lose. */
    private static boolean isEarlier(LocalDateTime a, LocalDateTime b) {
        if (a == null) return false;
        if (b == null) return true;
        return a.isBefore(b);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
