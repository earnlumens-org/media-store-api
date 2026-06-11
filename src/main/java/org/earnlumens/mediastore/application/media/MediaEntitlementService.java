package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.response.MediaEntitlementResponse;
import org.earnlumens.mediastore.domain.media.model.Asset;
import org.earnlumens.mediastore.domain.media.model.AssetStatus;
import org.earnlumens.mediastore.domain.media.model.Collection;
import org.earnlumens.mediastore.domain.media.model.CollectionStatus;
import org.earnlumens.mediastore.domain.media.model.Entitlement;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.GrantType;
import org.earnlumens.mediastore.domain.media.model.MediaKind;
import org.earnlumens.mediastore.domain.media.model.Order;
import org.earnlumens.mediastore.domain.media.model.OrderStatus;
import org.earnlumens.mediastore.domain.media.repository.AssetRepository;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.media.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class MediaEntitlementService {

    private static final Logger logger = LoggerFactory.getLogger(MediaEntitlementService.class);

    private final EntryRepository entryRepository;
    private final EntitlementRepository entitlementRepository;
    private final AssetRepository assetRepository;
    private final CollectionRepository collectionRepository;
    private final OrderRepository orderRepository;

    public MediaEntitlementService(EntryRepository entryRepository,
                                   EntitlementRepository entitlementRepository,
                                   AssetRepository assetRepository,
                                   CollectionRepository collectionRepository,
                                   OrderRepository orderRepository) {
        this.entryRepository = entryRepository;
        this.entitlementRepository = entitlementRepository;
        this.assetRepository = assetRepository;
        this.collectionRepository = collectionRepository;
        this.orderRepository = orderRepository;
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

        // Paid content requires an active entitlement (purchase), and the
        // entitlement itself must be backed by a server-confirmed COMPLETED order.
        Optional<Entitlement> entryEntitlement = entitlementRepository
                .findByTenantIdAndUserIdAndEntryIdAndStatus(tenantId, userId, entryId, EntitlementStatus.ACTIVE);

        if (entryEntitlement.isPresent() && isBackedByCompletedOrder(entryEntitlement.get())) {
            logger.debug("Access granted (entry entitlement): userId={}, entryId={}", userId, entryId);
            return buildAssetResponse(tenantId, entry);
        }

        // Check collection-level access: find PUBLISHED collections containing this entry,
        // then check if the user has an ACTIVE collection entitlement for any of them.
        List<Collection> parentCollections = collectionRepository
                .findByTenantIdAndStatusAndItemsEntryId(tenantId, CollectionStatus.PUBLISHED, entryId);

        if (!parentCollections.isEmpty()) {
            List<String> collIds = parentCollections.stream()
                    .filter(Collection::isPaid)
                    .map(Collection::getId)
                    .toList();

            if (!collIds.isEmpty()) {
                List<Entitlement> collEntitlements = entitlementRepository
                        .findByTenantIdAndUserIdAndCollectionIdsAndStatus(
                                tenantId, userId, collIds, EntitlementStatus.ACTIVE);

                boolean entitledViaCollection = collEntitlements.stream()
                        .anyMatch(this::isBackedByCompletedOrder);

                if (entitledViaCollection) {
                    logger.debug("Access granted (collection entitlement): userId={}, entryId={}",
                            userId, entryId);
                    return buildAssetResponse(tenantId, entry);
                }
            }
        }

        logger.debug("Access denied (no entitlement): tenantId={}, userId={}, entryId={}", tenantId, userId, entryId);
        return Optional.empty();
    }

    /**
     * Defense in depth for unlock endpoints: a PURCHASE entitlement only grants
     * access if its backing order is COMPLETED with an on-chain tx hash recorded.
     * An entitlement that somehow exists without a confirmed payment never
     * releases content. Non-purchase grants (e.g. promotional) pass through.
     */
    private boolean isBackedByCompletedOrder(Entitlement entitlement) {
        if (entitlement.getGrantType() != GrantType.PURCHASE) {
            return true;
        }
        if (entitlement.getOrderId() == null) {
            logger.warn("PURCHASE entitlement without orderId — denying access: entitlementId={}",
                    entitlement.getId());
            return false;
        }
        Optional<Order> order = orderRepository.findByTenantIdAndId(
                entitlement.getTenantId(), entitlement.getOrderId());
        boolean valid = order.isPresent()
                && order.get().getStatus() == OrderStatus.COMPLETED
                && order.get().getStellarTxHash() != null;
        if (!valid) {
            logger.warn("PURCHASE entitlement not backed by a confirmed COMPLETED order — denying access: "
                            + "entitlementId={}, orderId={}, orderStatus={}",
                    entitlement.getId(), entitlement.getOrderId(),
                    order.map(o -> o.getStatus().name()).orElse("MISSING"));
        }
        return valid;
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
