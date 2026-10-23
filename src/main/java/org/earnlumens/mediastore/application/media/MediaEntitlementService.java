package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.response.MediaEntitlementResponse;
import org.earnlumens.mediastore.domain.media.model.Asset;
import org.earnlumens.mediastore.domain.media.model.AssetStatus;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.MediaKind;
import org.earnlumens.mediastore.domain.media.repository.AssetRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class MediaEntitlementService {

    private static final Logger logger = LoggerFactory.getLogger(MediaEntitlementService.class);

    /** Outcome of a CDN entitlement check: the decision plus, when allowed, the asset to serve. */
    public record Check(AccessDecision decision, Optional<MediaEntitlementResponse> response) {
        static Check denied(AccessDecision decision) { return new Check(decision, Optional.empty()); }
    }

    private final EntryRepository entryRepository;
    private final AssetRepository assetRepository;
    private final ContentAccessPolicy accessPolicy;

    public MediaEntitlementService(EntryRepository entryRepository,
                                   AssetRepository assetRepository,
                                   ContentAccessPolicy accessPolicy) {
        this.entryRepository = entryRepository;
        this.assetRepository = assetRepository;
        this.accessPolicy = accessPolicy;
    }

    /**
     * Checks whether a user is entitled to access a media entry's full asset.
     * <p>
     * The decision is delegated to {@link ContentAccessPolicy} — the same
     * rule the public detail page applies (H3: the CDN is never more
     * permissive than the UI). In short: a moderation block denies everyone;
     * the owner always passes; free content is served only while publicly
     * visible (D-19); paid content needs an ACTIVE entitlement backed by a
     * COMPLETED order, in any status the creator or a review can set.
     *
     * @param tenantId the tenant identifier
     * @param userId   the requesting user's OAuth user ID (JWT subject), or {@code null} if unauthenticated
     * @param entryId  the entry identifier
     * @return the entitlement response if allowed, empty otherwise
     */
    public Optional<MediaEntitlementResponse> checkEntitlement(String tenantId, String userId, String entryId) {
        return check(tenantId, userId, entryId).response();
    }

    /** Same as {@link #checkEntitlement} but also exposes the denial reason. */
    public Check check(String tenantId, String userId, String entryId) {
        Optional<Entry> optEntry = entryRepository.findByTenantIdAndId(tenantId, entryId);
        if (optEntry.isEmpty()) {
            logger.debug("Entry not found: tenantId={}, entryId={}", tenantId, entryId);
            return Check.denied(AccessDecision.DENIED_HIDDEN);
        }

        Entry entry = optEntry.get();
        ContentAccessPolicy.Result result = accessPolicy.evaluate(tenantId, entry, userId);
        if (!result.allowed()) {
            logger.debug("Access denied ({}): tenantId={}, userId={}, entryId={}, status={}",
                    result.decision(), tenantId, userId, entryId, entry.getStatus());
            return Check.denied(result.decision());
        }

        logger.debug("Access granted ({}): userId={}, entryId={}",
                result.owner() ? "owner" : result.entitled() ? "entitlement" : "public", userId, entryId);
        Optional<MediaEntitlementResponse> response = buildAssetResponse(tenantId, entry);
        return new Check(response.isPresent() ? AccessDecision.ALLOWED : AccessDecision.DENIED_HIDDEN, response);
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
