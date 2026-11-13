package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.response.PurchasedCollectionPageResponse;
import org.earnlumens.mediastore.domain.media.dto.response.PurchasedCollectionResponse;
import org.earnlumens.mediastore.domain.media.dto.response.PurchasedEntryPageResponse;
import org.earnlumens.mediastore.domain.media.dto.response.PurchasedEntryResponse;
import org.earnlumens.mediastore.domain.media.dto.response.PublicFeedItemResponse;
import org.earnlumens.mediastore.domain.media.dto.response.PublicFeedPageResponse;
import org.earnlumens.mediastore.domain.media.model.Collection;
import org.earnlumens.mediastore.domain.media.model.CollectionItem;
import org.earnlumens.mediastore.domain.media.model.Entitlement;
import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryVersion;
import org.earnlumens.mediastore.domain.media.model.TargetType;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for listing a user's purchased content.
 * Joins entitlements with entry/collection data in a single paginated response.
 */
@Service
public class PurchaseListService {

    private static final Logger logger = LoggerFactory.getLogger(PurchaseListService.class);
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final EntitlementRepository entitlementRepository;
    private final EntryRepository entryRepository;
    private final CollectionRepository collectionRepository;

    public PurchaseListService(EntitlementRepository entitlementRepository,
                               EntryRepository entryRepository,
                               CollectionRepository collectionRepository) {
        this.entitlementRepository = entitlementRepository;
        this.entryRepository = entryRepository;
        this.collectionRepository = collectionRepository;
    }

    /**
     * Returns a paginated list of entries the user has purchased (ACTIVE entitlements).
     * For collection entitlements, expands into individual entries so the frontend
     * store knows which entry IDs are unlocked via collection purchase.
     */
    public PurchasedEntryPageResponse listPurchases(String tenantId, String userId,
                                                     int page, int size) {
        // 1. Get paginated entitlements (both ENTRY and COLLECTION types)
        Page<Entitlement> entitlementPage = entitlementRepository
                .findByTenantIdAndUserIdAndStatus(
                        tenantId, userId, EntitlementStatus.ACTIVE,
                        PageRequest.of(page, size));

        List<Entitlement> entitlements = entitlementPage.getContent();

        if (entitlements.isEmpty()) {
            return new PurchasedEntryPageResponse(
                    List.of(), page, size,
                    entitlementPage.getTotalElements(),
                    entitlementPage.getTotalPages());
        }

        // 2. Separate entry-level and collection-level entitlements
        List<Entitlement> entryEntitlements = entitlements.stream()
                .filter(ent -> ent.getEntryId() != null)
                .toList();
        List<Entitlement> collectionEntitlements = entitlements.stream()
                .filter(ent -> ent.getEntryId() == null && ent.getCollectionId() != null)
                .toList();

        // 3. Batch-load entries from direct entry entitlements
        List<String> directEntryIds = entryEntitlements.stream()
                .map(Entitlement::getEntryId)
                .toList();

        Map<String, Entry> entriesById = directEntryIds.isEmpty()
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(entryRepository
                        .findByTenantIdAndIdIn(tenantId, directEntryIds)
                        .stream()
                        .collect(Collectors.toMap(Entry::getId, e -> e)));

        // 4. Expand collection entitlements → load collections → extract entry IDs → load entries
        Map<String, Entitlement> collEntitlementByCollId = new LinkedHashMap<>();
        for (Entitlement ent : collectionEntitlements) {
            collEntitlementByCollId.put(ent.getCollectionId(), ent);
        }

        List<PurchasedEntryResponse> collectionExpandedItems = new ArrayList<>();
        if (!collectionEntitlements.isEmpty()) {
            List<String> collIds = new ArrayList<>(collEntitlementByCollId.keySet());
            List<Collection> collections = collectionRepository.findByTenantIdAndIdIn(tenantId, collIds);

            // Items the buyer's right covers (D-20): active records plus those
            // removed at or after grantedAt. A blocked collection grants nothing.
            Map<String, List<CollectionItem>> coveredByColl = new LinkedHashMap<>();
            for (Collection c : collections) {
                Entitlement ent = collEntitlementByCollId.get(c.getId());
                coveredByColl.put(c.getId(), c.isBlocked() || ent == null
                        ? List.of() : c.itemsCoveredBy(ent.getGrantedAt()));
            }

            // Find entry IDs not yet loaded
            List<String> expandedEntryIds = coveredByColl.values().stream()
                    .flatMap(items -> items.stream().map(CollectionItem::getEntryId))
                    .filter(id -> id != null && !entriesById.containsKey(id))
                    .distinct()
                    .toList();

            if (!expandedEntryIds.isEmpty()) {
                entryRepository.findByTenantIdAndIdIn(tenantId, expandedEntryIds)
                        .forEach(e -> entriesById.put(e.getId(), e));
            }

            // Create response items for each entry the right covers in each purchased collection
            for (Collection coll : collections) {
                Entitlement ent = collEntitlementByCollId.get(coll.getId());
                for (CollectionItem item : coveredByColl.get(coll.getId())) {
                    Entry entry = entriesById.get(item.getEntryId());
                    if (entry != null) {
                        collectionExpandedItems.add(toResponse(entry, ent, item.rightStartFor(ent.getGrantedAt())));
                    }
                }
            }
        }

        // 5. Build direct entry items
        List<PurchasedEntryResponse> directItems = entryEntitlements.stream()
                .filter(ent -> entriesById.containsKey(ent.getEntryId()))
                .map(ent -> toResponse(entriesById.get(ent.getEntryId()), ent, ent.getGrantedAt()))
                .toList();

        // 6. Merge, deduplicate by entry ID (direct purchases take precedence)
        Map<String, PurchasedEntryResponse> merged = new LinkedHashMap<>();
        for (PurchasedEntryResponse item : directItems) {
            merged.put(item.id(), item);
        }
        for (PurchasedEntryResponse item : collectionExpandedItems) {
            merged.putIfAbsent(item.id(), item);
        }

        return new PurchasedEntryPageResponse(
                new ArrayList<>(merged.values()),
                entitlementPage.getNumber(),
                entitlementPage.getSize(),
                entitlementPage.getTotalElements(),
                entitlementPage.getTotalPages());
    }

    /**
     * @param rightStart when the buyer's right to this entry began (direct
     *                   {@code grantedAt}, or {@code max(grantedAt, addedAt)}
     *                   via a collection) — selects the purchased version (N8-lite)
     */
    private PurchasedEntryResponse toResponse(Entry entry, Entitlement entitlement, LocalDateTime rightStart) {
        EntryVersion purchased = ContentVersionService.purchasedVersion(entry, rightStart);
        String title = entry.getTitle();
        String description = entry.getDescription();
        String thumbnailR2Key = entry.getThumbnailR2Key();
        String previewR2Key = entry.getPreviewR2Key();
        List<String> tags = entry.getTags();
        String thumbnailVariantsPrefix = entry.getThumbnailVariantsPrefix();
        String previewVariantsPrefix = entry.getPreviewVariantsPrefix();
        if (purchased != null) {
            title = purchased.getTitle();
            description = purchased.getDescription();
            thumbnailR2Key = purchased.getThumbnailR2Key();
            previewR2Key = purchased.getPreviewR2Key();
            tags = purchased.getTags();
            if (!Objects.equals(thumbnailR2Key, entry.getThumbnailR2Key())) thumbnailVariantsPrefix = null;
            if (!Objects.equals(previewR2Key, entry.getPreviewR2Key())) previewVariantsPrefix = null;
        }
        return new PurchasedEntryResponse(
                entry.getId(),
                entry.getType() != null ? entry.getType().name().toLowerCase() : "resource",
                title,
                description,
                entry.getAuthorUsername(),
                entry.getAuthorAvatarUrl(),
                entry.getPublishedAt() != null ? entry.getPublishedAt().format(ISO_FORMATTER) : null,
                thumbnailR2Key,
                previewR2Key,
                entry.getDurationSec(),
                entry.isPaid(),
                entry.getPriceXlm(),
                tags,
                entitlement.getGrantedAt() != null
                        ? entitlement.getGrantedAt().format(ISO_FORMATTER)
                        : null,
                thumbnailVariantsPrefix,
                previewVariantsPrefix
        );
    }

    /**
     * Returns a paginated list of collections the user has purchased (ACTIVE collection entitlements).
     */
    public PurchasedCollectionPageResponse listCollectionPurchases(String tenantId, String userId,
                                                                    int page, int size) {
        Page<Entitlement> entitlementPage = entitlementRepository
                .findByTenantIdAndUserIdAndTargetTypeAndStatus(
                        tenantId, userId, TargetType.COLLECTION, EntitlementStatus.ACTIVE,
                        PageRequest.of(page, size));

        List<Entitlement> entitlements = entitlementPage.getContent();

        if (entitlements.isEmpty()) {
            return new PurchasedCollectionPageResponse(
                    List.of(), page, size,
                    entitlementPage.getTotalElements(),
                    entitlementPage.getTotalPages());
        }

        List<String> collectionIds = entitlements.stream()
                .map(Entitlement::getCollectionId)
                .toList();

        Map<String, Collection> collectionsById = collectionRepository
                .findByTenantIdAndIdIn(tenantId, collectionIds)
                .stream()
                .collect(Collectors.toMap(Collection::getId, c -> c));

        List<PurchasedCollectionResponse> items = entitlements.stream()
                .filter(ent -> ent.getCollectionId() != null && collectionsById.containsKey(ent.getCollectionId()))
                .map(ent -> {
                    Collection coll = collectionsById.get(ent.getCollectionId());
                    return new PurchasedCollectionResponse(
                            coll.getId(),
                            coll.getTitle(),
                            coll.getDescription(),
                            coll.getCollectionType() != null ? coll.getCollectionType().name() : null,
                            coll.getCoverR2Key(),
                            coll.getAuthorUsername(),
                            coll.getAuthorAvatarUrl(),
                            coll.isPaid(),
                            coll.getPriceXlm(),
                            coll.isBlocked() ? 0 : coll.itemsCoveredBy(ent.getGrantedAt()).size(),
                            coll.getContentLanguage(),
                            ent.getGrantedAt() != null
                                    ? ent.getGrantedAt().format(ISO_FORMATTER)
                                    : null,
                            coll.getCoverVariantsPrefix()
                    );
                })
                .toList();

        return new PurchasedCollectionPageResponse(
                items,
                entitlementPage.getNumber(),
                entitlementPage.getSize(),
                entitlementPage.getTotalElements(),
                entitlementPage.getTotalPages());
    }

    // ── Unified purchased feed (entries + collections via $unionWith) ──────

    /**
     * Returns a unified, paginated feed of purchased entries + collections.
     * All items are unlocked (user owns them). Entry cards show the metadata
     * of the <b>purchased version</b> (N8-lite), like the detail page, the
     * library and the CDN: only entries whose document carries
     * {@code versions[]} are re-read, so the common case stays a single
     * aggregation.
     */
    public PublicFeedPageResponse getUnifiedPurchases(String tenantId, String userId,
                                                       String type, String search, String sort,
                                                       int page, int size) {
        // 1. Get ALL entitled entries (with grantedAt) and collection IDs
        List<Entitlement> entryEntitlements = entitlementRepository.findAllEntryEntitlements(
                tenantId, userId, EntitlementStatus.ACTIVE);
        Map<String, LocalDateTime> grantedAtByEntry = new LinkedHashMap<>();
        for (Entitlement ent : entryEntitlements) {
            grantedAtByEntry.putIfAbsent(ent.getEntryId(), ent.getGrantedAt());
        }
        Set<String> entryIds = grantedAtByEntry.keySet();
        Set<String> collectionIds = entitlementRepository.findAllEntitledCollectionIds(
                tenantId, userId, EntitlementStatus.ACTIVE);

        if (entryIds.isEmpty() && collectionIds.isEmpty()) {
            return new PublicFeedPageResponse(List.of(), page, size, 0, 0);
        }

        // 2. Run $unionWith aggregation with server-side pagination
        int skip = page * size;
        List<Document> docs = entryRepository.findPurchasedFeedItems(
                tenantId, entryIds, collectionIds, type, search, sort, skip, size);
        long total = entryRepository.countPurchasedFeedItems(
                tenantId, entryIds, collectionIds, type, search);
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;

        // 3. Purchased version overlay (N8-lite): only for entries with versions[]
        Map<String, EntryVersion> purchasedByEntry = resolvePurchasedVersions(tenantId, docs, grantedAtByEntry);

        // 4. Map docs — all purchased items are unlocked
        List<PublicFeedItemResponse> content = docs.stream()
                .map(doc -> mapPurchasedDoc(doc, purchasedByEntry))
                .toList();

        return new PublicFeedPageResponse(content, page, size, total, totalPages);
    }

    /**
     * For the entry cards on this page whose document has {@code versions[]},
     * loads the entries and computes the version the buyer's right points at
     * ({@link ContentVersionService#purchasedVersion}). Entries without
     * versions or whose purchased version is the current one map to nothing
     * (the aggregation already carries the live = purchased metadata).
     */
    private Map<String, EntryVersion> resolvePurchasedVersions(String tenantId, List<Document> docs,
                                                               Map<String, LocalDateTime> grantedAtByEntry) {
        List<String> versionedEntryIds = docs.stream()
                .filter(d -> "entry".equals(d.getString("kind")))
                .filter(d -> d.get("versionCount") instanceof Number n && n.intValue() > 0)
                .map(d -> d.get("_id") != null ? d.get("_id").toString() : null)
                .filter(id -> id != null && grantedAtByEntry.containsKey(id))
                .toList();
        if (versionedEntryIds.isEmpty()) {
            return Map.of();
        }
        Map<String, EntryVersion> result = new LinkedHashMap<>();
        for (Entry entry : entryRepository.findByTenantIdAndIdIn(tenantId, versionedEntryIds)) {
            EntryVersion purchased = ContentVersionService.purchasedVersion(entry, grantedAtByEntry.get(entry.getId()));
            if (purchased != null) {
                result.put(entry.getId(), purchased);
            }
        }
        return result;
    }

    private PublicFeedItemResponse mapPurchasedDoc(Document doc, Map<String, EntryVersion> purchasedByEntry) {
        String id = doc.get("_id") != null ? doc.get("_id").toString() : null;
        String kind = doc.getString("kind");
        String type = doc.getString("type") != null ? doc.getString("type").toLowerCase() : "resource";
        boolean isPaid = doc.getBoolean("isPaid", false);

        String title = doc.getString("title");
        String description = doc.getString("description");
        String thumbnailR2Key = doc.getString("thumbnailR2Key");
        String thumbnailVariantsPrefix = doc.getString("thumbnailVariantsPrefix");
        String previewVariantsPrefix = doc.getString("previewVariantsPrefix");
        EntryVersion purchased = id == null ? null : purchasedByEntry.get(id);
        if (purchased != null) {
            title = purchased.getTitle();
            description = purchased.getDescription();
            // Variants are derived from the live keys; drop them when the
            // purchased key differs so the card falls back to the original.
            if (!Objects.equals(purchased.getThumbnailR2Key(), thumbnailR2Key)) thumbnailVariantsPrefix = null;
            if (!Objects.equals(purchased.getPreviewR2Key(), doc.getString("previewR2Key"))) previewVariantsPrefix = null;
            thumbnailR2Key = purchased.getThumbnailR2Key();
        }

        return new PublicFeedItemResponse(
                id,
                kind,
                type,
                title,
                description,
                doc.getString("authorUsername"),
                doc.getString("authorAvatarUrl"),
                doc.getString("authorBadge"),
                doc.get("publishedAt") instanceof java.util.Date d ? d.toInstant().toString() :
                    (doc.get("publishedAt") instanceof String s ? s : null),
                thumbnailR2Key,
                doc.getString("coverR2Key"),
                doc.getInteger("durationSec"),
                doc.get("viewCount") instanceof Number n ? n.longValue() : 0L,
                isPaid,
                doc.get("priceXlm") instanceof Number n ? new BigDecimal(n.toString()) : null,
                doc.get("priceUsd") instanceof Number n ? new BigDecimal(n.toString()) : null,
                doc.getString("priceCurrency"),
                doc.getInteger("itemCount", 0),
                false,  // locked: never locked (user owns it)
                isPaid,  // unlocked: true for paid items (user bought it)
                thumbnailVariantsPrefix,
                previewVariantsPrefix,
                doc.getString("coverVariantsPrefix"),
                doc.getBoolean("remix", false)
        );
    }
}
