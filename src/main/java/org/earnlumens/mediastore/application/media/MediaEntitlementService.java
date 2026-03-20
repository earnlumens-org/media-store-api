package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.response.MediaEntitlementResponse;
import org.earnlumens.mediastore.domain.media.model.Asset;
import org.earnlumens.mediastore.domain.media.model.AssetStatus;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.MediaKind;
import org.earnlumens.mediastore.domain.media.repository.AssetRepository;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class MediaEntitlementService {

    private static final Logger logger = LoggerFactory.getLogger(MediaEntitlementService.class);

    private final EntryRepository entryRepository;
    private final EntitlementRepository entitlementRepository;
    private final AssetRepository assetRepository;

    public MediaEntitlementService(EntryRepository entryRepository,
                                   EntitlementRepository entitlementRepository,
                                   AssetRepository assetRepository) {
        this.entryRepository = entryRepository;
        this.entitlementRepository = entitlementRepository;
        this.assetRepository = assetRepository;
    }

    /**
     * Checks whether a user is entitled to access a media entry's full asset.
     * <p>
     * Access rules (evaluated in order):
     * <ol>
     *   <li>Free content ({@code isPaid = false}) is accessible to anyone (including unauthenticated)</li>
     *   <li>Owner always has access</li>
     *   <li>Paid content requires an ACTIVE entitlement (purchase)</li>
     * </ol>
     *
     * @param tenantId the tenant identifier
     * @param userId   the requesting user's OAuth user ID (JWT subject), or {@code null} if unauthenticated
     * @param entryId  the entry identifier
     * @return the entitlement response if allowed, empty otherwise
     */
    public Optional<MediaEntitlementResponse> checkEntitlement(String tenantId, String userId, String entryId) {
        Optional<Entry> optEntry = entryRepository.findByTenantIdAndId(tenantId, entryId);
        if (optEntry.isEmpty()) {
            logger.debug("Entry not found: tenantId={}, entryId={}", tenantId, entryId);
            return Optional.empty();
        }

        Entry entry = optEntry.get();

        // Free content is accessible to anyone (including unauthenticated users)
        if (!entry.isPaid()) {
            logger.debug("Access granted (free content): userId={}, entryId={}", userId, entryId);
            return buildAssetResponse(tenantId, entry);
        }

        // Paid content requires authentication
        if (userId == null) {
            logger.debug("Access denied (unauthenticated, paid content): entryId={}", entryId);
            return Optional.empty();
        }

        // Owner always has access
        if (userId.equals(entry.getUserId())) {
            logger.debug("Access granted (owner): userId={}, entryId={}", userId, entryId);
            return buildAssetResponse(tenantId, entry);
        }

        // Paid content requires an active entitlement (purchase)
        boolean entitled = entitlementRepository
                .existsByTenantIdAndUserIdAndEntryIdAndStatus(tenantId, userId, entryId, EntitlementStatus.ACTIVE);

        if (!entitled) {
            logger.debug("Access denied (no entitlement): tenantId={}, userId={}, entryId={}", tenantId, userId, entryId);
            return Optional.empty();
        }

        logger.debug("Access granted (entitlement): userId={}, entryId={}", userId, entryId);
        return buildAssetResponse(tenantId, entry);
    }

    private Optional<MediaEntitlementResponse> buildAssetResponse(String tenantId, Entry entry) {
        String entryId = entry.getId();
        Optional<Asset> optAsset = assetRepository
                .findByTenantIdAndEntryIdAndKindAndStatus(tenantId, entryId, MediaKind.FULL, AssetStatus.READY);

        if (optAsset.isEmpty()) {
            // RESOURCE entries can be text-only (no file upload).
            // Grant access so the frontend can render resourceContent.
            if (entry.getType() == EntryType.RESOURCE) {
                logger.debug("Text-only RESOURCE entry, granting access: tenantId={}, entryId={}", tenantId, entryId);
                return Optional.of(new MediaEntitlementResponse(true, null, null, null, null, null));
            }
            logger.warn("No READY FULL asset found: tenantId={}, entryId={}", tenantId, entryId);
            return Optional.empty();
        }

        Asset asset = optAsset.get();

        // Resolve HLS R2 prefix: stored on entry for new transcodes,
        // computed for legacy entries that have hlsReady but no stored prefix
        String hlsPrefix = null;
        if (entry.isHlsReady()) {
            hlsPrefix = entry.getHlsR2Prefix() != null
                    ? entry.getHlsR2Prefix()
                    : "public/media/" + entryId + "/hls";
        }

        return Optional.of(new MediaEntitlementResponse(
                true,
                asset.getR2Key(),
                asset.getContentType(),
                computeContentDisposition(asset),
                asset.getFileName(),
                hlsPrefix
        ));
    }

    private String computeContentDisposition(Asset asset) {
        String ct = asset.getContentType();
        String fn = asset.getFileName();

        boolean inline = ct != null && (
                ct.startsWith("image/") ||
                ct.startsWith("video/") ||
                ct.startsWith("audio/") ||
                "application/pdf".equals(ct)
        );

        String disposition = inline ? "inline" : "attachment";
        if (fn != null && !fn.isBlank()) {
            String safeName = fn.replace("\"", "\\\"");
            disposition += "; filename=\"" + safeName + "\"";
        }
        return disposition;
    }
}
