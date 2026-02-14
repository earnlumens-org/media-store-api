package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.response.MediaEntitlementResponse;
import org.earnlumens.mediastore.domain.media.model.Asset;
import org.earnlumens.mediastore.domain.media.model.AssetStatus;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;
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
     * Checks whether a user is entitled to access a private media entry.
     * Access is granted if the user is the content owner OR has an ACTIVE entitlement.
     * The FULL asset's r2Key is resolved from the assets collection.
     *
     * @param tenantId the tenant identifier
     * @param userId   the requesting user's OAuth user ID (JWT subject)
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

        // Owner always has access
        if (userId.equals(entry.getUserId())) {
            logger.debug("Access granted (owner): userId={}, entryId={}", userId, entryId);
            return buildAssetResponse(tenantId, entryId);
        }

        // Check active entitlement
        boolean entitled = entitlementRepository
                .existsByTenantIdAndUserIdAndEntryIdAndStatus(tenantId, userId, entryId, EntitlementStatus.ACTIVE);

        if (!entitled) {
            logger.debug("Access denied: tenantId={}, userId={}, entryId={}", tenantId, userId, entryId);
            return Optional.empty();
        }

        logger.debug("Access granted (entitlement): userId={}, entryId={}", userId, entryId);
        return buildAssetResponse(tenantId, entryId);
    }

    private Optional<MediaEntitlementResponse> buildAssetResponse(String tenantId, String entryId) {
        Optional<Asset> optAsset = assetRepository
                .findByTenantIdAndEntryIdAndKindAndStatus(tenantId, entryId, MediaKind.FULL, AssetStatus.READY);

        if (optAsset.isEmpty()) {
            logger.warn("No READY FULL asset found: tenantId={}, entryId={}", tenantId, entryId);
            return Optional.empty();
        }

        Asset asset = optAsset.get();
        return Optional.of(new MediaEntitlementResponse(
                true,
                asset.getR2Key(),
                asset.getContentType(),
                computeContentDisposition(asset),
                asset.getFileName()
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
