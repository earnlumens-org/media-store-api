package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.response.RatingAggregateResponse;
import org.earnlumens.mediastore.domain.media.dto.response.RatingResponse;
import org.earnlumens.mediastore.domain.media.model.*;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.media.repository.RatingAggregateRepository;
import org.earnlumens.mediastore.domain.media.repository.RatingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * User content votes (like / dislike + optional review) for a <em>target</em>
 * &mdash; an entry or a whole collection (e.g. a music album) &mdash; built
 * around one goal: <b>kill fraud</b>. The public score is the percentage of
 * likes, Roblox-style.
 *
 * <h3>Anti-fraud guarantees</h3>
 * <ol>
 *   <li><b>One vote per user per target</b> — enforced by a unique index plus
 *       upsert-on-edit. A single account can never stuff the ballot, and an
 *       anonymous (unauthenticated) caller is rejected at the controller.</li>
 *   <li><b>Proof-gated eligibility</b> — a paid target can only be rated by a
 *       user with a verified purchase. For an entry that means an ACTIVE
 *       entitlement on the entry or on a collection containing it; for a
 *       collection, an ACTIVE collection entitlement. Free targets accept
 *       authenticated {@code FREE_VIEW} votes.</li>
 *   <li><b>Immutable, segregated proof</b> — every vote stores its
 *       {@link RatingProofType}; the aggregate keeps verified-purchase totals
 *       separate, so a public&harr;paid flip cannot inflate the buyer score.</li>
 * </ol>
 *
 * <h3>Anti-spam</h3> Daily per-user creation cap (across all targets); edits don't count.
 * <h3>Anti-XSS</h3> Comments are stripped of markup/control chars and length-capped
 * before persistence; they are stored and returned as plain text only.
 */
@Service
public class RatingService {

    private static final Logger logger = LoggerFactory.getLogger(RatingService.class);

    private static final int MAX_COMMENT = 1000;

    /** Max new votes a user can create per rolling 24h (edits excluded). */
    private static final int DAILY_RATING_LIMIT = 20;

    /** Hard cap on page size for public review listing. */
    private static final int MAX_PAGE_SIZE = 50;

    private final RatingRepository ratingRepository;
    private final RatingAggregateRepository aggregateRepository;
    private final EntryRepository entryRepository;
    private final CollectionRepository collectionRepository;
    private final EntitlementRepository entitlementRepository;

    public RatingService(RatingRepository ratingRepository,
                         RatingAggregateRepository aggregateRepository,
                         EntryRepository entryRepository,
                         CollectionRepository collectionRepository,
                         EntitlementRepository entitlementRepository) {
        this.ratingRepository = ratingRepository;
        this.aggregateRepository = aggregateRepository;
        this.entryRepository = entryRepository;
        this.collectionRepository = collectionRepository;
        this.entitlementRepository = entitlementRepository;
    }

    // ── Commands ───────────────────────────────────────────────

    /**
     * Create or update the caller's vote for a target (entry or collection).
     *
     * @throws IllegalArgumentException invalid input / non-ratable target
     *         ({@code TARGET_NOT_FOUND}, {@code TARGET_NOT_RATABLE},
     *         {@code CANNOT_RATE_OWN_CONTENT})
     * @throws IllegalStateException    fraud / rate-limit
     *         ({@code PURCHASE_REQUIRED}, {@code DAILY_RATING_LIMIT_REACHED})
     */
    public Rating submitRating(String tenantId, String userId, String username,
                               TargetType targetType, String targetId,
                               boolean liked, String comment) {

        // 1. Target must exist and be in a ratable (published) state
        TargetInfo target = resolveTarget(tenantId, targetType, targetId);

        // 2. No self-rating
        if (userId.equals(target.creatorUserId())) {
            throw new IllegalArgumentException("CANNOT_RATE_OWN_CONTENT");
        }

        // 3. Fraud gate — resolve proof. Throws PURCHASE_REQUIRED for paid
        //    content the caller never bought.
        ProofResolution proof = resolveProof(tenantId, userId, targetType, targetId, target);

        String cleanComment = sanitizeComment(comment);
        LocalDateTime now = LocalDateTime.now();

        Rating existing = ratingRepository
                .findByTenantIdAndUserIdAndTargetTypeAndTargetId(tenantId, userId, targetType, targetId)
                .orElse(null);

        Rating rating;
        if (existing != null) {
            // Edit — never re-charges the rate limit, never downgrades proof.
            rating = existing;
            rating.setLiked(liked);
            rating.setComment(cleanComment);
            rating.setProofType(RatingProofType.strongest(existing.getProofType(), proof.type()));
            if (proof.type() == RatingProofType.PURCHASE) {
                rating.setProofRef(proof.ref());
            }
            rating.setUpdatedAt(now);
        } else {
            // New vote — enforce anti-spam daily cap.
            long recent = ratingRepository.countByTenantIdAndUserIdAndCreatedAtAfter(
                    tenantId, userId, now.minusDays(1));
            if (recent >= DAILY_RATING_LIMIT) {
                throw new IllegalStateException("DAILY_RATING_LIMIT_REACHED");
            }
            rating = new Rating();
            rating.setTenantId(tenantId);
            rating.setTargetType(targetType);
            rating.setTargetId(targetId);
            rating.setUserId(userId);
            rating.setUsername(username);
            rating.setCreatorUserId(target.creatorUserId());
            rating.setLiked(liked);
            rating.setComment(cleanComment);
            rating.setProofType(proof.type());
            rating.setProofRef(proof.ref());
            rating.setCreatedAt(now);
            rating.setUpdatedAt(now);
        }

        Rating saved = ratingRepository.save(rating);
        recomputeAggregate(tenantId, targetType, targetId);

        logger.info("Vote saved: target={}:{}, user={}, liked={}, proof={}, new={}",
                targetType, targetId, userId, liked, saved.getProofType(), existing == null);
        return saved;
    }

    /** Remove the caller's vote for a target (idempotent). */
    public void deleteRating(String tenantId, String userId, TargetType targetType, String targetId) {
        long deleted = ratingRepository.deleteByTenantIdAndUserIdAndTargetTypeAndTargetId(
                tenantId, userId, targetType, targetId);
        if (deleted > 0) {
            recomputeAggregate(tenantId, targetType, targetId);
            logger.info("Rating deleted: target={}:{}, user={}", targetType, targetId, userId);
        }
    }

    // ── Queries ────────────────────────────────────────────────

    public Rating getMyRating(String tenantId, String userId, TargetType targetType, String targetId) {
        return ratingRepository
                .findByTenantIdAndUserIdAndTargetTypeAndTargetId(tenantId, userId, targetType, targetId)
                .orElse(null);
    }

    public Page<Rating> listReviews(String tenantId, TargetType targetType, String targetId,
                                    int page, int size) {
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int safePage = Math.max(0, page);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ratingRepository.findByTenantIdAndTargetTypeAndTargetId(
                tenantId, targetType, targetId, pageable);
    }

    public RatingAggregateResponse getAggregateResponse(String tenantId, TargetType targetType, String targetId) {
        RatingAggregate agg = aggregateRepository
                .findByTenantIdAndTargetTypeAndTargetId(tenantId, targetType, targetId)
                .orElse(null);
        return toAggregateResponse(targetType.name(), targetId, agg);
    }

    public RatingResponse toRatingResponse(Rating r) {
        if (r == null) return null;
        boolean verified = r.getProofType() == RatingProofType.PURCHASE;
        return new RatingResponse(
                r.getId(),
                r.getUserId(),
                r.getUsername(),
                r.isLiked(),
                r.getComment(),
                r.getProofType() == null ? null : r.getProofType().name(),
                verified,
                r.getCreatedAt() == null ? null : r.getCreatedAt().toString(),
                r.getUpdatedAt() == null ? null : r.getUpdatedAt().toString()
        );
    }

    private RatingAggregateResponse toAggregateResponse(String targetType, String targetId, RatingAggregate agg) {
        if (agg == null || agg.getCount() == 0) {
            return new RatingAggregateResponse(targetType, targetId,
                    0, 0, 0, 0.0,
                    0, 0, 0, 0.0);
        }
        double likePercent = percent(agg.getLikes(), agg.getCount());
        double verifiedLikePercent = agg.getVerifiedCount() == 0
                ? 0.0 : percent(agg.getVerifiedLikes(), agg.getVerifiedCount());
        return new RatingAggregateResponse(
                targetType,
                targetId,
                agg.getCount(),
                agg.getLikes(),
                agg.getDislikes(),
                likePercent,
                agg.getVerifiedCount(),
                agg.getVerifiedLikes(),
                agg.getVerifiedDislikes(),
                verifiedLikePercent
        );
    }

    // ── Target resolution ──────────────────────────────────────

    /** Loads the target, asserts it is published/ratable, returns owner + paid flag. */
    private TargetInfo resolveTarget(String tenantId, TargetType targetType, String targetId) {
        if (targetType == TargetType.ENTRY) {
            Entry entry = entryRepository.findByTenantIdAndId(tenantId, targetId)
                    .orElseThrow(() -> new IllegalArgumentException("TARGET_NOT_FOUND"));
            if (entry.getStatus() != EntryStatus.PUBLISHED && entry.getStatus() != EntryStatus.APPROVED) {
                throw new IllegalArgumentException("TARGET_NOT_RATABLE");
            }
            return new TargetInfo(entry.getUserId(), entry.isPaid());
        }
        // COLLECTION
        Collection coll = collectionRepository.findByTenantIdAndId(tenantId, targetId)
                .orElseThrow(() -> new IllegalArgumentException("TARGET_NOT_FOUND"));
        if (coll.getStatus() != CollectionStatus.PUBLISHED) {
            throw new IllegalArgumentException("TARGET_NOT_RATABLE");
        }
        return new TargetInfo(coll.getUserId(), coll.isPaid());
    }

    private record TargetInfo(String creatorUserId, boolean paid) {}

    // ── Fraud gate ─────────────────────────────────────────────

    /**
     * Decide whether the caller may rate the target and with what proof.
     *
     * <ul>
     *   <li>Verified purchase &rarr; {@code PURCHASE}. Allowed even if the
     *       target is now free.</li>
     *   <li>Otherwise, if the target is currently free &rarr; {@code FREE_VIEW}.</li>
     *   <li>Otherwise (paid, never purchased) &rarr; {@code PURCHASE_REQUIRED}.</li>
     * </ul>
     */
    private ProofResolution resolveProof(String tenantId, String userId,
                                         TargetType targetType, String targetId, TargetInfo target) {
        if (targetType == TargetType.COLLECTION) {
            boolean entitled = entitlementRepository
                    .existsByTenantIdAndUserIdAndTargetTypeAndCollectionIdAndStatus(
                            tenantId, userId, TargetType.COLLECTION, targetId, EntitlementStatus.ACTIVE);
            if (entitled) {
                return new ProofResolution(RatingProofType.PURCHASE, "COLLECTION:" + targetId);
            }
            if (!target.paid()) {
                return new ProofResolution(RatingProofType.FREE_VIEW, null);
            }
            throw new IllegalStateException("PURCHASE_REQUIRED");
        }

        // ENTRY — entry-level entitlement first.
        boolean entryEntitled = entitlementRepository
                .existsByTenantIdAndUserIdAndEntryIdAndStatus(
                        tenantId, userId, targetId, EntitlementStatus.ACTIVE);
        if (entryEntitled) {
            return new ProofResolution(RatingProofType.PURCHASE, "ENTRY:" + targetId);
        }

        // Collection-level entitlement (entry bundled in a purchased collection).
        List<Collection> collections = collectionRepository
                .findByTenantIdAndStatusAndItemsEntryId(tenantId, CollectionStatus.PUBLISHED, targetId);
        for (Collection c : collections) {
            boolean collectionEntitled = entitlementRepository
                    .existsByTenantIdAndUserIdAndTargetTypeAndCollectionIdAndStatus(
                            tenantId, userId, TargetType.COLLECTION, c.getId(), EntitlementStatus.ACTIVE);
            if (collectionEntitled) {
                return new ProofResolution(RatingProofType.PURCHASE, "COLLECTION:" + c.getId());
            }
        }

        if (!target.paid()) {
            return new ProofResolution(RatingProofType.FREE_VIEW, null);
        }
        throw new IllegalStateException("PURCHASE_REQUIRED");
    }

    private record ProofResolution(RatingProofType type, String ref) {}

    // ── Aggregate recomputation (derived from source — never drifts) ──

    private void recomputeAggregate(String tenantId, TargetType targetType, String targetId) {
        long likes = ratingRepository.countByTenantIdAndTargetTypeAndTargetIdAndLiked(
                tenantId, targetType, targetId, true);
        long dislikes = ratingRepository.countByTenantIdAndTargetTypeAndTargetIdAndLiked(
                tenantId, targetType, targetId, false);
        long verifiedLikes = ratingRepository.countByTenantIdAndTargetTypeAndTargetIdAndProofTypeAndLiked(
                tenantId, targetType, targetId, RatingProofType.PURCHASE, true);
        long verifiedDislikes = ratingRepository.countByTenantIdAndTargetTypeAndTargetIdAndProofTypeAndLiked(
                tenantId, targetType, targetId, RatingProofType.PURCHASE, false);

        RatingAggregate agg = aggregateRepository
                .findByTenantIdAndTargetTypeAndTargetId(tenantId, targetType, targetId)
                .orElseGet(() -> {
                    RatingAggregate a = new RatingAggregate();
                    a.setTenantId(tenantId);
                    a.setTargetType(targetType);
                    a.setTargetId(targetId);
                    return a;
                });

        agg.setCount(likes + dislikes);
        agg.setLikes(likes);
        agg.setDislikes(dislikes);
        agg.setVerifiedCount(verifiedLikes + verifiedDislikes);
        agg.setVerifiedLikes(verifiedLikes);
        agg.setVerifiedDislikes(verifiedDislikes);
        agg.setUpdatedAt(LocalDateTime.now());

        aggregateRepository.save(agg);
    }

    /** Percentage of {@code part} out of {@code total}, rounded to 2 decimals (0 when total is 0). */
    private double percent(long part, long total) {
        if (total == 0) return 0.0;
        return Math.round((double) part / total * 10000.0) / 100.0;
    }

    // ── Anti-XSS sanitization ──────────────────────────────────

    /**
     * Reduce a free-text comment to safe plain text: trim, drop control chars
     * (except newline), strip angle brackets so no markup/script can survive
     * even if a downstream consumer renders it unescaped, and length-cap.
     * Returns {@code null} for blank input.
     */
    private String sanitizeComment(String raw) {
        if (raw == null) return null;
        String s = raw.strip();
        if (s.isEmpty()) return null;
        // Remove control chars except newline.
        s = s.replaceAll("[\\p{Cntrl}&&[^\\n]]", "");
        // Neutralize any HTML/script markup.
        s = s.replaceAll("[<>]", "");
        // Collapse runs of 3+ newlines to 2.
        s = s.replaceAll("\\n{3,}", "\n\n");
        s = s.strip();
        if (s.isEmpty()) return null;
        if (s.length() > MAX_COMMENT) {
            s = s.substring(0, MAX_COMMENT);
        }
        return s;
    }
}
