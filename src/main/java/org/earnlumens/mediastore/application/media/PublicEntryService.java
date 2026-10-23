package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.response.AssetInfo;
import org.earnlumens.mediastore.domain.media.dto.response.PublicEntryPageResponse;
import org.earnlumens.mediastore.domain.media.dto.response.PublicEntryResponse;
import org.earnlumens.mediastore.domain.media.dto.response.PublicFeedItemResponse;
import org.earnlumens.mediastore.domain.media.dto.response.PublicFeedPageResponse;
import org.earnlumens.mediastore.domain.media.model.AssetStatus;
import org.earnlumens.mediastore.domain.media.model.DisputeStatus;
import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.MediaKind;
import org.earnlumens.mediastore.domain.media.repository.AssetRepository;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.domain.media.repository.DisputeRepository;
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
    private final ContentAccessPolicy accessPolicy;
    private final DisputeRepository disputeRepository;

    public PublicEntryService(EntryRepository entryRepository, AssetRepository assetRepository,
                              EntitlementRepository entitlementRepository,
                              CollectionRepository collectionRepository,
                              ContentAccessPolicy accessPolicy, DisputeRepository disputeRepository) {
        this.entryRepository = entryRepository;
        this.assetRepository = assetRepository;
        this.entitlementRepository = entitlementRepository;
        this.collectionRepository = collectionRepository;
        this.accessPolicy = accessPolicy;
        this.disputeRepository = disputeRepository;
    }

    /**
     * Returns a single entry by ID for the given tenant, or the reason it
     * cannot be shown. Visibility is decided by {@link ContentAccessPolicy}
     * — the same rule the CDN applies (H3): a moderation block hides the
     * entry from everyone; the owner always sees it; free content is public
     * only in {@code PUBLISHED} / {@code UNLISTED} (D-19); a buyer with a
     * confirmed purchase keeps the page in any status (archived, deleted,
     * in re-review …). Paid content that is public but not purchased is
     * still shown — the page is the paywall.
     *
     * @param tenantId the tenant identifier
     * @param entryId  the entry identifier
     * @param viewerUserId optional OAuth user ID of the requester; {@code null} if anonymous
     */
    public Lookup lookupEntry(String tenantId, String entryId, String viewerUserId) {
        Optional<Entry> optEntry = entryRepository.findByTenantIdAndId(tenantId, entryId);
        if (optEntry.isEmpty()) {
            return Lookup.denied(AccessDecision.DENIED_HIDDEN);
        }
        Entry entry = optEntry.get();
        ContentAccessPolicy.Result result = accessPolicy.evaluate(tenantId, entry, viewerUserId);

        // The detail page doubles as the paywall: a public, paid, unpurchased
        // entry is shown (locked). Every other denial hides the page.
        if (!result.allowed() && result.decision() != AccessDecision.DENIED_LOCKED) {
            if (result.decision() == AccessDecision.DENIED_BLOCKED) {
                return Lookup.blocked(openDisputeFor(tenantId, entryId, viewerUserId));
            }
            return Lookup.denied(result.decision());
        }

        // Atomically increment view count (fire-and-forget)
        entryRepository.incrementViewCount(tenantId, entryId);

        // For detail view: include FULL asset metadata if available
        AssetInfo assetInfo = assetRepository
                .findByTenantIdAndEntryIdAndKindAndStatus(tenantId, entryId, MediaKind.FULL, AssetStatus.READY)
                .map(asset -> new AssetInfo(asset.getFileName(), asset.getFileSizeBytes(), asset.getContentType(), asset.getScannedAt()))
                .orElse(null);
        String viewerAccess = result.owner() ? "OWNER" : result.entitled() ? "ENTITLED" : "PUBLIC";
        return Lookup.found(toPublicResponse(entry, assetInfo, viewerAccess));
    }

    /**
     * Result of {@link #lookupEntry}: the page, or why it is not shown. For a
     * takedown, {@code disputeId} references the viewer's OPEN dispute on this
     * entry (if any) so the UI can point the buyer to it.
     */
    public record Lookup(Optional<PublicEntryResponse> entry, AccessDecision denial, String disputeId) {
        static Lookup found(PublicEntryResponse r) { return new Lookup(Optional.of(r), null, null); }
        static Lookup denied(AccessDecision d) { return new Lookup(Optional.empty(), d, null); }
        static Lookup blocked(String disputeId) {
            return new Lookup(Optional.empty(), AccessDecision.DENIED_BLOCKED, disputeId);
        }
        public boolean blocked() { return denial == AccessDecision.DENIED_BLOCKED; }
    }

    private String openDisputeFor(String tenantId, String entryId, String viewerUserId) {
        if (viewerUserId == null) {
            return null;
        }
        return disputeRepository.findByTenantIdAndUserIdAndStatus(tenantId, viewerUserId, DisputeStatus.OPEN)
                .stream()
                .filter(d -> d.getAffectedEntryIds().contains(entryId))
                .map(d -> d.getId())
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns a single entry by ID for the given tenant (see {@link #lookupEntry}).
     */
    public Optional<PublicEntryResponse> getPublishedEntryById(String tenantId, String entryId, String viewerUserId) {
        return lookupEntry(tenantId, entryId, viewerUserId).entry();
    }

    /**
     * Backwards-compatible overload (anonymous viewer).
     */
    public Optional<PublicEntryResponse> getPublishedEntryById(String tenantId, String entryId) {
        return getPublishedEntryById(tenantId, entryId, null);
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
     * Returns the public feed of a single space — PUBLISHED entries whose
     * {@code spaceIds} contains the given {@code spaceId}, newest first.
     * Tenant scope is enforced at the repository layer (a forged spaceId
     * from another tenant cannot leak rows across tenants).
     */
    public PublicEntryPageResponse getSpaceFeed(String tenantId, String spaceId, int page, int size) {
        Page<Entry> entryPage = entryRepository.findByTenantIdAndSpaceIdAndStatus(
                tenantId, spaceId, EntryStatus.PUBLISHED, PageRequest.of(page, size));
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
        return toPublicResponse(entry, assetInfo, "PUBLIC");
    }

    private PublicEntryResponse toPublicResponse(Entry entry, AssetInfo assetInfo, String viewerAccess) {
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
                entry.getAuthorBadge(),
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
                hlsReady,
                entry.getThumbnailVariantsPrefix(),
                entry.getPreviewVariantsPrefix(),
                entry.isResellerEnabled(),
                entry.getResellerCommissionPercent(),
                entry.isRemix(),
                entry.isRemix() ? entry.getOriginalEntryId() : null,
                entry.isRemix() ? entry.getOriginalAuthorUsername() : null,
                entry.getRemixRoyaltyPercent(),
                entry.getStatus() != null ? entry.getStatus().name() : null,
                viewerAccess
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
            case COLLECTION -> "collection";
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
     * Returns a paginated community feed: entries + collections from badge-verified users.
     * Hierarchical: content with authorBadge u1, u2 or u3 appears (higher tiers
     * are always included in lower-tier feeds).
     */
    public PublicFeedPageResponse getCommunityFeed(String tenantId, String type, String pricing,
                                                    String sort,
                                                    org.earnlumens.mediastore.domain.media.model.LanguageFilter languageFilter,
                                                    int page, int size) {
        int skip = page * size;
        Document facetResult = entryRepository.findCommunityFeed(tenantId, "u1", type, pricing, sort, languageFilter, skip, size);
        PublicFeedPageResponse response = toFeedPage(facetResult, page, size, false);

        // Language fallback: an active language filter that matches nothing
        // must never leave the user staring at an empty wall while content
        // exists in other languages — re-run unfiltered and flag it so the
        // UI can explain ("no content in your languages yet — showing all").
        if (page == 0 && response.content().isEmpty()
                && languageFilter != null && languageFilter.applies()) {
            Document unfiltered = entryRepository.findCommunityFeed(
                    tenantId, "u1", type, pricing, sort,
                    org.earnlumens.mediastore.domain.media.model.LanguageFilter.NONE, skip, size);
            PublicFeedPageResponse fallback = toFeedPage(unfiltered, page, size, true);
            if (!fallback.content().isEmpty()) {
                return fallback;
            }
        }
        return response;
    }

    /**
     * Returns a unified, paginated feed of ALL entries + collections for the explore page.
     * No entitlement checks — locked/unlocked is resolved client-side via purchasesStore.
     */
    public PublicFeedPageResponse getExploreFeed(String tenantId, String type, String pricing, String sort,
                                                  org.earnlumens.mediastore.domain.media.model.LanguageFilter languageFilter,
                                                  int page, int size) {
        int skip = page * size;
        Document facetResult = entryRepository.findExploreFeed(tenantId, type, pricing, sort, languageFilter, skip, size);
        PublicFeedPageResponse response = toFeedPage(facetResult, page, size, false);

        // Language fallback — see getCommunityFeed for rationale.
        if (page == 0 && response.content().isEmpty()
                && languageFilter != null && languageFilter.applies()) {
            Document unfiltered = entryRepository.findExploreFeed(
                    tenantId, type, pricing, sort,
                    org.earnlumens.mediastore.domain.media.model.LanguageFilter.NONE, skip, size);
            PublicFeedPageResponse fallback = toFeedPage(unfiltered, page, size, true);
            if (!fallback.content().isEmpty()) {
                return fallback;
            }
        }
        return response;
    }

    /** Map a $facet aggregation result (data + count) to a feed page response. */
    private PublicFeedPageResponse toFeedPage(Document facetResult, int page, int size, boolean languageFallback) {
        List<Document> docs = facetResult != null
                ? facetResult.getList("data", Document.class, List.of())
                : List.of();
        List<Document> countList = facetResult != null
                ? facetResult.getList("count", Document.class, List.of())
                : List.of();
        long total = countList.isEmpty() ? 0 : countList.get(0).get("total", Number.class).longValue();
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;

        List<PublicFeedItemResponse> content = new ArrayList<>();
        Set<String> emptySet = Set.of();
        for (Document doc : docs) {
            content.add(mapDocToFeedItem(doc, emptySet, emptySet, false));
        }

        return new PublicFeedPageResponse(content, page, size, total, totalPages, languageFallback);
    }

    /**
     * Returns a unified, paginated feed of entries + collections matching a
     * search query. Locked/unlocked is resolved client-side (like Explore).
     */
    public PublicFeedPageResponse searchContent(String tenantId, String query, String type, String sort,
                                                 int page, int size) {
        int skip = page * size;
        Document facetResult = entryRepository.findSearchFeed(tenantId, query, type, sort, skip, size);

        List<Document> docs = facetResult != null
                ? facetResult.getList("data", Document.class, List.of())
                : List.of();
        List<Document> countList = facetResult != null
                ? facetResult.getList("count", Document.class, List.of())
                : List.of();
        long total = countList.isEmpty() ? 0 : countList.get(0).get("total", Number.class).longValue();
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
                doc.getString("authorBadge"),
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
                unlocked,
                doc.getString("thumbnailVariantsPrefix"),
                doc.getString("previewVariantsPrefix"),
                doc.getString("coverVariantsPrefix"),
                doc.getBoolean("remix", false)
        );
    }
}
