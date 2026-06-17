package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.request.CreateEntryRequest;
import org.earnlumens.mediastore.domain.media.dto.request.FinalizeUploadRequest;
import org.earnlumens.mediastore.domain.media.dto.request.InitUploadRequest;
import org.earnlumens.mediastore.domain.media.dto.request.UpdateEntryMetadataRequest;
import org.earnlumens.mediastore.domain.media.dto.request.UpdateEntryStatusRequest;
import org.earnlumens.mediastore.domain.media.dto.response.CreateEntryResponse;
import org.earnlumens.mediastore.domain.media.dto.response.FinalizeUploadResponse;
import org.earnlumens.mediastore.domain.media.dto.response.InitUploadResponse;
import org.earnlumens.mediastore.domain.media.dto.response.OwnerEntryPageResponse;
import org.earnlumens.mediastore.domain.media.dto.response.OwnerEntryResponse;
import org.earnlumens.mediastore.domain.media.dto.response.StudioItemResponse;
import org.earnlumens.mediastore.domain.media.dto.response.StudioPageResponse;
import org.earnlumens.mediastore.domain.media.model.Asset;
import org.earnlumens.mediastore.domain.media.model.AssetStatus;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.MediaKind;
import org.earnlumens.mediastore.domain.media.model.MediaVisibility;
import org.earnlumens.mediastore.domain.media.model.OrderStatus;
import org.earnlumens.mediastore.domain.media.model.PaymentSplit;
import org.earnlumens.mediastore.domain.media.model.PriceCurrency;
import org.earnlumens.mediastore.domain.media.model.SplitRole;
import org.earnlumens.mediastore.domain.media.model.ModerationJob;
import org.earnlumens.mediastore.domain.media.model.ModerationJobStatus;
import org.earnlumens.mediastore.domain.media.model.TranscodingJobStatus;
import org.earnlumens.mediastore.domain.media.model.UploadSession;
import org.earnlumens.mediastore.domain.media.repository.AssetRepository;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.media.repository.OrderRepository;
import org.earnlumens.mediastore.domain.media.repository.UploadSessionRepository;
import org.earnlumens.mediastore.domain.user.repository.UserRepository;
import org.earnlumens.mediastore.application.user.UserBadgeService;
import org.earnlumens.mediastore.application.space.SpaceValidationService;
import org.earnlumens.mediastore.infrastructure.config.PlatformConfig;
import org.earnlumens.mediastore.infrastructure.r2.R2PresignedUrlService;
import org.earnlumens.mediastore.infrastructure.r2.R2StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Application service orchestrating the creator upload flow:
 * <ol>
 *   <li>Create a DRAFT entry</li>
 *   <li>Generate a presigned PUT URL for R2</li>
 *   <li>Persist the uploaded asset record</li>
 *   <li>Transition the entry to IN_REVIEW</li>
 * </ol>
 */
@Service
public class EntryUploadService {

    private static final Logger logger = LoggerFactory.getLogger(EntryUploadService.class);
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100.00");
    private static final Pattern STELLAR_PUBLIC_KEY = Pattern.compile("^G[A-Z2-7]{55}$");

    /**
     * File extensions that are never accepted, regardless of the declared
     * content type. The original file name is reflected in the download
     * {@code Content-Disposition}, so a spoofed content type with a
     * dangerous extension must be blocked at the boundary. This is the
     * first layer of defense; the moderation worker additionally runs a
     * ClamAV scan on the uploaded bytes (magic-byte based).
     */
    private static final java.util.Set<String> BLOCKED_EXTENSIONS = java.util.Set.of(
            "exe", "msi", "bat", "cmd", "com", "scr", "pif", "cpl", "dll", "sys", "drv",
            "sh", "bash", "zsh", "ps1", "psm1", "vbs", "vbe", "js", "jse", "wsf", "wsh",
            "jar", "apk", "app", "deb", "rpm", "dmg", "pkg", "bin", "run", "elf",
            "hta", "reg", "lnk", "gadget", "scf", "inf"
    );

    /**
     * Content-type prefixes that are never accepted for any upload kind.
     */
    private static final java.util.Set<String> BLOCKED_CONTENT_TYPES = java.util.Set.of(
            "application/x-msdownload", "application/x-msdos-program", "application/x-dosexec",
            "application/x-executable", "application/x-sharedlib", "application/x-mach-binary",
            "application/x-elf", "application/vnd.microsoft.portable-executable",
            "application/x-sh", "application/x-shellscript", "application/x-bat",
            "application/java-archive", "application/vnd.android.package-archive",
            "application/x-msi", "application/x-apple-diskimage"
    );

    /**
     * Document / ebook / archive content types accepted for RESOURCE
     * attachments (in addition to {@code image/*}, {@code video/*},
     * {@code audio/*} and {@code text/*}). Archives are allowed here but the
     * moderation worker routes them to manual review.
     */
    private static final java.util.Set<String> RESOURCE_DOCUMENT_CONTENT_TYPES = java.util.Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation",
            "application/rtf",
            "application/epub+zip",
            "application/json",
            "application/xml",
            "application/zip",
            "application/x-zip-compressed",
            "application/x-tar",
            "application/gzip",
            "application/x-7z-compressed",
            "application/vnd.rar"
    );

    // ── Upload size limits & multipart parameters ──────────────────────
    private static final long MB = 1024L * 1024L;
    private static final long GB = 1024L * MB;

    /** Files at or above this size are uploaded via S3 multipart (resumable per-part). */
    public static final long MULTIPART_THRESHOLD_BYTES = 64L * MB;

    /** Uniform part size (R2 requires all parts equal except the last). */
    public static final long PART_SIZE_BYTES = 16L * MB;

    public static final long MAX_THUMBNAIL_BYTES = 10L * MB;
    public static final long MAX_PREVIEW_BYTES = 512L * MB;
    public static final Map<EntryType, Long> MAX_FULL_BYTES = Map.of(
            EntryType.VIDEO, 5L * GB,
            EntryType.AUDIO, 500L * MB,
            EntryType.IMAGE, 50L * MB,
            EntryType.RESOURCE, 2L * GB
    );

    /**
     * Legacy r2Key shape for finalize requests whose upload session predates
     * session persistence (in-flight uploads during rollout). Matches the
     * exact format produced by {@link #initUpload}.
     */
    private static final Pattern LEGACY_R2_KEY = Pattern.compile(
            "^(public|private)/media/([^/]+)/(full|thumbnail|preview)/[0-9a-fA-F-]{36}-[^/]+$");

    private final EntryRepository entryRepository;
    private final AssetRepository assetRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final CollectionRepository collectionRepository;
    private final R2PresignedUrlService r2PresignedUrlService;
    private final R2StorageService r2StorageService;
    private final UploadSessionRepository uploadSessionRepository;
    private final PlatformConfig platformConfig;
    private final TranscodingJobService transcodingJobService;
    private final ModerationJobService moderationJobService;
    private final UserBadgeService userBadgeService;
    private final SpaceValidationService spaceValidationService;
    private final org.earnlumens.mediastore.application.payment.StellarTransactionService stellarTransactionService;
    private final int dailyEntryLimit;
    private final int maxConcurrentReview;

    public EntryUploadService(
            EntryRepository entryRepository,
            AssetRepository assetRepository,
            UserRepository userRepository,
            OrderRepository orderRepository,
            CollectionRepository collectionRepository,
            R2PresignedUrlService r2PresignedUrlService,
            R2StorageService r2StorageService,
            UploadSessionRepository uploadSessionRepository,
            PlatformConfig platformConfig,
            TranscodingJobService transcodingJobService,
            ModerationJobService moderationJobService,
            UserBadgeService userBadgeService,
            SpaceValidationService spaceValidationService,
            org.earnlumens.mediastore.application.payment.StellarTransactionService stellarTransactionService,
            @Value("${mediastore.abuse.daily-entry-limit:20}") int dailyEntryLimit,
            @Value("${mediastore.abuse.max-concurrent-review:10}") int maxConcurrentReview
    ) {
        this.entryRepository = entryRepository;
        this.assetRepository = assetRepository;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.collectionRepository = collectionRepository;
        this.r2PresignedUrlService = r2PresignedUrlService;
        this.r2StorageService = r2StorageService;
        this.uploadSessionRepository = uploadSessionRepository;
        this.platformConfig = platformConfig;
        this.transcodingJobService = transcodingJobService;
        this.moderationJobService = moderationJobService;
        this.userBadgeService = userBadgeService;
        this.spaceValidationService = spaceValidationService;
        this.stellarTransactionService = stellarTransactionService;
        this.dailyEntryLimit = dailyEntryLimit;
        this.maxConcurrentReview = maxConcurrentReview;
    }

    /**
     * Creates a new DRAFT entry owned by the given user.
     * <p>
     * For paid content, a valid Stellar wallet (sellerWallet) is required.
     * The entry stores only non-platform splits (SELLER, COLLABORATOR).
     * The platform wallet and fee are applied dynamically at payment time
     * from environment config (PLATFORM_WALLET, PLATFORM_FEE_PERCENT).
     * <p>
     * Future: sellers will be able to add COLLABORATOR splits manually.
     */
    public CreateEntryResponse createEntry(String tenantId, String userId, CreateEntryRequest request) {
        // ── Abuse prevention: daily entry creation limit ──────────────
        long entriesToday = entryRepository.countByTenantIdAndUserIdAndCreatedAtAfter(
                tenantId, userId, java.time.LocalDateTime.now().minusHours(24));
        if (entriesToday >= dailyEntryLimit) {
            logger.warn("createEntry: daily limit reached for user={} (count={}, limit={})",
                    userId, entriesToday, dailyEntryLimit);
            throw new IllegalArgumentException("DAILY_ENTRY_LIMIT_REACHED");
        }

        EntryType entryType = EntryType.valueOf(request.type());
        boolean isPaid = Boolean.TRUE.equals(request.isPaid());

        // Parse currency (default to XLM for backward compatibility)
        PriceCurrency currency = parsePriceCurrency(request.priceCurrency());

        // Validate wallet requirement for paid content
        if (isPaid) {
            if (request.sellerWallet() == null || request.sellerWallet().isBlank()) {
                throw new IllegalArgumentException("A connected wallet is required for paid content");
            }
            if (!STELLAR_PUBLIC_KEY.matcher(request.sellerWallet()).matches()) {
                throw new IllegalArgumentException("Invalid Stellar public key");
            }
            // Validate that at least one price field is provided based on currency
            if (currency == PriceCurrency.USD) {
                if (request.priceUsd() == null || request.priceUsd().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("Price must be greater than 0 for paid content");
                }
            } else {
                if (request.priceXlm() == null || request.priceXlm().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("Price must be greater than 0 for paid content");
                }
            }
            if (request.sellerWallet().equals(platformConfig.getWallet())) {
                throw new IllegalArgumentException("Seller wallet cannot be the platform wallet");
            }
            // The seller wallet becomes a payment-split destination; it must
            // already exist on the Stellar network or every future sale of
            // this entry would fail with op_no_destination.
            if (!stellarTransactionService.isAccountActive(request.sellerWallet())) {
                throw new IllegalArgumentException("WALLET_NOT_ACTIVATED");
            }
        }

        Entry entry = new Entry();
        entry.setTenantId(tenantId);
        entry.setUserId(userId);
        entry.setTitle(request.title());
        entry.setDescription(request.description());
        entry.setResourceContent(request.resourceContent());
        entry.setType(entryType);
        entry.setStatus(EntryStatus.DRAFT);
        entry.setVisibility(MediaVisibility.PRIVATE);
        entry.setPaid(isPaid);
        entry.setPriceXlm(request.priceXlm());
        entry.setPriceUsd(request.priceUsd());
        entry.setPriceCurrency(currency);
        entry.setContentLanguage(request.contentLanguage());
        entry.setSpaceIds(spaceValidationService.validateForPublish(tenantId, request.spaceIds()));

        // Set seller wallet and generate payment splits for paid content.
        // Only non-platform splits are stored in the entry. The platform split
        // (wallet + fee%) is applied dynamically at payment time from env config.
        if (isPaid) {
            entry.setSellerWallet(request.sellerWallet());
            entry.setPaymentSplits(buildSellerSplits(request.sellerWallet()));
            logger.info("Paid entry: sellerWallet={}, splits={}", request.sellerWallet(),
                    entry.getPaymentSplits().size());
        }

        // Denormalize author info for fast reads (no user join at query time)
        // userId is the OAuth provider ID (e.g. Google ID), not MongoDB _id
        // NOTE: this is a snapshot — kept fresh on profile change by
        // AuthService.generateTempUUID() (EntryRepository.updateAuthorInfoByUserId).
        userRepository.findByOauthUserId(userId).ifPresent(user -> {
            entry.setAuthorUsername(user.getUsername());
            entry.setAuthorAvatarUrl(user.getProfileImageUrl());
        });

        Entry saved = entryRepository.save(entry);

        logger.info("Created DRAFT entry: id={}, tenantId={}, userId={}, isPaid={}",
                saved.getId(), tenantId, userId, isPaid);

        return new CreateEntryResponse(
                saved.getId(),
                saved.getTitle(),
                saved.getType().name(),
                saved.getStatus().name()
        );
    }

    /**
     * Builds the non-platform payment splits for an entry.
     * Only the SELLER split is stored in the entry. The PLATFORM split
     * (wallet + fee%) is resolved dynamically at payment time from environment config.
     * <p>
     * Splits represent how to divide the non-platform portion among recipients.
     * A single seller gets 100.00 (= 100% of the seller portion).
     * Future collaborators would split this (e.g. SELLER 80 + COLLABORATOR 20).
     * At payment time, these are scaled to (100 - platformFee)% of the total.
     *
     * @param sellerWallet the seller's Stellar public key
     * @return list with the SELLER split at 100% of the non-platform portion
     */
    private List<PaymentSplit> buildSellerSplits(String sellerWallet) {
        List<PaymentSplit> splits = new ArrayList<>();
        splits.add(new PaymentSplit(sellerWallet, SplitRole.SELLER, ONE_HUNDRED));
        return splits;
    }

    /**
     * Generates a presigned PUT URL so the client can upload a file directly to R2.
     * Verifies the entry exists and belongs to the requesting user.
     *
     * @return the upload response, or empty if the entry is not found or not owned
     */
    public Optional<InitUploadResponse> initUpload(String tenantId, String userId, InitUploadRequest request) {
        Optional<Entry> optEntry = entryRepository.findByTenantIdAndId(tenantId, request.entryId());
        if (optEntry.isEmpty()) {
            logger.debug("initUpload: entry not found: tenantId={}, entryId={}", tenantId, request.entryId());
            return Optional.empty();
        }

        Entry entry = optEntry.get();
        if (!userId.equals(entry.getUserId())) {
            logger.warn("initUpload: user {} does not own entry {}", userId, request.entryId());
            return Optional.empty();
        }

        MediaKind kind = MediaKind.valueOf(request.kind());
        String sanitizedFileName = sanitizeFileName(request.fileName());

        // ── Boundary defense: reject dangerous / unsupported file types ──
        // The client controls both contentType and fileName, so we validate
        // against the declared content type AND the file extension. This is
        // the first of three layers (boundary → ClamAV scan → download
        // disclaimer); the moderation worker scans the actual bytes later.
        validateUploadType(entry.getType(), kind, request.contentType(), request.fileName());

        // ── Server-side size limit (don't trust the client UI alone) ──
        validateFileSize(entry.getType(), kind, request.fileSizeBytes());

        // THUMBNAIL and PREVIEW go under public/ prefix so CDN Worker serves them without auth.
        // FULL (paid content) stays under private/ — served via /media/<entryId> with entitlement check.
        String prefix = (kind == MediaKind.THUMBNAIL || kind == MediaKind.PREVIEW) ? "public" : "private";
        String r2Key = String.format("%s/media/%s/%s/%s-%s",
                prefix,
                request.entryId(),
                kind.name().toLowerCase(),
                UUID.randomUUID(),
                sanitizedFileName);

        String uploadId = UUID.randomUUID().toString();
        boolean multipart = request.fileSizeBytes() >= MULTIPART_THRESHOLD_BYTES;

        UploadSession session = new UploadSession();
        session.setId(uploadId);
        session.setTenantId(tenantId);
        session.setUserId(userId);
        session.setEntryId(request.entryId());
        session.setKind(kind.name());
        session.setR2Key(r2Key);
        session.setContentType(request.contentType());
        session.setFileName(request.fileName());
        session.setExpectedSizeBytes(request.fileSizeBytes());
        session.setMultipart(multipart);

        if (multipart) {
            String s3UploadId = r2StorageService.createMultipartUpload(r2Key, request.contentType());
            int totalParts = (int) ((request.fileSizeBytes() + PART_SIZE_BYTES - 1) / PART_SIZE_BYTES);
            List<String> partUrls = new ArrayList<>(totalParts);
            for (int part = 1; part <= totalParts; part++) {
                partUrls.add(r2PresignedUrlService.generatePresignedUploadPartUrl(r2Key, s3UploadId, part));
            }
            session.setS3UploadId(s3UploadId);
            session.setPartSizeBytes(PART_SIZE_BYTES);
            session.setTotalParts(totalParts);
            uploadSessionRepository.save(session);

            logger.info("initUpload (multipart): uploadId={}, entryId={}, kind={}, r2Key={}, parts={}",
                    uploadId, request.entryId(), kind, r2Key, totalParts);

            return Optional.of(new InitUploadResponse(uploadId, null, r2Key, true, PART_SIZE_BYTES, partUrls));
        }

        String presignedUrl = r2PresignedUrlService.generatePresignedPutUrl(
                r2Key, request.contentType(), R2PresignedUrlService.SINGLE_PUT_DURATION);
        uploadSessionRepository.save(session);

        logger.info("initUpload: uploadId={}, entryId={}, kind={}, r2Key={}",
                uploadId, request.entryId(), kind, r2Key);

        return Optional.of(new InitUploadResponse(uploadId, presignedUrl, r2Key));
    }

    /**
     * Persists an Asset record after the client has finished uploading to R2.
     * Verifies entry ownership.
     *
     * @return the finalize response, or empty if the entry is not found or not owned
     */
    public Optional<FinalizeUploadResponse> finalizeUpload(String tenantId, String userId, FinalizeUploadRequest request) {
        Optional<Entry> optEntry = entryRepository.findByTenantIdAndId(tenantId, request.entryId());
        if (optEntry.isEmpty()) {
            logger.debug("finalizeUpload: entry not found: tenantId={}, entryId={}", tenantId, request.entryId());
            return Optional.empty();
        }

        Entry entry = optEntry.get();
        if (!userId.equals(entry.getUserId())) {
            logger.warn("finalizeUpload: user {} does not own entry {}", userId, request.entryId());
            return Optional.empty();
        }

        MediaKind kind = MediaKind.valueOf(request.kind());

        // ── Resolve the upload session: source of truth for r2Key/contentType ──
        // Never trust the client-supplied r2Key when a session exists.
        UploadSession session = uploadSessionRepository
                .findByIdAndTenantId(request.uploadId(), tenantId)
                .orElse(null);

        final String r2Key;
        final String contentType;
        final String fileName;

        if (session != null) {
            if (!userId.equals(session.getUserId())
                    || !request.entryId().equals(session.getEntryId())
                    || !kind.name().equals(session.getKind())) {
                logger.warn("finalizeUpload: session mismatch: uploadId={}, entryId={}, kind={}",
                        request.uploadId(), request.entryId(), kind);
                throw new IllegalArgumentException("UPLOAD_SESSION_MISMATCH");
            }

            // Idempotency: a finalize retry after a lost response must not
            // create a duplicate asset.
            if (session.getStatus() == UploadSession.Status.COMPLETED && session.getAssetId() != null) {
                logger.info("finalizeUpload: idempotent retry for uploadId={}, returning existing asset {}",
                        request.uploadId(), session.getAssetId());
                String existingAssetId = session.getAssetId();
                return assetRepository.findByTenantIdAndEntryId(tenantId, request.entryId()).stream()
                        .filter(a -> existingAssetId.equals(a.getId()))
                        .findFirst()
                        .map(a -> new FinalizeUploadResponse(a.getId(), a.getStatus().name()));
            }

            // Multipart uploads must be assembled before finalize. If the
            // client's /complete call was lost in transit, recover here.
            if (session.isMultipart() && r2StorageService.headObject(session.getR2Key()).isEmpty()) {
                try {
                    r2StorageService.completeMultipartUpload(session.getR2Key(), session.getS3UploadId());
                } catch (Exception e) {
                    logger.warn("finalizeUpload: multipart auto-complete failed for uploadId={}: {}",
                            request.uploadId(), e.getMessage());
                }
            }

            r2Key = session.getR2Key();
            contentType = session.getContentType();
            fileName = session.getFileName();
        } else {
            // ── Legacy fallback (uploads initiated before session persistence
            // was deployed). Strictly validate the claimed key shape and that
            // it belongs to this entry/kind before accepting it.
            var matcher = LEGACY_R2_KEY.matcher(request.r2Key() == null ? "" : request.r2Key());
            String expectedPrefix = (kind == MediaKind.THUMBNAIL || kind == MediaKind.PREVIEW) ? "public" : "private";
            if (!matcher.matches()
                    || !matcher.group(1).equals(expectedPrefix)
                    || !matcher.group(2).equals(request.entryId())
                    || !matcher.group(3).equals(kind.name().toLowerCase())) {
                logger.warn("finalizeUpload: rejected r2Key '{}' for entryId={}, kind={} (no session, invalid shape)",
                        request.r2Key(), request.entryId(), kind);
                throw new IllegalArgumentException("INVALID_UPLOAD");
            }
            r2Key = request.r2Key();
            contentType = request.contentType();
            fileName = request.fileName();
        }

        // ── Verify the bytes actually landed in R2 and get the true size ──
        var head = r2StorageService.headObject(r2Key)
                .orElseThrow(() -> {
                    logger.warn("finalizeUpload: object not found in R2: key={}, uploadId={}",
                            r2Key, request.uploadId());
                    return new IllegalArgumentException("UPLOAD_NOT_FOUND");
                });
        long actualSizeBytes = head.contentLength() != null ? head.contentLength() : request.fileSizeBytes();
        if (request.fileSizeBytes() != null && actualSizeBytes != request.fileSizeBytes()) {
            logger.warn("finalizeUpload: size mismatch for key={}: client={} actual={}",
                    r2Key, request.fileSizeBytes(), actualSizeBytes);
        }
        validateFileSize(entry.getType(), kind, actualSizeBytes);

        Asset asset = new Asset();
        asset.setTenantId(tenantId);
        asset.setEntryId(request.entryId());
        asset.setR2Key(r2Key);
        asset.setContentType(contentType);
        asset.setFileName(fileName);
        asset.setFileSizeBytes(actualSizeBytes);
        asset.setKind(kind);

        // Persist client-extracted media metadata (best-effort, nullable)
        asset.setWidthPx(request.widthPx());
        asset.setHeightPx(request.heightPx());
        asset.setDurationSec(request.durationSec());
        asset.setCodecVideo(request.codecVideo());
        asset.setCodecAudio(request.codecAudio());
        asset.setBitrateBps(request.bitrateBps());

        // Videos uploaded as FULL need HLS transcoding — start as UPLOADED.
        // Non-video FULL assets and THUMBNAIL/PREVIEW are immediately READY.
        boolean needsTranscoding = kind == MediaKind.FULL && entry.getType() == EntryType.VIDEO;
        asset.setStatus(needsTranscoding ? AssetStatus.UPLOADED : AssetStatus.READY);

        Asset saved = assetRepository.save(asset);

        // Mark the session completed so finalize retries are idempotent.
        if (session != null) {
            session.setStatus(UploadSession.Status.COMPLETED);
            session.setAssetId(saved.getId());
            session.setCompletedAt(java.time.LocalDateTime.now());
            uploadSessionRepository.save(session);
        }

        // NOTE: Transcoding for VIDEO assets is deferred until AFTER moderation approval.
        // ModerationJobService.createTranscodingJobForEntry() handles this.

        // If a THUMBNAIL was finalized, denormalize its R2 key onto the entry for fast reads
        if (kind == MediaKind.THUMBNAIL) {
            entry.setThumbnailR2Key(r2Key);
            entryRepository.save(entry);
            logger.info("finalizeUpload: set thumbnailR2Key on entry {}: {}", request.entryId(), r2Key);
        }
        // If a PREVIEW was finalized, denormalize its R2 key onto the entry
        if (kind == MediaKind.PREVIEW) {
            entry.setPreviewR2Key(r2Key);
            entryRepository.save(entry);
            logger.info("finalizeUpload: set previewR2Key on entry {}: {}", request.entryId(), r2Key);
        }

        // ── Re-moderation: visual asset change on a non-draft entry triggers re-review ──
        // This must also handle entries already IN_REVIEW (e.g. from updateEntryMetadata)
        // because the existing moderation job has a stale thumbnailR2Key.
        if (kind == MediaKind.THUMBNAIL || kind == MediaKind.PREVIEW) {
            boolean needsReReview = entry.getStatus() != EntryStatus.DRAFT
                    && entry.getStatus() != EntryStatus.ARCHIVED
                    && entry.getStatus() != EntryStatus.DELETED;
            if (needsReReview) {
                // Cancel any active moderation job — it has the old thumbnailR2Key
                // and may already be dispatched to Cloud Run with stale env vars.
                moderationJobService.findActiveByTenantIdAndEntryId(tenantId, entry.getId())
                        .ifPresent(activeJob -> {
                            moderationJobService.cancelJob(activeJob,
                                    "Superseded by new " + kind.name().toLowerCase() + " upload");
                            logger.info("finalizeUpload: cancelled active moderation job {} (superseded by {} change)",
                                    activeJob.getId(), kind);
                        });

                if (entry.getStatus() != EntryStatus.IN_REVIEW) {
                    EntryStatus previousStatus = entry.getStatus();
                    entry.getStatusHistory().add(
                            new org.earnlumens.mediastore.domain.media.model.StatusChangeRecord(
                                    previousStatus, EntryStatus.IN_REVIEW, null,
                                    kind == MediaKind.THUMBNAIL ? "thumbnail changed" : "preview changed"));
                    entry.setStatus(EntryStatus.IN_REVIEW);
                    entry.setModerationFeedback(null);
                    entry.setUpdatedAt(java.time.LocalDateTime.now());
                    entryRepository.save(entry);
                }
                createModerationJob(tenantId, entry);
                logger.info("finalizeUpload: triggered re-moderation for entry={} ({}, status was {})",
                        request.entryId(), kind, entry.getStatus());
            }
        }

        // Denormalize duration onto the entry for feed display (from the FULL asset)
        if (kind == MediaKind.FULL && request.durationSec() != null && request.durationSec() > 0) {
            entry.setDurationSec(request.durationSec());
            entryRepository.save(entry);
            logger.info("finalizeUpload: set durationSec={} on entry {}", request.durationSec(), request.entryId());
        }

        logger.info("finalizeUpload: assetId={}, entryId={}, kind={}, r2Key={}, widthPx={}, heightPx={}, durationSec={}",
                saved.getId(), request.entryId(), kind, r2Key,
                request.widthPx(), request.heightPx(), request.durationSec());

        return Optional.of(new FinalizeUploadResponse(saved.getId(), saved.getStatus().name()));
    }

    /**
     * Assembles a multipart upload server-side (via ListParts, so the client
     * never needs the ETag response header). Idempotent: completing an
     * already-completed upload succeeds as long as the object exists.
     *
     * @return true on success, false if the session is unknown / not owned
     */
    public boolean completeMultipart(String tenantId, String userId, String uploadId) {
        UploadSession session = uploadSessionRepository.findByIdAndTenantId(uploadId, tenantId).orElse(null);
        if (session == null || !userId.equals(session.getUserId())) {
            logger.warn("completeMultipart: session not found or not owned: uploadId={}", uploadId);
            return false;
        }
        if (!session.isMultipart()) {
            throw new IllegalArgumentException("NOT_MULTIPART");
        }
        if (r2StorageService.headObject(session.getR2Key()).isPresent()) {
            // Already assembled (retry of a lost response) — success.
            return true;
        }
        try {
            r2StorageService.completeMultipartUpload(session.getR2Key(), session.getS3UploadId());
            return true;
        } catch (Exception e) {
            // The multipart may have been completed by a concurrent retry.
            if (r2StorageService.headObject(session.getR2Key()).isPresent()) {
                return true;
            }
            logger.error("completeMultipart failed: uploadId={}: {}", uploadId, e.getMessage());
            throw new IllegalArgumentException("MULTIPART_COMPLETE_FAILED");
        }
    }

    /**
     * Re-signs the URL for a single part of an in-progress multipart upload
     * (used by the client if the original batch of URLs expired mid-upload).
     */
    public Optional<String> generatePartUrl(String tenantId, String userId, String uploadId, int partNumber) {
        UploadSession session = uploadSessionRepository.findByIdAndTenantId(uploadId, tenantId).orElse(null);
        if (session == null || !userId.equals(session.getUserId())) {
            return Optional.empty();
        }
        if (!session.isMultipart() || session.getTotalParts() == null
                || partNumber < 1 || partNumber > session.getTotalParts()) {
            throw new IllegalArgumentException("INVALID_PART_NUMBER");
        }
        return Optional.of(r2PresignedUrlService.generatePresignedUploadPartUrl(
                session.getR2Key(), session.getS3UploadId(), partNumber));
    }

    /**
     * Aborts an in-progress upload: cancels the multipart upload (freeing
     * stored parts) or deletes the single-PUT object, and marks the session
     * aborted. Best-effort; safe to call repeatedly.
     *
     * @return true if the session was found and aborted
     */
    public boolean abortUpload(String tenantId, String userId, String uploadId) {
        UploadSession session = uploadSessionRepository.findByIdAndTenantId(uploadId, tenantId).orElse(null);
        if (session == null || !userId.equals(session.getUserId())) {
            return false;
        }
        if (session.getStatus() == UploadSession.Status.COMPLETED) {
            // Finalized uploads are owned by the asset lifecycle now.
            return false;
        }
        if (session.isMultipart() && session.getS3UploadId() != null) {
            r2StorageService.abortMultipartUpload(session.getR2Key(), session.getS3UploadId());
        } else {
            r2StorageService.deleteObject(session.getR2Key());
        }
        session.setStatus(UploadSession.Status.ABORTED);
        uploadSessionRepository.save(session);
        logger.info("abortUpload: uploadId={}, r2Key={}", uploadId, session.getR2Key());
        return true;
    }

    /**
     * Upload limits and multipart parameters, exposed so the client always
     * validates against the same numbers the server enforces.
     */
    public Map<String, Object> getUploadConfig() {
        Map<String, Object> maxFullBytes = new HashMap<>();
        MAX_FULL_BYTES.forEach((type, bytes) -> maxFullBytes.put(type.name(), bytes));
        Map<String, Object> config = new HashMap<>();
        config.put("maxFullBytes", maxFullBytes);
        config.put("maxThumbnailBytes", MAX_THUMBNAIL_BYTES);
        config.put("maxPreviewBytes", MAX_PREVIEW_BYTES);
        config.put("multipartThresholdBytes", MULTIPART_THRESHOLD_BYTES);
        config.put("partSizeBytes", PART_SIZE_BYTES);
        return config;
    }

    /**
     * Enforces per-kind/per-type maximum file sizes at the server boundary.
     *
     * @throws IllegalArgumentException with code FILE_TOO_LARGE
     */
    private void validateFileSize(EntryType entryType, MediaKind kind, Long sizeBytes) {
        if (sizeBytes == null || sizeBytes <= 0) {
            throw new IllegalArgumentException("INVALID_FILE_SIZE");
        }
        long limit = switch (kind) {
            case THUMBNAIL -> MAX_THUMBNAIL_BYTES;
            case PREVIEW -> MAX_PREVIEW_BYTES;
            case FULL -> MAX_FULL_BYTES.getOrDefault(entryType, 2L * GB);
        };
        if (sizeBytes > limit) {
            logger.warn("validateFileSize: rejected {} bytes for kind={}, type={} (limit={})",
                    sizeBytes, kind, entryType, limit);
            throw new IllegalArgumentException("FILE_TOO_LARGE");
        }
    }

    /**
     * Updates the entry status. Currently only supports DRAFT → IN_REVIEW.
     *
     * @return true if the status was updated, false if entry not found, not owned, or invalid transition
     */
    /**
     * Updates entry metadata (title, description, isPaid, priceXlm).
     * Only non-null fields in the request are applied.
     *
     * @return true if updated, false if entry not found or not owned
     */
    public boolean updateEntryMetadata(String tenantId, String userId, String entryId, UpdateEntryMetadataRequest request) {
        Optional<Entry> optEntry = entryRepository.findByTenantIdAndId(tenantId, entryId);
        if (optEntry.isEmpty()) {
            logger.debug("updateEntryMetadata: entry not found: tenantId={}, entryId={}", tenantId, entryId);
            return false;
        }

        Entry entry = optEntry.get();
        if (!userId.equals(entry.getUserId())) {
            logger.warn("updateEntryMetadata: user {} does not own entry {}", userId, entryId);
            return false;
        }

        // ── Block edits while transcoding to avoid races between worker output and entry state ──
        if (isTranscodingInProgress(tenantId, entry)) {
            logger.warn("updateEntryMetadata: blocked, transcoding in progress for entry={}", entryId);
            throw new IllegalArgumentException("TRANSCODING_IN_PROGRESS");
        }

        if (request.title() != null) {
            entry.setTitle(request.title());
        }
        if (request.description() != null) {
            entry.setDescription(request.description());
        }
        if (request.isPaid() != null) {
            entry.setPaid(request.isPaid());
            if (Boolean.TRUE.equals(request.isPaid())) {
                PriceCurrency currency = parsePriceCurrency(request.priceCurrency());
                // Same price guard as createEntry: a paid entry must never be
                // persisted without a positive price or it becomes unsellable.
                if (currency == PriceCurrency.USD) {
                    if (request.priceUsd() == null || request.priceUsd().compareTo(BigDecimal.ZERO) <= 0) {
                        throw new IllegalArgumentException("Price must be greater than 0 for paid content");
                    }
                } else {
                    if (request.priceXlm() == null || request.priceXlm().compareTo(BigDecimal.ZERO) <= 0) {
                        throw new IllegalArgumentException("Price must be greater than 0 for paid content");
                    }
                }
                entry.setPriceCurrency(currency);
                if (currency == PriceCurrency.USD) {
                    entry.setPriceUsd(request.priceUsd());
                    entry.setPriceXlm(null);
                } else {
                    entry.setPriceXlm(request.priceXlm());
                    entry.setPriceUsd(null);
                }
                // Set seller wallet when switching to paid (required if entry has no wallet yet)
                if (request.sellerWallet() != null && !request.sellerWallet().isBlank()) {
                    if (!STELLAR_PUBLIC_KEY.matcher(request.sellerWallet()).matches()) {
                        throw new IllegalArgumentException("Invalid Stellar public key");
                    }
                    if (request.sellerWallet().equals(platformConfig.getWallet())) {
                        throw new IllegalArgumentException("Seller wallet cannot be the platform wallet");
                    }
                    if (!stellarTransactionService.isAccountActive(request.sellerWallet())) {
                        throw new IllegalArgumentException("WALLET_NOT_ACTIVATED");
                    }
                    entry.setSellerWallet(request.sellerWallet());
                    entry.setPaymentSplits(buildSellerSplits(request.sellerWallet()));
                } else if (entry.getSellerWallet() == null || entry.getSellerWallet().isBlank()) {
                    throw new IllegalArgumentException("A connected wallet is required for paid content");
                }
            } else if (Boolean.FALSE.equals(request.isPaid())) {
                entry.setPriceXlm(null);
                entry.setPriceUsd(null);
                entry.setPriceCurrency(null);
            }
        }

        // contentLanguage is intentionally NOT user-editable here.
        // Initial value comes from upload (user-declared default) and is
        // overridden later by the moderation pipeline (source of truth).

        if (request.resourceContent() != null) {
            entry.setResourceContent(request.resourceContent());
        }

        if (request.spaceIds() != null) {
            // null = leave unchanged; empty list = clear; non-empty = replace.
            entry.setSpaceIds(spaceValidationService.validateForPublish(tenantId, request.spaceIds()));
        }

        // ── Auto-moderation: any edit on a non-DRAFT entry triggers re-review ──
        boolean requiresReReview = entry.getStatus() != EntryStatus.DRAFT
                && entry.getStatus() != EntryStatus.IN_REVIEW
                && entry.getStatus() != EntryStatus.ARCHIVED
                && entry.getStatus() != EntryStatus.DELETED;

        if (requiresReReview) {
            EntryStatus previousStatus = entry.getStatus();
            entry.getStatusHistory().add(
                    new org.earnlumens.mediastore.domain.media.model.StatusChangeRecord(
                            previousStatus, EntryStatus.IN_REVIEW, null, "metadata edited"));
            entry.setStatus(EntryStatus.IN_REVIEW);
            logger.info("updateEntryMetadata: auto-transition {} → IN_REVIEW for entry={}",
                    previousStatus, entryId);
        }

        entry.setUpdatedAt(java.time.LocalDateTime.now());
        entryRepository.save(entry);

        // Create moderation job after save so the job sees the updated content
        if (requiresReReview) {
            createModerationJob(tenantId, entry);
        }

        logger.info("updateEntryMetadata: entryId={}, title={}", entryId, entry.getTitle());
        return true;
    }

    public boolean updateEntryStatus(String tenantId, String userId, String entryId, UpdateEntryStatusRequest request) {
        Optional<Entry> optEntry = entryRepository.findByTenantIdAndId(tenantId, entryId);
        if (optEntry.isEmpty()) {
            logger.debug("updateEntryStatus: entry not found: tenantId={}, entryId={}", tenantId, entryId);
            return false;
        }

        Entry entry = optEntry.get();
        if (!userId.equals(entry.getUserId())) {
            logger.warn("updateEntryStatus: user {} does not own entry {}", userId, entryId);
            return false;
        }

        EntryStatus newStatus = EntryStatus.valueOf(request.status());

        // Validate allowed transitions
        if (!isValidStatusTransition(entry.getStatus(), newStatus)) {
            logger.warn("updateEntryStatus: invalid transition {} → {} for entry {}",
                    entry.getStatus(), newStatus, entryId);
            return false;
        }

        // ── Ensure the post-approval media pipeline ran before going live ──
        // Content the AI sent to MANUAL_QUEUE and a human moderator approved
        // (admin-api writes status directly to Mongo) bypasses the AI
        // auto-approval path in ModerationJobService.handleApproval, so its
        // thumbnail variants and — for VIDEO — HLS transcoding were never
        // enqueued. Trigger them here so a manually-approved entry gets the
        // same processing as an auto-approved one. Idempotent: both steps skip
        // when a job already exists. Runs BEFORE the transcoding-in-progress
        // guard so a freshly-created HLS job blocks the publish until it
        // finishes, mirroring the auto-approval flow.
        if (newStatus == EntryStatus.PUBLISHED) {
            moderationJobService.ensurePostApprovalMediaPipeline(tenantId, entry);
        }

        // ── Block submit-for-review and publish while transcoding ──
        // Archiving / unarchiving / re-edit transitions remain permitted so the
        // creator is never locked out of their own draft mid-encode.
        if ((newStatus == EntryStatus.IN_REVIEW || newStatus == EntryStatus.PUBLISHED)
                && isTranscodingInProgress(tenantId, entry)) {
            logger.warn("updateEntryStatus: blocked transition to {}, transcoding in progress for entry={}",
                    newStatus, entryId);
            throw new IllegalArgumentException("TRANSCODING_IN_PROGRESS");
        }

        // ── Abuse prevention: burst detection on submit for review ────
        if (newStatus == EntryStatus.IN_REVIEW) {
            long inReviewCount = entryRepository.countByTenantIdAndUserIdAndStatus(
                    tenantId, userId, EntryStatus.IN_REVIEW);
            if (inReviewCount >= maxConcurrentReview) {
                logger.warn("updateEntryStatus: burst limit reached for user={} (inReview={}, limit={})",
                        userId, inReviewCount, maxConcurrentReview);
                throw new IllegalArgumentException("TOO_MANY_PENDING_REVIEWS");
            }
        }

        // When archiving or soft-deleting, remember the current status so we can restore it later
        if (newStatus == EntryStatus.ARCHIVED || newStatus == EntryStatus.DELETED) {
            entry.setPreviousStatus(entry.getStatus());
        }

        // When publishing, stamp the author's active badge and publishedAt timestamp
        if (newStatus == EntryStatus.PUBLISHED) {
            userBadgeService.getActiveBadgeKey(tenantId, userId)
                    .ifPresent(entry::setAuthorBadge);
            entry.setPublishedAt(java.time.LocalDateTime.now());
        }

        // Record audit trail
        EntryStatus previousStatus = entry.getStatus();
        entry.getStatusHistory().add(
                new org.earnlumens.mediastore.domain.media.model.StatusChangeRecord(
                        previousStatus, newStatus, entry.getAuthorUsername(), null));

        entry.setStatus(newStatus);
        entryRepository.save(entry);

        // When transitioning to IN_REVIEW, create a moderation job
        if (newStatus == EntryStatus.IN_REVIEW) {
            createModerationJob(tenantId, entry);
        }

        logger.info("updateEntryStatus: entryId={}, {} → {}", entryId, entry.getStatus(), newStatus);
        return true;
    }

    /**
     * Creates a moderation job for an entry that just transitioned to IN_REVIEW.
     * The moderation pipeline will run BEFORE any transcoding happens.
     */
    private void createModerationJob(String tenantId, Entry entry) {
        // Skip if there's already an active moderation job
        if (moderationJobService.findActiveByTenantIdAndEntryId(tenantId, entry.getId()).isPresent()) {
            logger.debug("createModerationJob: skipping — active moderation job exists for entry={}",
                    entry.getId());
            return;
        }

        // Find the source R2 key — for VIDEO/AUDIO/IMAGE it's the FULL asset
        String sourceR2Key = null;
        String sourceContentType = null;
        String sourceFileName = null;
        var optAsset = assetRepository.findByTenantIdAndEntryIdAndKindAndStatus(
                tenantId, entry.getId(), MediaKind.FULL, AssetStatus.UPLOADED);
        if (optAsset.isEmpty()) {
            optAsset = assetRepository.findByTenantIdAndEntryIdAndKindAndStatus(
                    tenantId, entry.getId(), MediaKind.FULL, AssetStatus.READY);
        }
        if (optAsset.isPresent()) {
            sourceR2Key = optAsset.get().getR2Key();
            sourceContentType = optAsset.get().getContentType();
            sourceFileName = optAsset.get().getFileName();
        }

        if (sourceR2Key == null && entry.getType() != org.earnlumens.mediastore.domain.media.model.EntryType.RESOURCE) {
            logger.warn("createModerationJob: no source asset found for entry={}, type={}",
                    entry.getId(), entry.getType());
            return;
        }

        // For RESOURCE entries without uploaded files, use a placeholder
        if (sourceR2Key == null) {
            sourceR2Key = "";
        }

        ModerationJob job = new ModerationJob();
        job.setTenantId(tenantId);
        job.setEntryId(entry.getId());
        job.setSourceR2Key(sourceR2Key);
        job.setSourceContentType(sourceContentType);
        job.setSourceFileName(sourceFileName);
        job.setThumbnailR2Key(entry.getThumbnailR2Key());
        job.setPreviewR2Key(entry.getPreviewR2Key());
        job.setEntryType(entry.getType());
        job.setEntryTitle(entry.getTitle());
        job.setEntryDescription(entry.getDescription());
        // Join tags list into comma-separated string for the worker
        if (entry.getTags() != null && !entry.getTags().isEmpty()) {
            job.setEntryTags(String.join(", ", entry.getTags()));
        }
        // Pass resourceContent for RESOURCE entries so Gemini can analyze the full text body
        if (entry.getType() == org.earnlumens.mediastore.domain.media.model.EntryType.RESOURCE
                && entry.getResourceContent() != null) {
            job.setResourceContent(entry.getResourceContent());
        }
        job.setStatus(ModerationJobStatus.PENDING);
        job.setRetryCount(0);
        job.setMaxRetries(moderationJobService.getMaxRetries());
        moderationJobService.createJob(job);

        logger.info("createModerationJob: created moderation job for entry={}, type={}",
                entry.getId(), entry.getType());
    }

    /**
     * Restores an archived entry to its previous status.
     *
     * @return true if unarchived, false if entry not found, not owned, or not archived
     */
    public boolean unarchiveEntry(String tenantId, String userId, String entryId) {
        Optional<Entry> optEntry = entryRepository.findByTenantIdAndId(tenantId, entryId);
        if (optEntry.isEmpty()) {
            logger.debug("unarchiveEntry: entry not found: tenantId={}, entryId={}", tenantId, entryId);
            return false;
        }

        Entry entry = optEntry.get();
        if (!userId.equals(entry.getUserId())) {
            logger.warn("unarchiveEntry: user {} does not own entry {}", userId, entryId);
            return false;
        }

        if (entry.getStatus() != EntryStatus.ARCHIVED) {
            logger.warn("unarchiveEntry: entry {} is not archived (status={})", entryId, entry.getStatus());
            return false;
        }

        EntryStatus restoreTo = entry.getPreviousStatus() != null
                ? entry.getPreviousStatus()
                : EntryStatus.DRAFT;

        logger.info("unarchiveEntry: entryId={}, ARCHIVED → {}", entryId, restoreTo);
        entry.getStatusHistory().add(
                new org.earnlumens.mediastore.domain.media.model.StatusChangeRecord(
                        EntryStatus.ARCHIVED, restoreTo, entry.getAuthorUsername(), null));
        entry.setStatus(restoreTo);
        entry.setPreviousStatus(null);
        entryRepository.save(entry);
        return true;
    }

    /**
     * Restores a soft-deleted entry to its previous status.
     *
     * @return true if restored, false if entry not found, not owned, or not deleted
     */
    public boolean restoreDeletedEntry(String tenantId, String userId, String entryId) {
        Optional<Entry> optEntry = entryRepository.findByTenantIdAndId(tenantId, entryId);
        if (optEntry.isEmpty()) {
            logger.debug("restoreDeletedEntry: entry not found: tenantId={}, entryId={}", tenantId, entryId);
            return false;
        }

        Entry entry = optEntry.get();
        if (!userId.equals(entry.getUserId())) {
            logger.warn("restoreDeletedEntry: user {} does not own entry {}", userId, entryId);
            return false;
        }

        if (entry.getStatus() != EntryStatus.DELETED) {
            logger.warn("restoreDeletedEntry: entry {} is not deleted (status={})", entryId, entry.getStatus());
            return false;
        }

        EntryStatus restoreTo = entry.getPreviousStatus() != null
                ? entry.getPreviousStatus()
                : EntryStatus.DRAFT;

        logger.info("restoreDeletedEntry: entryId={}, DELETED → {}", entryId, restoreTo);
        entry.getStatusHistory().add(
                new org.earnlumens.mediastore.domain.media.model.StatusChangeRecord(
                        EntryStatus.DELETED, restoreTo, entry.getAuthorUsername(), null));
        entry.setStatus(restoreTo);
        entry.setPreviousStatus(null);
        entryRepository.save(entry);
        return true;
    }

    /**
     * Aggregated dashboard stats for the owner (counts by status + total views).
     * Delegates to a single MongoDB aggregation pipeline for efficiency.
     */
    public org.earnlumens.mediastore.domain.media.dto.response.OwnerStatsResponse getOwnerStats(
            String tenantId, String userId) {
        java.util.Map<String, Long> raw = entryRepository.getOwnerStats(tenantId, userId);
        long totalSales = orderRepository.countByTenantIdAndSellerIdAndStatus(
                tenantId, userId, OrderStatus.COMPLETED);
        return new org.earnlumens.mediastore.domain.media.dto.response.OwnerStatsResponse(
                raw.getOrDefault("totalEntries", 0L),
                raw.getOrDefault("published", 0L),
                raw.getOrDefault("drafts", 0L),
                raw.getOrDefault("inReview", 0L),
                raw.getOrDefault("rejected", 0L),
                raw.getOrDefault("archived", 0L),
                raw.getOrDefault("deleted", 0L),
                raw.getOrDefault("totalViews", 0L),
                totalSales
        );
    }

    /**
     * Returns the list of completed sales for a seller, with payment split breakdown.
     * Each sale includes the target title (entry or collection), gross amount,
     * splits (with computed XLM amounts), and the Stellar transaction hash
     * for on-chain verification. Collection sales are reported with type COLLECTION.
     */
    public List<org.earnlumens.mediastore.domain.media.dto.response.SellerOrderResponse> getSellerSales(
            String tenantId, String sellerId) {
        var orders = orderRepository.findByTenantIdAndSellerIdAndStatus(
                tenantId, sellerId, OrderStatus.COMPLETED);

        // Batch-load entry titles (legacy orders without targetType are entry sales)
        List<String> entryIds = orders.stream()
                .filter(o -> o.getTargetType() != org.earnlumens.mediastore.domain.media.model.TargetType.COLLECTION)
                .map(o -> o.getEntryId())
                .filter(java.util.Objects::nonNull)
                .distinct().toList();
        java.util.Map<String, Entry> entryMap = new java.util.HashMap<>();
        if (!entryIds.isEmpty()) {
            entryRepository.findByTenantIdAndIdIn(tenantId, entryIds)
                    .forEach(e -> entryMap.put(e.getId(), e));
        }

        // Batch-load collection titles
        List<String> collectionIds = orders.stream()
                .filter(o -> o.getTargetType() == org.earnlumens.mediastore.domain.media.model.TargetType.COLLECTION)
                .map(o -> o.getCollectionId())
                .filter(java.util.Objects::nonNull)
                .distinct().toList();
        java.util.Map<String, org.earnlumens.mediastore.domain.media.model.Collection> collectionMap =
                new java.util.HashMap<>();
        if (!collectionIds.isEmpty()) {
            collectionRepository.findByTenantIdAndIdIn(tenantId, collectionIds)
                    .forEach(c -> collectionMap.put(c.getId(), c));
        }

        return orders.stream().map(order -> {
            boolean isCollection =
                    order.getTargetType() == org.earnlumens.mediastore.domain.media.model.TargetType.COLLECTION;

            String targetId;
            String targetTitle;
            String targetType;
            if (isCollection) {
                var collection = collectionMap.get(order.getCollectionId());
                targetId = order.getCollectionId();
                targetTitle = collection != null ? collection.getTitle() : "—";
                targetType = "COLLECTION";
            } else {
                Entry entry = entryMap.get(order.getEntryId());
                targetId = order.getEntryId();
                targetTitle = entry != null ? entry.getTitle() : "—";
                targetType = entry != null && entry.getType() != null ? entry.getType().name() : "RESOURCE";
            }

            // Build split details with computed XLM amounts
            BigDecimal gross = order.getAmountXlm() != null ? order.getAmountXlm() : BigDecimal.ZERO;
            List<org.earnlumens.mediastore.domain.media.dto.response.SellerOrderResponse.SplitDetail> splits =
                    order.getPaymentSplits().stream().map(s -> {
                        BigDecimal splitXlm = gross.multiply(s.getPercent())
                                .divide(ONE_HUNDRED, 7, java.math.RoundingMode.HALF_UP);
                        return new org.earnlumens.mediastore.domain.media.dto.response.SellerOrderResponse.SplitDetail(
                                s.getWallet(), s.getRole().name(), s.getPercent(), splitXlm);
                    }).toList();

            return new org.earnlumens.mediastore.domain.media.dto.response.SellerOrderResponse(
                    order.getId(), targetId, targetTitle, targetType,
                    gross, order.getStellarTxHash(), order.getCompletedAt(), splits);
        }).toList();
    }

    /**
     * Returns a paginated list of entries owned by the authenticated user,
     * optionally filtered by status and/or type.
     * Ordered by createdAt descending (most recent first).
     */
    public OwnerEntryPageResponse getEntriesByOwner(
            String tenantId, String userId,
            String status, String type,
            int page, int size
    ) {
        Page<Entry> entryPage;
        EntryStatus entryStatus = parseStatus(status);
        EntryType entryType = parseType(type);

        if (entryStatus != null && entryType != null) {
            entryPage = entryRepository.findByTenantIdAndUserIdAndStatusAndType(
                    tenantId, userId, entryStatus, entryType, PageRequest.of(page, size));
        } else if (entryStatus != null) {
            entryPage = entryRepository.findByTenantIdAndUserIdAndStatus(
                    tenantId, userId, entryStatus, PageRequest.of(page, size));
        } else if (entryType != null) {
            // No explicit status → exclude ARCHIVED by default
            entryPage = entryRepository.findByTenantIdAndUserIdAndStatusNotAndType(
                    tenantId, userId, EntryStatus.ARCHIVED, entryType, PageRequest.of(page, size));
        } else {
            // No explicit status → exclude ARCHIVED by default
            entryPage = entryRepository.findByTenantIdAndUserIdAndStatusNot(
                    tenantId, userId, EntryStatus.ARCHIVED, PageRequest.of(page, size));
        }

        List<OwnerEntryResponse> content = entryPage.getContent().stream()
                .map(entry -> {
                    String transcodingStatus = resolveTranscodingStatus(tenantId, entry);
                    return toOwnerResponse(entry, transcodingStatus);
                })
                .toList();

        return new OwnerEntryPageResponse(
                content,
                entryPage.getNumber(),
                entryPage.getSize(),
                entryPage.getTotalElements(),
                entryPage.getTotalPages()
        );
    }

    /**
     * Unified Creator Studio feed — returns entries and collections merged,
     * sorted, filtered and paginated server-side via a single MongoDB
     * {@code $unionWith} aggregation.
     */
    public StudioPageResponse getStudioItems(String tenantId, String userId,
                                              String status, String type, String search,
                                              String sort, int page, int size) {
        int skip = page * size;
        List<org.bson.Document> docs = entryRepository.findStudioItems(
                tenantId, userId, status, type, search, sort, skip, size);
        long total = entryRepository.countStudioItems(tenantId, userId, status, type, search);

        // Batch-resolve transcoding status for video entries in one pass
        Map<String, String> transcodingMap = new HashMap<>();
        for (org.bson.Document doc : docs) {
            if ("entry".equals(doc.getString("kind")) && "VIDEO".equalsIgnoreCase(doc.getString("type"))) {
                String entryId = doc.getObjectId("_id").toHexString();
                transcodingJobService.findLatestByTenantIdAndEntryId(tenantId, entryId)
                        .ifPresent(job -> transcodingMap.put(entryId, job.getStatus().name()));
            }
        }

        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        List<StudioItemResponse> content = docs.stream().map(doc -> {
            String id = doc.getObjectId("_id").toHexString();
            String kind = doc.getString("kind");

            // Date formatting helper
            java.util.function.Function<String, String> fmtDate = field -> {
                Object val = doc.get(field);
                if (val instanceof java.util.Date d) {
                    return d.toInstant().atZone(java.time.ZoneOffset.UTC).format(fmt);
                }
                return val instanceof String s ? s : null;
            };

            return new StudioItemResponse(
                    id,
                    kind,
                    doc.getString("type") != null ? doc.getString("type").toLowerCase() : null,
                    doc.getString("title"),
                    doc.getString("description"),
                    doc.getString("status"),
                    doc.getString("thumbnailR2Key"),
                    doc.getString("coverR2Key"),
                    Boolean.TRUE.equals(doc.getBoolean("isPaid")),
                    toBigDecimal(doc.get("priceXlm")),
                    toBigDecimal(doc.get("priceUsd")),
                    doc.getString("priceCurrency"),
                    doc.getString("contentLanguage"),
                    doc.getInteger("durationSec"),
                    doc.get("viewCount") instanceof Number n ? n.longValue() : 0L,
                    doc.getInteger("itemCount", 0),
                    fmtDate.apply("createdAt"),
                    fmtDate.apply("updatedAt"),
                    fmtDate.apply("publishedAt"),
                    transcodingMap.get(id),
                    doc.getString("sellerWallet"),
                    doc.getString("moderationFeedback"),
                    doc.getString("resourceContent"),
                    doc.getString("thumbnailVariantsPrefix"),
                    doc.getString("previewVariantsPrefix"),
                    doc.getString("coverVariantsPrefix")
            );
        }).toList();

        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 1;
        return new StudioPageResponse(content, page, size, total, totalPages);
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(value.toString()); } catch (NumberFormatException e) { return null; }
    }

    /**
     * Resolves the transcoding status for a video entry.
     * Returns the active job status (PENDING/DISPATCHED/PROCESSING) if one exists,
     * or null for non-video entries and completed transcoding.
     */
    private String resolveTranscodingStatus(String tenantId, Entry entry) {
        if (entry.getType() != EntryType.VIDEO) {
            return null;
        }
        return transcodingJobService.findLatestByTenantIdAndEntryId(tenantId, entry.getId())
                .map(job -> job.getStatus().name())
                .orElse(null);
    }

    /**
     * Returns true when the latest transcoding job for a VIDEO entry is still
     * pending/dispatched/processing. Used to block edits and submit-for-review /
     * publish transitions until the worker finishes, so creators do not mutate
     * the source asset or move the entry forward while output paths are being
     * written under {@code private/media/{tenantId}/{entryId}/hls}.
     */
    private boolean isTranscodingInProgress(String tenantId, Entry entry) {
        if (entry.getType() != EntryType.VIDEO) {
            return false;
        }
        return transcodingJobService.findLatestByTenantIdAndEntryId(tenantId, entry.getId())
                .map(job -> {
                    TranscodingJobStatus s = job.getStatus();
                    return s == TranscodingJobStatus.PENDING
                            || s == TranscodingJobStatus.DISPATCHED
                            || s == TranscodingJobStatus.PROCESSING;
                })
                .orElse(false);
    }

    private OwnerEntryResponse toOwnerResponse(Entry entry, String transcodingStatus) {
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        return new OwnerEntryResponse(
                entry.getId(),
                entry.getType() != null ? entry.getType().name().toLowerCase() : "resource",
                entry.getTitle(),
                entry.getDescription(),
                entry.getStatus() != null ? entry.getStatus().name() : "DRAFT",
                entry.getThumbnailR2Key(),
                entry.getPreviewR2Key(),
                entry.isPaid(),
                entry.getPriceXlm(),
                entry.getPriceUsd(),
                entry.getPriceCurrency() != null ? entry.getPriceCurrency().name() : null,
                entry.getContentLanguage(),
                entry.getDurationSec(),
                entry.getViewCount(),
                entry.getCreatedAt() != null ? entry.getCreatedAt().format(fmt) : null,
                entry.getUpdatedAt() != null ? entry.getUpdatedAt().format(fmt) : null,
                entry.getPublishedAt() != null ? entry.getPublishedAt().format(fmt) : null,
                transcodingStatus,
                entry.getSellerWallet(),
                entry.getModerationFeedback(),
                entry.getThumbnailVariantsPrefix(),
                entry.getPreviewVariantsPrefix()
        );
    }

    private EntryStatus parseStatus(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            return EntryStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private EntryType parseType(String type) {
        if (type == null || type.isBlank()) return null;
        try {
            return EntryType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Parses the priceCurrency string. Defaults to XLM if null/blank (backward compatible).
     */
    private PriceCurrency parsePriceCurrency(String priceCurrency) {
        if (priceCurrency == null || priceCurrency.isBlank()) return PriceCurrency.XLM;
        try {
            return PriceCurrency.valueOf(priceCurrency.toUpperCase());
        } catch (IllegalArgumentException e) {
            return PriceCurrency.XLM;
        }
    }

    private boolean isValidStatusTransition(EntryStatus current, EntryStatus target) {
        // Any status can transition to ARCHIVED (creator can always archive)
        if (target == EntryStatus.ARCHIVED) return true;
        // Any status can transition to DELETED (creator can always soft-delete)
        if (target == EntryStatus.DELETED) return true;

        return switch (current) {
            case DRAFT -> target == EntryStatus.IN_REVIEW;
            case IN_REVIEW -> target == EntryStatus.APPROVED || target == EntryStatus.REJECTED;
            case APPROVED -> target == EntryStatus.PUBLISHED || target == EntryStatus.IN_REVIEW;
            case REJECTED -> target == EntryStatus.DRAFT || target == EntryStatus.IN_REVIEW;
            case PUBLISHED -> target == EntryStatus.UNLISTED || target == EntryStatus.SUSPENDED
                    || target == EntryStatus.IN_REVIEW;
            case UNLISTED -> target == EntryStatus.PUBLISHED || target == EntryStatus.SUSPENDED;
            case SUSPENDED -> target == EntryStatus.PUBLISHED || target == EntryStatus.DRAFT;
            case ARCHIVED -> target == EntryStatus.DRAFT;
            case DELETED -> target == EntryStatus.DRAFT;
        };
    }

    /**
     * Validates that an uploaded file's declared content type and extension
     * are acceptable for the given entry type and upload kind.
     *
     * <p>Three checks, fail-fast:
     * <ol>
     *   <li>The file extension is not on the global blocklist (executables,
     *       scripts, installers — dangerous even if the content type is faked).</li>
     *   <li>The content type is not on the global blocklist.</li>
     *   <li>The content type is allowed for the (entryType, kind) pair.</li>
     * </ol>
     *
     * @throws IllegalArgumentException with code {@code DANGEROUS_FILE_TYPE}
     *         or {@code UNSUPPORTED_FILE_TYPE} when the file is rejected.
     */
    private void validateUploadType(EntryType entryType, MediaKind kind, String contentType, String fileName) {
        String ct = contentType == null ? "" : contentType.trim().toLowerCase();
        String ext = extractExtension(fileName);

        // 1 & 2 — global blocklists (apply to every kind and entry type)
        if (ext != null && BLOCKED_EXTENSIONS.contains(ext)) {
            logger.warn("validateUploadType: blocked dangerous extension '{}' (file={})", ext, fileName);
            throw new IllegalArgumentException("DANGEROUS_FILE_TYPE");
        }
        if (BLOCKED_CONTENT_TYPES.contains(ct)) {
            logger.warn("validateUploadType: blocked dangerous content type '{}'", ct);
            throw new IllegalArgumentException("DANGEROUS_FILE_TYPE");
        }

        // 3 — per (kind, entryType) allowlist
        // THUMBNAIL / PREVIEW are always cover images regardless of entry type.
        if (kind == MediaKind.THUMBNAIL || kind == MediaKind.PREVIEW) {
            if (!ct.startsWith("image/")) {
                logger.warn("validateUploadType: {} must be an image, got '{}'", kind, ct);
                throw new IllegalArgumentException("UNSUPPORTED_FILE_TYPE");
            }
            return;
        }

        // FULL asset — allowed types depend on the entry type.
        boolean allowed = switch (entryType) {
            case VIDEO -> ct.startsWith("video/");
            case AUDIO -> ct.startsWith("audio/");
            case IMAGE -> ct.startsWith("image/");
            // RESOURCE attachments are the broadest: media, documents, ebooks
            // and archives are all valid downloadable resources. Anything on
            // the global blocklists above has already been rejected.
            case RESOURCE -> ct.startsWith("image/")
                    || ct.startsWith("video/")
                    || ct.startsWith("audio/")
                    || ct.startsWith("text/")
                    || RESOURCE_DOCUMENT_CONTENT_TYPES.contains(ct);
            // COLLECTION entries never carry a FULL asset.
            case COLLECTION -> false;
        };

        if (!allowed) {
            logger.warn("validateUploadType: content type '{}' not allowed for {} FULL asset", ct, entryType);
            throw new IllegalArgumentException("UNSUPPORTED_FILE_TYPE");
        }
    }

    /**
     * Returns the lowercase extension of a file name (without the dot), or
     * {@code null} when there is none.
     */
    private String extractExtension(String fileName) {
        if (fileName == null) return null;
        String name = fileName.replace("\\", "/");
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        return name.substring(dot + 1).trim().toLowerCase();
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "file";
        }
        // Remove path separators and keep only the base name
        String name = fileName.replace("\\", "/");
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        // Replace whitespace with hyphens, remove non-safe characters
        name = name.replaceAll("\\s+", "-")
                    .replaceAll("[^a-zA-Z0-9._-]", "");
        return name.isEmpty() ? "file" : name;
    }
}
