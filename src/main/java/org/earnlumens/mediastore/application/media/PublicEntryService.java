package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.response.AssetInfo;
import org.earnlumens.mediastore.domain.media.dto.response.PublicEntryPageResponse;
import org.earnlumens.mediastore.domain.media.dto.response.PublicEntryResponse;
import org.earnlumens.mediastore.domain.media.dto.response.PublicFeedItemResponse;
import org.earnlumens.mediastore.domain.media.dto.response.PublicFeedPageResponse;
import org.earnlumens.mediastore.domain.media.model.AssetStatus;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.MediaKind;
import org.earnlumens.mediastore.domain.media.repository.AssetRepository;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Service for public (unauthenticated) entry queries.
 * Returns PUBLISHED entries with denormalized author info — no user join needed.
 */
@Service
public class PublicEntryService {

    private final EntryRepository entryRepository;
    private final AssetRepository assetRepository;
    private final EntitlementRepository entitlementRepository;
    private final CollectionRepository collectionRepository;

    public PublicEntryService(EntryRepository entryRepository, AssetRepository assetRepository,
                              EntitlementRepository entitlementRepository,
                              CollectionRepository collectionRepository) {
        this.entryRepository = entryRepository;
        this.assetRepository = assetRepository;
        this.entitlementRepository = entitlementRepository;
        this.collectionRepository = collectionRepository;
    }

    /**
     * Returns a single PUBLISHED entry by ID for the given tenant.
     * Returns empty if the entry doesn't exist or is not published.
     */
    public Optional<PublicEntryResponse> getPublishedEntryById(String tenantId, String entryId) {
        return entryRepository.findByTenantIdAndId(tenantId, entryId)
                .filter(entry -> entry.getStatus() == EntryStatus.PUBLISHED || entry.getStatus() == EntryStatus.UNLISTED)
                .map(entry -> {
                    // Atomically increment view count (fire-and-forget)
                    entryRepository.incrementViewCount(tenantId, entryId);

                    // For detail view: include FULL asset metadata if available
                    AssetInfo assetInfo = assetRepository
                            .findByTenantIdAndEntryIdAndKindAndStatus(tenantId, entryId, MediaKind.FULL, AssetStatus.READY)
                            .map(asset -> new AssetInfo(asset.getFileName(), asset.getFileSizeBytes(), asset.getContentType()))
                            .orElse(null);
                    return toPublicResponse(entry, assetInfo);
                });
    }

    /**
     * Returns a paginated list of PUBLISHED entries for the given tenant,
     * ordered by publishedAt descending (most recent first).
     * All author info is denormalized on the entry, so this is a single-query operation.
     */
    public PublicEntryPageResponse getPublishedEntries(String tenantId, int page, int size) {
        Page<Entry> entryPage = entryRepository.findByTenantIdAndStatus(
                tenantId, EntryStatus.PUBLISHED, PageRequest.of(page, size));
        return toPageResponse(entryPage);
    }

    /**
     * Returns a paginated list of PUBLISHED entries for a specific author (by username),
     * optionally filtered by entry type.
     */
    public PublicEntryPageResponse getPublishedEntriesByUser(String tenantId, String username, String type, int page, int size) {
        Page<Entry> entryPage;

        if (type != null && !type.isBlank()) {
            EntryType entryType = parseEntryType(type);
            if (entryType == null) {
                return new PublicEntryPageResponse(List.of(), page, size, 0, 0);
            }
            entryPage = entryRepository.findByTenantIdAndAuthorUsernameAndStatusAndType(
                    tenantId, username, EntryStatus.PUBLISHED, entryType, PageRequest.of(page, size));
        } else {
            entryPage = entryRepository.findByTenantIdAndAuthorUsernameAndStatus(
                    tenantId, username, EntryStatus.PUBLISHED, PageRequest.of(page, size));
        }

        return toPageResponse(entryPage);
    }

    private PublicEntryPageResponse toPageResponse(Page<Entry> entryPage) {
        List<PublicEntryResponse> content = entryPage.getContent().stream()
                .map(entry -> toPublicResponse(entry, null))
                .toList();

        return new PublicEntryPageResponse(
                content,
                entryPage.getNumber(),
                entryPage.getSize(),
                entryPage.getTotalElements(),
                entryPage.getTotalPages()
        );
    }

    private PublicEntryResponse toPublicResponse(Entry entry, AssetInfo assetInfo) {
        String publishedAt = entry.getPublishedAt() != null
                ? entry.getPublishedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                : null;

        // HLS is ready when the transcoding pipeline has completed for this entry
        boolean hlsReady = entry.isHlsReady();

        // resourceContent is returned for all entries; access control is enforced
        // by the frontend (entitlement check) and the CDN worker (media downloads).
        String resourceContent = entry.getResourceContent();

        return new PublicEntryResponse(
                entry.getId(),
                mapType(entry.getType()),
                entry.getTitle(),
                entry.getDescription(),
                resourceContent,
                entry.getUserId(),
                entry.getAuthorUsername() != null ? entry.getAuthorUsername() : entry.getUserId(),
                entry.getAuthorAvatarUrl(),
                publishedAt,
                entry.getThumbnailR2Key(),
                entry.getPreviewR2Key(),
                entry.getDurationSec(),
                entry.getViewCount(),
                entry.isPaid(),
                entry.getPriceXlm(),
                entry.getPriceUsd(),
                entry.getPriceCurrency() != null ? entry.getPriceCurrency().name() : null,
                entry.getContentLanguage(),
                entry.getTags(),
                assetInfo,
                hlsReady
        );
    }

    /**
     * Maps backend EntryType to the lowercase string the UI expects.
     */
    private String mapType(EntryType type) {
        if (type == null) return "resource";
        return switch (type) {
            case VIDEO -> "video";
            case AUDIO -> "audio";
            case IMAGE -> "image";
            case RESOURCE -> "resource";
        };
    }

    /**
     * Parses a UI type string back to EntryType.
     * Returns null if the type is unknown.
     */
    private EntryType parseEntryType(String type) {
        return switch (type.toLowerCase()) {
            case "video" -> EntryType.VIDEO;
            case "audio" -> EntryType.AUDIO;
            case "image" -> EntryType.IMAGE;
            case "resource" -> EntryType.RESOURCE;
            default -> null;
        };
    }

    // ── Unified profile feed (entries + collections via $unionWith) ────────

    /**
     * Returns a unified, paginated feed of ALL entries + collections for the explore page.
     * No entitlement checks — locked/unlocked is resolved client-side via purchasesStore.
     */
    public PublicFeedPageResponse getExploreFeed(String tenantId, String type, String sort,
                                                  int page, int size) {
        int skip = page * size;
        List<Document> docs = entryRepository.findExploreFeedItems(tenantId, type, sort, skip, size);
        long total = entryRepository.countExploreFeedItems(tenantId, type);
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;

        List<PublicFeedItemResponse> content = new ArrayList<>();
        Set<String> emptySet = Set.of();
        for (Document doc : docs) {
            content.add(mapDocToFeedItem(doc, emptySet, emptySet, false));
        }

        return new PublicFeedPageResponse(content, page, size, total, totalPages);
    }

    /**
     * Returns a unified, paginated feed of entries + collections for a public user profile.
     * @param userId nullable — the viewer's userId for entitlement checks (null if anonymous)
     */
    public PublicFeedPageResponse getProfileFeed(String tenantId, String authorUsername,
                                                  String userId, String viewerUsername,
                                                  String type, String search, String sort,
                                                  int page, int size) {
        int skip = page * size;
        List<Document> docs = entryRepository.findProfileFeedItems(
                tenantId, authorUsername, type, search, sort, skip, size);
        long total = entryRepository.countProfileFeedItems(tenantId, authorUsername, type, search);
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;

        // Owner shortcut: if the viewer is the profile owner, all their paid items are unlocked
        boolean viewerIsOwner = viewerUsername != null && viewerUsername.equals(authorUsername);

        Set<String> unlockedEntryIds = Set.of();
        Set<String> unlockedCollectionIds = Set.of();

        if (!viewerIsOwner && userId != null) {
            // Batch entitlement check for paid items (only when viewer is NOT the owner)
            List<String> paidEntryIds = new ArrayList<>();
            List<String> paidCollectionIds = new ArrayList<>();
            for (Document doc : docs) {
                Boolean isPaid = doc.getBoolean("isPaid", false);
                if (Boolean.TRUE.equals(isPaid)) {
                    String kind = doc.getString("kind");
                    String id = doc.get("_id") != null ? doc.get("_id").toString() : null;
                    if ("entry".equals(kind) && id != null) paidEntryIds.add(id);
                    else if ("collection".equals(kind) && id != null) paidCollectionIds.add(id);
                }
            }
            if (!paidEntryIds.isEmpty()) {
                unlockedEntryIds = new java.util.HashSet<>(entitlementRepository.findEntitledEntryIds(
                        tenantId, userId, paidEntryIds, EntitlementStatus.ACTIVE));

                // Also expand collection-level entitlements: entries inside purchased collections are unlocked
                Set<String> stillLocked = new java.util.HashSet<>(paidEntryIds);
                stillLocked.removeAll(unlockedEntryIds);
                if (!stillLocked.isEmpty()) {
                    Set<String> userCollIds = entitlementRepository.findAllEntitledCollectionIds(
                            tenantId, userId, EntitlementStatus.ACTIVE);
                    if (!userCollIds.isEmpty()) {
                        var userColls = collectionRepository.findByTenantIdAndIdIn(
                                tenantId, new ArrayList<>(userCollIds));
                        for (var c : userColls) {
                            if (c.getItems() != null) {
                                for (var item : c.getItems()) {
                                    if (item.getEntryId() != null && stillLocked.contains(item.getEntryId())) {
                                        unlockedEntryIds.add(item.getEntryId());
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!paidCollectionIds.isEmpty()) {
                unlockedCollectionIds = entitlementRepository.findEntitledCollectionIds(
                        tenantId, userId, paidCollectionIds, EntitlementStatus.ACTIVE);
            }
        }

        List<PublicFeedItemResponse> content = new ArrayList<>();
        for (Document doc : docs) {
            content.add(mapDocToFeedItem(doc, unlockedEntryIds, unlockedCollectionIds, viewerIsOwner));
        }

        return new PublicFeedPageResponse(content, page, size, total, totalPages);
    }

    private PublicFeedItemResponse mapDocToFeedItem(Document doc,
                                                     Set<String> unlockedEntryIds,
                                                     Set<String> unlockedCollectionIds,
                                                     boolean viewerIsOwner) {
        String id = doc.get("_id") != null ? doc.get("_id").toString() : null;
        String kind = doc.getString("kind");
        String type = doc.getString("type") != null ? doc.getString("type").toLowerCase() : "resource";
        boolean isPaid = doc.getBoolean("isPaid", false);

        boolean unlocked;
        boolean locked;
        if (!isPaid) {
            unlocked = false;
            locked = false;
        } else if (viewerIsOwner) {
            unlocked = true;
            locked = false;
        } else if ("entry".equals(kind)) {
            unlocked = unlockedEntryIds.contains(id);
            locked = !unlocked;
        } else {
            unlocked = unlockedCollectionIds.contains(id);
            locked = !unlocked;
        }

        return new PublicFeedItemResponse(
                id,
                kind,
                type,
                doc.getString("title"),
                doc.getString("description"),
                doc.getString("authorUsername"),
                doc.getString("authorAvatarUrl"),
                doc.get("publishedAt") instanceof java.util.Date d ? d.toInstant().toString() :
                    (doc.get("publishedAt") instanceof String s ? s : null),
                doc.getString("thumbnailR2Key"),
                doc.getString("coverR2Key"),
                doc.getInteger("durationSec"),
                doc.get("viewCount") instanceof Number n ? n.longValue() : 0L,
                isPaid,
                doc.get("priceXlm") instanceof Number n ? new BigDecimal(n.toString()) : null,
                doc.get("priceUsd") instanceof Number n ? new BigDecimal(n.toString()) : null,
                doc.getString("priceCurrency"),
                doc.getInteger("itemCount", 0),
                locked,
                unlocked
        );
    }
}
