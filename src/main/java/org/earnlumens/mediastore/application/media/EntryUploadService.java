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
import org.earnlumens.mediastore.domain.media.repository.AssetRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.media.repository.OrderRepository;
import org.earnlumens.mediastore.domain.user.repository.UserRepository;
import org.earnlumens.mediastore.application.user.UserBadgeService;
import org.earnlumens.mediastore.infrastructure.config.PlatformConfig;
import org.earnlumens.mediastore.infrastructure.r2.R2PresignedUrlService;
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

    private final EntryRepository entryRepository;
    private final AssetRepository assetRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final R2PresignedUrlService r2PresignedUrlService;
    private final PlatformConfig platformConfig;
    private final TranscodingJobService transcodingJobService;
    private final ModerationJobService moderationJobService;
    private final UserBadgeService userBadgeService;
    private final int dailyEntryLimit;
    private final int maxConcurrentReview;

    public EntryUploadService(
            EntryRepository entryRepository,
            AssetRepository assetRepository,
            UserRepository userRepository,
            OrderRepository orderRepository,
            R2PresignedUrlService r2PresignedUrlService,
            PlatformConfig platformConfig,
            TranscodingJobService transcodingJobService,
            ModerationJobService moderationJobService,
            UserBadgeService userBadgeService,
            @Value("${mediastore.abuse.daily-entry-limit:20}") int dailyEntryLimit,
            @Value("${mediastore.abuse.max-concurrent-review:10}") int maxConcurrentReview
    ) {
        this.entryRepository = entryRepository;
        this.assetRepository = assetRepository;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.r2PresignedUrlService = r2PresignedUrlService;
        this.platformConfig = platformConfig;
        this.transcodingJobService = transcodingJobService;
        this.moderationJobService = moderationJobService;
        this.userBadgeService = userBadgeService;
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

        // NOTE: Transcoding for VIDEO assets is deferred until AFTER moderation approval.
        // ModerationJobService.createTranscodingJobForEntry() handles this.

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

        // ── Re-moderation: visual asset change on a non-draft entry triggers re-review ──
        // This must also handle entries already IN_REVIEW (e.g. from updateEntryMetadata)
        // because the existing moderation job has a stale thumbnailR2Key.
        if (kind == MediaKind.THUMBNAIL || kind == MediaKind.PREVIEW) {
            boolean needsReReview = entry.getStatus() != EntryStatus.DRAFT
                    && entry.getStatus() != EntryStatus.ARCHIVED;
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
                saved.getId(), request.entryId(), kind, request.r2Key(),
                request.widthPx(), request.heightPx(), request.durationSec());

        return Optional.of(new FinalizeUploadResponse(saved.getId(), saved.getStatus().name()));
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

        if (request.contentLanguage() != null) {
            entry.setContentLanguage(request.contentLanguage());
        }

        if (request.resourceContent() != null) {
            entry.setResourceContent(request.resourceContent());
        }

        // ── Auto-moderation: any edit on a non-DRAFT entry triggers re-review ──
        boolean requiresReReview = entry.getStatus() != EntryStatus.DRAFT
                && entry.getStatus() != EntryStatus.IN_REVIEW
                && entry.getStatus() != EntryStatus.ARCHIVED;

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

        // When archiving, remember the current status so we can restore it later
        if (newStatus == EntryStatus.ARCHIVED) {
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
                raw.getOrDefault("totalViews", 0L),
                totalSales
        );
    }

    /**
     * Returns the list of completed sales for a seller, with payment split breakdown.
     * Each sale includes the entry title, gross amount, splits (with computed XLM amounts),
     * and the Stellar transaction hash for on-chain verification.
     */
    public List<org.earnlumens.mediastore.domain.media.dto.response.SellerOrderResponse> getSellerSales(
            String tenantId, String sellerId) {
        var orders = orderRepository.findByTenantIdAndSellerIdAndStatus(
                tenantId, sellerId, OrderStatus.COMPLETED);

        // Batch-load entry titles
        List<String> entryIds = orders.stream().map(o -> o.getEntryId()).distinct().toList();
        java.util.Map<String, Entry> entryMap = new java.util.HashMap<>();
        if (!entryIds.isEmpty()) {
            entryRepository.findByTenantIdAndIdIn(tenantId, entryIds)
                    .forEach(e -> entryMap.put(e.getId(), e));
        }

        return orders.stream().map(order -> {
            // Look up entry title
            Entry entry = entryMap.get(order.getEntryId());
            String entryTitle = entry != null ? entry.getTitle() : "—";
            String entryType = entry != null && entry.getType() != null ? entry.getType().name() : "RESOURCE";

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
                    order.getId(), order.getEntryId(), entryTitle, entryType,
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
                    doc.getString("resourceContent")
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
                entry.getModerationFeedback()
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
