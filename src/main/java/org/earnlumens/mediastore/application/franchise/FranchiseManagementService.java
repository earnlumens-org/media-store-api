package org.earnlumens.mediastore.application.franchise;

import org.earnlumens.mediastore.application.payment.StellarTransactionService;
import org.earnlumens.mediastore.infrastructure.franchise.read.FranchiseBanReadRepository;
import org.earnlumens.mediastore.infrastructure.franchise.write.FranchiseWriteModel;
import org.earnlumens.mediastore.infrastructure.franchise.write.FranchiseWriteRepository;
import org.earnlumens.mediastore.infrastructure.r2.R2PresignedUrlService;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantReadModel;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantReadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Franchisee self-service: a logged-in user creates and manages their own
 * franchise ("beta") under the franchisor tenant whose storefront they are on.
 *
 * <p><b>Why this lives in media-store-api.</b> The franchise owner is an
 * ordinary storefront user, not a tenant admin, so they act through the public
 * app. Rather than have the public app call admin-api cross-service, the same
 * {@code franchises} collection is written here directly. Franchisor governance
 * (enable model, set default commission, disable a franchise, ban a user) stays
 * the exclusive concern of admin-api; this service only ever performs actions a
 * franchise <i>owner</i> is allowed to perform.
 *
 * <p><b>Security model (exhaustive).</b>
 * <ul>
 *   <li><b>Authentication</b> — every entry point requires a logged-in caller;
 *       the controller rejects anonymous requests before reaching this service.</li>
 *   <li><b>Tenant isolation</b> — the tenant is taken from the request-scoped
 *       {@code TenantContext} (host/subdomain), never from the client body, and
 *       every repository query is scoped by that {@code tenantId}. A caller can
 *       therefore only ever touch franchises under the storefront they are on.</li>
 *   <li><b>Ownership</b> — reads and edits are additionally scoped by
 *       {@code ownerOauthUserId}, so a user can never see or mutate a franchise
 *       they do not own, even by guessing an id.</li>
 *   <li><b>Eligibility</b> — creation re-checks, against the DB on every call,
 *       that the tenant is ACTIVE, has franchises enabled and not paused, and
 *       that the caller is not banned. Edits also re-check the ban so a user
 *       banned after creating loses the ability to operate the franchise.</li>
 *   <li><b>Immutability</b> — slug, commission, payout wallet, owner and status
 *       are fixed at creation; the owner can only edit branding afterwards.</li>
 * </ul>
 */
@Service
public class FranchiseManagementService {

    private static final Logger logger = LoggerFactory.getLogger(FranchiseManagementService.class);

    private static final String ACTIVE = "ACTIVE";

    /** RFC-1123-safe slug, mirrors admin-api (min length 3). */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{1,28}[a-z0-9])$");

    /** Slugs reserved to avoid clashing with reserved storefront paths. */
    private static final Set<String> RESERVED_SLUGS = Set.of(
        "new", "me", "admin", "api", "settings", "f");

    /** Stellar public key syntactic check (G + 55 base32 chars). */
    private static final Pattern STELLAR_KEY = Pattern.compile("^G[A-Z2-7]{55}$");

    /** Accent colour: #RRGGBB or #RRGGBBAA. */
    private static final Pattern HEX_COLOR = Pattern.compile("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$");

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    /** Branding image slots a franchise owner may upload. */
    private static final String SLOT_LOGO = "logo";
    private static final String SLOT_COVER = "cover";
    private static final Set<String> IMAGE_SLOTS = Set.of(SLOT_LOGO, SLOT_COVER);

    /** Allowed raster image content-types (kept narrow on purpose). */
    private static final Set<String> IMAGE_TYPES = Set.of(
        "image/png", "image/jpeg", "image/webp");

    /** Per-slot upload size ceilings. */
    private static final long LOGO_MAX_BYTES = 2L * 1024 * 1024;   // 2 MB
    private static final long COVER_MAX_BYTES = 6L * 1024 * 1024;  // 6 MB

    /** Public R2 namespace for franchise branding (served by the CDN worker). */
    private static final String IMAGE_KEY_ROOT = "public/franchises/";

    private final FranchiseWriteRepository repository;
    private final FranchiseBanReadRepository banRepository;
    private final TenantReadRepository tenantRepository;
    private final R2PresignedUrlService presignedUrlService;
    private final StellarTransactionService stellarTransactionService;

    public FranchiseManagementService(FranchiseWriteRepository repository,
                                      FranchiseBanReadRepository banRepository,
                                      TenantReadRepository tenantRepository,
                                      R2PresignedUrlService presignedUrlService,
                                      StellarTransactionService stellarTransactionService) {
        this.repository = repository;
        this.banRepository = banRepository;
        this.tenantRepository = tenantRepository;
        this.presignedUrlService = presignedUrlService;
        this.stellarTransactionService = stellarTransactionService;
    }

    // ============================== reads ==============================

    /** Franchises owned by the caller under the current tenant. */
    public List<FranchiseWriteModel> listMine(String tenantId, String callerOauthUserId) {
        requireCaller(callerOauthUserId);
        return repository.findByTenantIdAndOwnerOauthUserId(tenantId, callerOauthUserId);
    }

    /** Whether (and on what terms) the current tenant accepts franchise sign-ups. */
    public FranchiseConfigView getConfig(String tenantId, String callerOauthUserId) {
        requireCaller(callerOauthUserId);
        TenantReadModel t = tenantRepository.findBySubdomain(tenantId)
            .orElseThrow(() -> new FranchiseException(FranchiseErrorCode.NOT_FOUND, 404));

        boolean enabled = t.isFranchisesEnabled();
        boolean paused = t.isFranchisesPaused();
        boolean banned = banRepository.existsByTenantIdAndUserId(tenantId, callerOauthUserId);
        boolean available = t.isActive() && enabled && !paused && !banned;

        BigDecimal commission = t.getDefaultFranchiseCommissionPercent();
        commission = (commission == null ? ZERO : commission.setScale(2, RoundingMode.HALF_UP));

        return new FranchiseConfigView(enabled, paused, banned, commission, available);
    }

    // ========================= franchisee flow =========================

    /**
     * Creates a franchise under the current tenant for the caller. No prior
     * approval; the commission is snapshotted from the franchisor's current
     * default and frozen onto the franchise.
     */
    public FranchiseWriteModel createFranchise(String tenantId, String callerOauthUserId,
                                               String callerUsername, String callerDisplayName,
                                               String slugRaw, String payoutWalletRaw,
                                               String titleRaw, String descriptionRaw,
                                               String accentColorRaw) {
        requireCaller(callerOauthUserId);

        TenantReadModel t = tenantRepository.findBySubdomain(tenantId)
            .orElseThrow(() -> new FranchiseException(FranchiseErrorCode.NOT_FOUND, 404));

        if (!t.isActive()) {
            throw new FranchiseException(FranchiseErrorCode.TENANT_BLOCKED, 403);
        }
        if (!t.isFranchisesEnabled()) {
            throw new FranchiseException(FranchiseErrorCode.FRANCHISES_NOT_ENABLED, 403);
        }
        if (t.isFranchisesPaused()) {
            throw new FranchiseException(FranchiseErrorCode.FRANCHISES_PAUSED, 403);
        }
        if (banRepository.existsByTenantIdAndUserId(tenantId, callerOauthUserId)) {
            throw new FranchiseException(FranchiseErrorCode.USER_BANNED, 403);
        }

        // Slug.
        String slug = slugRaw == null ? "" : slugRaw.trim().toLowerCase();
        if (slug.isEmpty()) {
            throw new FranchiseException(FranchiseErrorCode.SLUG_REQUIRED, 400);
        }
        if (!SLUG.matcher(slug).matches() || RESERVED_SLUGS.contains(slug)) {
            throw new FranchiseException(FranchiseErrorCode.SLUG_FORMAT, 400);
        }
        if (repository.existsByTenantIdAndSlug(tenantId, slug)) {
            throw new FranchiseException(FranchiseErrorCode.SLUG_TAKEN, 409);
        }

        // Payout wallet.
        String wallet = payoutWalletRaw == null ? "" : payoutWalletRaw.trim();
        if (!STELLAR_KEY.matcher(wallet).matches()) {
            throw new FranchiseException(FranchiseErrorCode.WALLET_FORMAT, 400);
        }
        // The wallet is immutable after creation and becomes a payment-split
        // destination, so it must already exist on the Stellar network —
        // otherwise every sale through this franchise would later fail with
        // op_no_destination.
        if (!stellarTransactionService.isAccountActive(wallet)) {
            throw new FranchiseException(FranchiseErrorCode.WALLET_NOT_ACTIVATED, 400);
        }

        // Accent colour (optional).
        String accent = emptyToNull(safeTrim(accentColorRaw, 9));
        if (accent != null && !HEX_COLOR.matcher(accent).matches()) {
            throw new FranchiseException(FranchiseErrorCode.ACCENT_COLOR_FORMAT, 400);
        }

        // Commission snapshot — frozen for the life of the franchise.
        BigDecimal commission = t.getDefaultFranchiseCommissionPercent();
        if (commission == null) commission = ZERO;
        commission = commission.setScale(2, RoundingMode.HALF_UP);

        FranchiseWriteModel f = new FranchiseWriteModel();
        f.setTenantId(tenantId);
        f.setSlug(slug);
        f.setOwnerOauthUserId(callerOauthUserId);
        f.setOwnerUsername(callerUsername);
        f.setOwnerDisplayName(callerDisplayName);
        f.setCommissionPercent(commission);
        f.setPayoutWallet(wallet);
        f.setTitle(emptyToNull(safeTrim(titleRaw, 80)));
        f.setDescription(emptyToNull(safeTrim(descriptionRaw, 280)));
        f.setAccentColor(accent);
        f.setStatus(ACTIVE);
        f.setAcceptedTermsAt(Instant.now());

        try {
            FranchiseWriteModel saved = repository.save(f);
            logger.info("Franchise created (self-service): id={} tenant={} slug={} owner={} commission={}",
                saved.getId(), saved.getTenantId(), saved.getSlug(), callerUsername, commission);
            return saved;
        } catch (DuplicateKeyException dke) {
            throw new FranchiseException(FranchiseErrorCode.SLUG_TAKEN, 409);
        }
    }

    /** Franchise owner edits their own in-app branding. */
    public FranchiseWriteModel updateOwnFranchise(String tenantId, String callerOauthUserId,
                                                  String franchiseId, FranchiseBrandingUpdate req) {
        requireCaller(callerOauthUserId);

        // Tenant + ownership scoped: a caller can only ever load a franchise
        // they own under the current tenant.
        FranchiseWriteModel f = repository
            .findByTenantIdAndIdAndOwnerOauthUserId(tenantId, franchiseId, callerOauthUserId)
            .orElseThrow(() -> new FranchiseException(FranchiseErrorCode.NOT_FOUND, 404));

        // A user banned after creating may no longer operate the franchise.
        if (banRepository.existsByTenantIdAndUserId(tenantId, callerOauthUserId)) {
            throw new FranchiseException(FranchiseErrorCode.USER_BANNED, 403);
        }

        if (req.title() != null) f.setTitle(emptyToNull(safeTrim(req.title(), 80)));
        if (req.description() != null) f.setDescription(emptyToNull(safeTrim(req.description(), 280)));
        if (req.logoR2Key() != null) {
            f.setLogoR2Key(validatedImageKey(req.logoR2Key(), tenantId, f.getId(), SLOT_LOGO));
        }
        if (req.coverR2Key() != null) {
            f.setCoverR2Key(validatedImageKey(req.coverR2Key(), tenantId, f.getId(), SLOT_COVER));
        }
        if (req.accentColor() != null) {
            String accent = emptyToNull(safeTrim(req.accentColor(), 9));
            if (accent != null && !HEX_COLOR.matcher(accent).matches()) {
                throw new FranchiseException(FranchiseErrorCode.ACCENT_COLOR_FORMAT, 400);
            }
            f.setAccentColor(accent);
        }
        return repository.save(f);
    }

    /**
     * Mints a presigned PUT URL the owner uses to upload a branding image
     * (logo or cover) straight to R2. The presign locks the content-type and a
     * deterministic object key inside this franchise's public namespace; the
     * owner then persists the returned key via {@link #updateOwnFranchise},
     * which re-validates the namespace so a key can never be smuggled in.
     */
    public FranchiseImagePresign presignBrandingImage(String tenantId, String callerOauthUserId,
                                                      String franchiseId, String slotRaw,
                                                      String contentTypeRaw, long sizeBytes) {
        requireCaller(callerOauthUserId);

        // Tenant + ownership scoped: only the owner of a franchise under the
        // current tenant can presign an upload for it.
        FranchiseWriteModel f = repository
            .findByTenantIdAndIdAndOwnerOauthUserId(tenantId, franchiseId, callerOauthUserId)
            .orElseThrow(() -> new FranchiseException(FranchiseErrorCode.NOT_FOUND, 404));

        // A user banned after creating may no longer operate the franchise.
        if (banRepository.existsByTenantIdAndUserId(tenantId, callerOauthUserId)) {
            throw new FranchiseException(FranchiseErrorCode.USER_BANNED, 403);
        }

        String slot = slotRaw == null ? "" : slotRaw.trim().toLowerCase();
        if (!IMAGE_SLOTS.contains(slot)) {
            throw new FranchiseException(FranchiseErrorCode.IMAGE_SLOT, 400);
        }

        String type = contentTypeRaw == null ? "" : contentTypeRaw.trim().toLowerCase();
        if (!IMAGE_TYPES.contains(type)) {
            throw new FranchiseException(FranchiseErrorCode.IMAGE_TYPE, 400);
        }

        long maxBytes = SLOT_LOGO.equals(slot) ? LOGO_MAX_BYTES : COVER_MAX_BYTES;
        if (sizeBytes <= 0 || sizeBytes > maxBytes) {
            throw new FranchiseException(FranchiseErrorCode.IMAGE_SIZE, 400);
        }

        String ext = switch (type) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            default -> "png";
        };
        // UUID per upload => each key is immutable, so the CDN can cache hard
        // and old objects are simply orphaned on replace.
        String r2Key = imageKeyPrefix(tenantId, f.getId(), slot) + UUID.randomUUID() + "." + ext;

        String url = presignedUrlService.generatePresignedPutUrl(r2Key, type);
        logger.info("Franchise branding upload presigned: tenant={} franchise={} slot={} key={}",
            tenantId, f.getId(), slot, r2Key);
        return new FranchiseImagePresign(url, r2Key);
    }

    // ============================= helpers =============================

    private static void requireCaller(String callerOauthUserId) {
        if (callerOauthUserId == null || callerOauthUserId.isBlank()) {
            throw new FranchiseException(FranchiseErrorCode.FORBIDDEN, 401);
        }
    }

    /** Public R2 key prefix that owns this franchise's branding images. */
    private static String imageKeyPrefix(String tenantId, String franchiseId, String slot) {
        return IMAGE_KEY_ROOT + tenantId + "/" + franchiseId + "/" + slot + "/";
    }

    /**
     * Re-validates a branding image key on persist: an empty value clears the
     * image, otherwise the key MUST live under this exact franchise/slot
     * namespace so an owner can never point the franchise at an arbitrary or
     * someone else's object.
     */
    private static String validatedImageKey(String keyRaw, String tenantId,
                                            String franchiseId, String slot) {
        String key = emptyToNull(safeTrim(keyRaw, 256));
        if (key == null) return null;
        if (!key.startsWith(imageKeyPrefix(tenantId, franchiseId, slot))) {
            throw new FranchiseException(FranchiseErrorCode.IMAGE_KEY, 400);
        }
        return key;
    }

    private static String safeTrim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
