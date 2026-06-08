package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.response.RatingAggregateResponse;
import org.earnlumens.mediastore.domain.media.dto.response.RatingResponse;
import org.earnlumens.mediastore.domain.media.model.*;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.RatingAggregateEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.RatingEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.RatingAggregateMongoRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.RatingMongoRepository;
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
 * User content ratings (1&ndash;5 stars + optional review) for a <em>target</em>
 * &mdash; an entry or a whole collection (e.g. a music album) &mdash; built
 * around one goal: <b>kill fraud</b>.
 *
 * <h3>Anti-fraud guarantees</h3>
 * <ol>
 *   <li><b>One rating per user per target</b> — enforced by a unique index plus
 *       upsert-on-edit. A single account can never stuff the ballot.</li>
 *   <li><b>Proof-gated eligibility</b> — a paid target can only be rated by a
 *       user with a verified purchase. For an entry that means an ACTIVE
 *       entitlement on the entry or on a collection containing it; for a
 *       collection, an ACTIVE collection entitlement. Free targets accept
 *       authenticated {@code FREE_VIEW} ratings.</li>
 *   <li><b>Immutable, segregated proof</b> — every rating stores its
 *       {@link RatingProofType}; the aggregate keeps verified-purchase totals
 *       separate, so a public&harr;paid flip cannot inflate the buyer score.</li>
 *   <li><b>Bayesian ranking score</b> — low-N targets are pulled toward a
 *       neutral prior so {@code 5.0 (1)} can't outrank {@code 4.6 (300)}.</li>
 * </ol>
 *
 * <h3>Anti-spam</h3> Daily per-user creation cap (across all targets); edits don't count.
 * <h3>Anti-XSS</h3> Comments are stripped of markup/control chars and length-capped
 * before persistence; they are stored and returned as plain text only.
 */
@Service
public class RatingService {

    private static final Logger logger = LoggerFactory.getLogger(RatingService.class);

    private static final int MIN_STARS = 1;
    private static final int MAX_STARS = 5;
    private static final int MAX_COMMENT = 1000;

    /** Max new ratings a user can create per rolling 24h (edits excluded). */
    private static final int DAILY_RATING_LIMIT = 20;

    /** Bayesian prior: neutral mean and its weight (virtual votes). */
    private static final double BAYES_PRIOR_MEAN = 3.5;
    private static final double BAYES_PRIOR_WEIGHT = 8.0;

    /** Hard cap on page size for public review listing. */
    private static final int MAX_PAGE_SIZE = 50;

    private final RatingMongoRepository ratingRepository;
    private final RatingAggregateMongoRepository aggregateRepository;
    private final EntryRepository entryRepository;
    private final CollectionRepository collectionRepository;
    private final EntitlementRepository entitlementRepository;

    public RatingService(RatingMongoRepository ratingRepository,
                         RatingAggregateMongoRepository aggregateRepository,
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
     * Create or update the caller's rating for a target (entry or collection).
     *
     * @throws IllegalArgumentException invalid input / non-ratable target
     *         ({@code INVALID_STARS}, {@code TARGET_NOT_FOUND},
     *         {@code TARGET_NOT_RATABLE}, {@code CANNOT_RATE_OWN_CONTENT})
     * @throws IllegalStateException    fraud / rate-limit
     *         ({@code PURCHASE_REQUIRED}, {@code DAILY_RATING_LIMIT_REACHED})
     */
    public RatingEntity submitRating(String tenantId, String userId, String username,
                                     TargetType targetType, String targetId,
                                     int stars, String comment) {

        // 1. Validate score
        if (stars < MIN_STARS || stars > MAX_STARS) {
            throw new IllegalArgumentException("INVALID_STARS");
        }

        // 2. Target must exist and be in a ratable (published) state
        TargetInfo target = resolveTarget(tenantId, targetType, targetId);

        // 3. No self-rating
        if (userId.equals(target.creatorUserId())) {
            throw new IllegalArgumentException("CANNOT_RATE_OWN_CONTENT");
        }

        // 4. Fraud gate — resolve proof. Throws PURCHASE_REQUIRED for paid
        //    content the caller never bought.
        ProofResolution proof = resolveProof(tenantId, userId, targetType, targetId, target);

        String typeName = targetType.name();
        String cleanComment = sanitizeComment(comment);
        LocalDateTime now = LocalDateTime.now();

        RatingEntity existing = ratingRepository
                .findByTenantIdAndUserIdAndTargetTypeAndTargetId(tenantId, userId, typeName, targetId)
                .orElse(null);

        RatingEntity rating;
        if (existing != null) {
            // Edit — never re-charges the rate limit, never downgrades proof.
            rating = existing;
            rating.setStars(stars);
            rating.setComment(cleanComment);
            rating.setProofType(RatingProofType
                    .strongest(RatingProofType.valueOf(existing.getProofType()), proof.type()).name());
            if (proof.type() == RatingProofType.PURCHASE) {
                rating.setProofRef(proof.ref());
            }
            rating.setUpdatedAt(now);
        } else {
            // New rating — enforce anti-spam daily cap.
            long recent = ratingRepository.countByTenantIdAndUserIdAndCreatedAtAfter(
                    tenantId, userId, now.minusDays(1));
            if (recent >= DAILY_RATING_LIMIT) {
                throw new IllegalStateException("DAILY_RATING_LIMIT_REACHED");
            }
            rating = new RatingEntity();
            rating.setTenantId(tenantId);
            rating.setTargetType(typeName);
            rating.setTargetId(targetId);
            rating.setUserId(userId);
            rating.setUsername(username);
            rating.setCreatorUserId(target.creatorUserId());
            rating.setStars(stars);
            rating.setComment(cleanComment);
            rating.setProofType(proof.type().name());
            rating.setProofRef(proof.ref());
            rating.setCreatedAt(now);
            rating.setUpdatedAt(now);
        }

        RatingEntity saved = ratingRepository.save(rating);
        recomputeAggregate(tenantId, typeName, targetId);

        logger.info("Rating saved: target={}:{}, user={}, stars={}, proof={}, new={}",
                typeName, targetId, userId, stars, saved.getProofType(), existing == null);
        return saved;
    }

    /** Remove the caller's rating for a target (idempotent). */
    public void deleteRating(String tenantId, String userId, TargetType targetType, String targetId) {
        String typeName = targetType.name();
        long deleted = ratingRepository.deleteByTenantIdAndUserIdAndTargetTypeAndTargetId(
                tenantId, userId, typeName, targetId);
        if (deleted > 0) {
            recomputeAggregate(tenantId, typeName, targetId);
            logger.info("Rating deleted: target={}:{}, user={}", typeName, targetId, userId);
        }
    }

    // ── Queries ────────────────────────────────────────────────

    public RatingEntity getMyRating(String tenantId, String userId, TargetType targetType, String targetId) {
        return ratingRepository
                .findByTenantIdAndUserIdAndTargetTypeAndTargetId(tenantId, userId, targetType.name(), targetId)
                .orElse(null);
    }

    public Page<RatingEntity> listReviews(String tenantId, TargetType targetType, String targetId,
                                          int page, int size) {
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int safePage = Math.max(0, page);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ratingRepository.findByTenantIdAndTargetTypeAndTargetId(
                tenantId, targetType.name(), targetId, pageable);
    }

    public RatingAggregateResponse getAggregateResponse(String tenantId, TargetType targetType, String targetId) {
        RatingAggregateEntity agg = aggregateRepository
                .findByTenantIdAndTargetTypeAndTargetId(tenantId, targetType.name(), targetId)
                .orElse(null);
        return toAggregateResponse(targetType.name(), targetId, agg);
    }

    public RatingResponse toRatingResponse(RatingEntity r) {
        if (r == null) return null;
        boolean verified = RatingProofType.PURCHASE.name().equals(r.getProofType());
        return new RatingResponse(
                r.getId(),
                r.getUserId(),
                r.getUsername(),
                r.getStars(),
                r.getComment(),
                r.getProofType(),
                verified,
                r.getCreatedAt() == null ? null : r.getCreatedAt().toString(),
                r.getUpdatedAt() == null ? null : r.getUpdatedAt().toString()
        );
    }

    private RatingAggregateResponse toAggregateResponse(String targetType, String targetId, RatingAggregateEntity agg) {
        if (agg == null || agg.getCount() == 0) {
            return new RatingAggregateResponse(targetType, targetId, 0, 0.0, 0, 0.0, 0.0,
                    List.of(0L, 0L, 0L, 0L, 0L));
        }
        double average = round2((double) agg.getSum() / agg.getCount());
        double verifiedAverage = agg.getVerifiedCount() == 0
                ? 0.0 : round2((double) agg.getVerifiedSum() / agg.getVerifiedCount());
        return new RatingAggregateResponse(
                targetType,
                targetId,
                agg.getCount(),
                average,
                agg.getVerifiedCount(),
                verifiedAverage,
                round2(agg.getBayesianScore()),
                List.of(agg.getStar1(), agg.getStar2(), agg.getStar3(), agg.getStar4(), agg.getStar5())
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

    private void recomputeAggregate(String tenantId, String targetType, String targetId) {
        long[] hist = new long[6];   // hist[1..5]
        long count = 0, sum = 0, verifiedCount = 0, verifiedSum = 0;

        for (int s = MIN_STARS; s <= MAX_STARS; s++) {
            long cs = ratingRepository.countByTenantIdAndTargetTypeAndTargetIdAndStars(
                    tenantId, targetType, targetId, s);
            hist[s] = cs;
            count += cs;
            sum += (long) s * cs;

            long vcs = ratingRepository.countByTenantIdAndTargetTypeAndTargetIdAndProofTypeAndStars(
                    tenantId, targetType, targetId, RatingProofType.PURCHASE.name(), s);
            verifiedCount += vcs;
            verifiedSum += (long) s * vcs;
        }

        RatingAggregateEntity agg = aggregateRepository
                .findByTenantIdAndTargetTypeAndTargetId(tenantId, targetType, targetId)
                .orElseGet(() -> {
                    RatingAggregateEntity a = new RatingAggregateEntity();
                    a.setTenantId(tenantId);
                    a.setTargetType(targetType);
                    a.setTargetId(targetId);
                    return a;
                });

        agg.setCount(count);
        agg.setSum(sum);
        agg.setStar1(hist[1]);
        agg.setStar2(hist[2]);
        agg.setStar3(hist[3]);
        agg.setStar4(hist[4]);
        agg.setStar5(hist[5]);
        agg.setVerifiedCount(verifiedCount);
        agg.setVerifiedSum(verifiedSum);
        agg.setBayesianScore(bayesian(count, sum));
        agg.setUpdatedAt(LocalDateTime.now());

        aggregateRepository.save(agg);
    }

    private double bayesian(long count, long sum) {
        if (count == 0) return 0.0;
        return (BAYES_PRIOR_WEIGHT * BAYES_PRIOR_MEAN + sum) / (BAYES_PRIOR_WEIGHT + count);
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

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
