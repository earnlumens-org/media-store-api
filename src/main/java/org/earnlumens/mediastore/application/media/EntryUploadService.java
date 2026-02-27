package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.request.CreateEntryRequest;
import org.earnlumens.mediastore.domain.media.dto.request.FinalizeUploadRequest;
import org.earnlumens.mediastore.domain.media.dto.request.InitUploadRequest;
import org.earnlumens.mediastore.domain.media.dto.request.UpdateEntryStatusRequest;
import org.earnlumens.mediastore.domain.media.dto.response.CreateEntryResponse;
import org.earnlumens.mediastore.domain.media.dto.response.FinalizeUploadResponse;
import org.earnlumens.mediastore.domain.media.dto.response.InitUploadResponse;
import org.earnlumens.mediastore.domain.media.model.Asset;
import org.earnlumens.mediastore.domain.media.model.AssetStatus;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.MediaKind;
import org.earnlumens.mediastore.domain.media.model.MediaVisibility;
import org.earnlumens.mediastore.domain.media.model.PaymentSplit;
import org.earnlumens.mediastore.domain.media.model.SplitRole;
import org.earnlumens.mediastore.domain.media.repository.AssetRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.user.repository.UserRepository;
import org.earnlumens.mediastore.infrastructure.config.PlatformConfig;
import org.earnlumens.mediastore.infrastructure.r2.R2PresignedUrlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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

    private final EntryRepository entryRepository;
    private final AssetRepository assetRepository;
    private final UserRepository userRepository;
    private final R2PresignedUrlService r2PresignedUrlService;
    private final PlatformConfig platformConfig;

    public EntryUploadService(
            EntryRepository entryRepository,
            AssetRepository assetRepository,
            UserRepository userRepository,
            R2PresignedUrlService r2PresignedUrlService,
            PlatformConfig platformConfig
    ) {
        this.entryRepository = entryRepository;
        this.assetRepository = assetRepository;
        this.userRepository = userRepository;
        this.r2PresignedUrlService = r2PresignedUrlService;
        this.platformConfig = platformConfig;
    }

    /**
     * Creates a new DRAFT entry owned by the given user.
     * <p>
     * For paid content, a valid Stellar wallet (sellerWallet) is required.
     * The backend auto-generates the default payment splits:
     *   - PLATFORM: platform.fee-percent (from config)
     *   - SELLER: 100 - platform.fee-percent
     * <p>
     * Future: sellers will be able to add COLLABORATOR splits manually.
     */
    public CreateEntryResponse createEntry(String tenantId, String userId, CreateEntryRequest request) {
        EntryType entryType = EntryType.valueOf(request.type());
        boolean isPaid = Boolean.TRUE.equals(request.isPaid());

        // Validate wallet requirement for paid content
        if (isPaid) {
            if (request.sellerWallet() == null || request.sellerWallet().isBlank()) {
                throw new IllegalArgumentException("A connected wallet is required for paid content");
            }
            if (!STELLAR_PUBLIC_KEY.matcher(request.sellerWallet()).matches()) {
                throw new IllegalArgumentException("Invalid Stellar public key");
            }
            if (request.priceXlm() == null || request.priceXlm().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Price must be greater than 0 for paid content");
            }
            if (request.sellerWallet().equals(platformConfig.getWallet())) {
                throw new IllegalArgumentException("Seller wallet cannot be the platform wallet");
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

        // Set seller wallet and generate payment splits for paid content
        if (isPaid) {
            entry.setSellerWallet(request.sellerWallet());
            entry.setPaymentSplits(buildDefaultSplits(request.sellerWallet()));
            logger.info("Paid entry: sellerWallet={}, splits={}", request.sellerWallet(),
                    entry.getPaymentSplits().size());
        }

        // Denormalize author info for fast reads (no user join at query time)
        // userId is the OAuth provider ID (e.g. Google ID), not MongoDB _id
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
     * Builds the default payment splits: PLATFORM + SELLER.
     * Uses BigDecimal to guarantee exact decimal arithmetic.
     *
     * @param sellerWallet the seller's Stellar public key
     * @return list with exactly 2 splits summing to 100.00%
     */
    private List<PaymentSplit> buildDefaultSplits(String sellerWallet) {
        BigDecimal platformPercent = platformConfig.getFeePercent();
        BigDecimal sellerPercent = ONE_HUNDRED.subtract(platformPercent);

        List<PaymentSplit> splits = new ArrayList<>();
        splits.add(new PaymentSplit(platformConfig.getWallet(), SplitRole.PLATFORM, platformPercent));
        splits.add(new PaymentSplit(sellerWallet, SplitRole.SELLER, sellerPercent));
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

        // THUMBNAIL and PREVIEW go under public/ prefix so CDN Worker serves them without auth.
        // FULL (paid content) stays under private/ — served via /media/<entryId> with entitlement check.
        String prefix = (kind == MediaKind.THUMBNAIL || kind == MediaKind.PREVIEW) ? "public" : "private";
        String r2Key = String.format("%s/media/%s/%s/%s-%s",
                prefix,
                request.entryId(),
                kind.name().toLowerCase(),
                UUID.randomUUID(),
                sanitizedFileName);

        String presignedUrl = r2PresignedUrlService.generatePresignedPutUrl(r2Key, request.contentType());
        String uploadId = UUID.randomUUID().toString();

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

        Asset asset = new Asset();
        asset.setTenantId(tenantId);
        asset.setEntryId(request.entryId());
        asset.setR2Key(request.r2Key());
        asset.setContentType(request.contentType());
        asset.setFileName(request.fileName());
        asset.setFileSizeBytes(request.fileSizeBytes());
        asset.setKind(kind);
        // No transcoding pipeline yet — files are uploaded directly to R2 and ready to serve.
        // When HLS transcoding is added, FULL assets should start as UPLOADED and transition
        // to READY via a webhook/callback after processing completes.
        asset.setStatus(AssetStatus.READY);

        Asset saved = assetRepository.save(asset);

        // If a THUMBNAIL was finalized, denormalize its R2 key onto the entry for fast reads
        if (kind == MediaKind.THUMBNAIL) {
            entry.setThumbnailR2Key(request.r2Key());
            entryRepository.save(entry);
            logger.info("finalizeUpload: set thumbnailR2Key on entry {}: {}", request.entryId(), request.r2Key());
        }
        // If a PREVIEW was finalized, denormalize its R2 key onto the entry
        if (kind == MediaKind.PREVIEW) {
            entry.setPreviewR2Key(request.r2Key());
            entryRepository.save(entry);
            logger.info("finalizeUpload: set previewR2Key on entry {}: {}", request.entryId(), request.r2Key());
        }

        logger.info("finalizeUpload: assetId={}, entryId={}, kind={}, r2Key={}",
                saved.getId(), request.entryId(), kind, request.r2Key());

        return Optional.of(new FinalizeUploadResponse(saved.getId(), saved.getStatus().name()));
    }

    /**
     * Updates the entry status. Currently only supports DRAFT → IN_REVIEW.
     *
     * @return true if the status was updated, false if entry not found, not owned, or invalid transition
     */
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

        entry.setStatus(newStatus);
        entryRepository.save(entry);

        logger.info("updateEntryStatus: entryId={}, {} → {}", entryId, entry.getStatus(), newStatus);
        return true;
    }

    private boolean isValidStatusTransition(EntryStatus current, EntryStatus target) {
        return switch (current) {
            case DRAFT -> target == EntryStatus.IN_REVIEW;
            case IN_REVIEW -> target == EntryStatus.APPROVED || target == EntryStatus.REJECTED;
            case APPROVED -> target == EntryStatus.PUBLISHED;
            case REJECTED -> target == EntryStatus.DRAFT;
            case PUBLISHED -> false;
        };
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
