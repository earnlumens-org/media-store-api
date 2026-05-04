package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.response.FavoriteItemResponse;
import org.earnlumens.mediastore.domain.media.dto.response.FavoritePageResponse;
import org.earnlumens.mediastore.domain.media.model.Collection;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.Favorite;
import org.earnlumens.mediastore.domain.media.model.FavoriteItemType;
import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.media.repository.FavoriteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Application service for the Favorites feature.
 *
 * <p>Handles adding/removing favorites, checking favorite status,
 * and listing paginated favorites with hydrated entry/collection data.
 * Follows the same batch-load pattern as {@link PurchaseListService}.</p>
 */
@Service
public class FavoriteService {

    private static final Logger logger = LoggerFactory.getLogger(FavoriteService.class);
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final FavoriteRepository favoriteRepository;
    private final EntryRepository entryRepository;
    private final CollectionRepository collectionRepository;
    private final EntitlementRepository entitlementRepository;

    public FavoriteService(FavoriteRepository favoriteRepository,
                           EntryRepository entryRepository,
                           CollectionRepository collectionRepository,
                           EntitlementRepository entitlementRepository) {
        this.favoriteRepository = favoriteRepository;
        this.entryRepository = entryRepository;
        this.collectionRepository = collectionRepository;
        this.entitlementRepository = entitlementRepository;
    }

    /**
     * Toggle a favorite: add if not present, remove if already favorited.
     *
     * @return {@code true} if the item was added, {@code false} if removed.
     */
    public boolean toggleFavorite(String tenantId, String userId, String itemId, FavoriteItemType itemType) {
        Optional<Favorite> existing = favoriteRepository.findByTenantIdAndUserIdAndItemId(tenantId, userId, itemId);

        if (existing.isPresent()) {
            favoriteRepository.deleteByTenantIdAndId(tenantId, existing.get().getId());
            logger.debug("Removed favorite itemId={} for userId={}", itemId, userId);
            return false;
        }

        Favorite favorite = new Favorite();
        favorite.setTenantId(tenantId);
        favorite.setUserId(userId);
        favorite.setItemId(itemId);
        favorite.setItemType(itemType);

        favoriteRepository.save(favorite);
        logger.debug("Added favorite itemId={} for userId={}", itemId, userId);
        return true;
    }

    /**
     * Check if a specific item is favorited by the user.
     */
    public boolean isFavorite(String tenantId, String userId, String itemId) {
        return favoriteRepository.existsByTenantIdAndUserIdAndItemId(tenantId, userId, itemId);
    }

    /**
     * List the user's favorites, paginated and hydrated with entry/collection data.
     * Items whose underlying entry/collection no longer exists are silently skipped
     * (orphaned favorites are cleaned up lazily).
     */
    public FavoritePageResponse listFavorites(String tenantId, String userId, String viewerUsername, int page, int size) {
        Page<Favorite> favoritePage = favoriteRepository.findByTenantIdAndUserId(
                tenantId, userId, PageRequest.of(page, size));

        List<Favorite> favorites = favoritePage.getContent();

        if (favorites.isEmpty()) {
            return new FavoritePageResponse(
                    List.of(), page, size,
                    favoritePage.getTotalElements(),
                    favoritePage.getTotalPages());
        }

        // Partition by item type
        List<String> entryIds = favorites.stream()
                .filter(f -> f.getItemType() == FavoriteItemType.ENTRY)
                .map(Favorite::getItemId)
                .toList();

        List<String> collectionIds = favorites.stream()
                .filter(f -> f.getItemType() == FavoriteItemType.COLLECTION)
                .map(Favorite::getItemId)
                .toList();

        // Batch-load entries and collections
        Map<String, Entry> entriesById = entryIds.isEmpty()
                ? Map.of()
                : entryRepository.findByTenantIdAndIdIn(tenantId, entryIds)
                        .stream()
                        .collect(Collectors.toMap(Entry::getId, e -> e));

        Map<String, Collection> collectionsById = collectionIds.isEmpty()
                ? Map.of()
                : collectionRepository.findByTenantIdAndIdIn(tenantId, collectionIds)
                        .stream()
                        .collect(Collectors.toMap(Collection::getId, c -> c));

        // Batch-check entitlements for paid entries so we can set locked correctly.
        // Exclude entries owned by the requesting user — owners always have access.
        List<String> paidNonOwnedEntryIds = entriesById.values().stream()
                .filter(Entry::isPaid)
                .filter(e -> !isOwner(userId, viewerUsername, e.getUserId(), e.getAuthorUsername()))
                .map(Entry::getId)
                .toList();

        Set<String> entitledEntryIds = new java.util.HashSet<>(entitlementRepository.findEntitledEntryIds(
                tenantId, userId, paidNonOwnedEntryIds, EntitlementStatus.ACTIVE));

        // Also check collection-level entitlements: if the user purchased a collection,
        // all entries inside it are unlocked too.
        if (!paidNonOwnedEntryIds.isEmpty()) {
            Set<String> stillLockedEntryIds = paidNonOwnedEntryIds.stream()
                    .filter(id -> !entitledEntryIds.contains(id))
                    .collect(java.util.stream.Collectors.toSet());
            if (!stillLockedEntryIds.isEmpty()) {
                Set<String> userCollIds = entitlementRepository.findAllEntitledCollectionIds(
                        tenantId, userId, EntitlementStatus.ACTIVE);
                if (!userCollIds.isEmpty()) {
                    List<Collection> userColls = collectionRepository.findByTenantIdAndIdIn(
                            tenantId, new java.util.ArrayList<>(userCollIds));
                    for (Collection c : userColls) {
                        if (c.getItems() != null) {
                            for (var item : c.getItems()) {
                                if (item.getEntryId() != null && stillLockedEntryIds.contains(item.getEntryId())) {
                                    entitledEntryIds.add(item.getEntryId());
                                }
                            }
                        }
                    }
                }
            }
        }

        // Batch-check entitlements for paid collections
        List<String> paidNonOwnedCollIds = collectionsById.values().stream()
                .filter(Collection::isPaid)
                .filter(c -> !isOwner(userId, viewerUsername, c.getUserId(), c.getAuthorUsername()))
                .map(Collection::getId)
                .toList();

        Set<String> entitledCollIds = entitlementRepository.findEntitledCollectionIds(
                tenantId, userId, paidNonOwnedCollIds, EntitlementStatus.ACTIVE);

        // Build response, preserving favorites order (newest first), skipping orphans
        List<FavoriteItemResponse> items = new ArrayList<>();
        List<String> orphanIds = new ArrayList<>();

        for (Favorite fav : favorites) {
            if (fav.getItemType() == FavoriteItemType.ENTRY) {
                Entry entry = entriesById.get(fav.getItemId());
                if (entry != null) {
                    boolean entryIsOwner = isOwner(userId, viewerUsername, entry.getUserId(), entry.getAuthorUsername());
                    boolean locked = entry.isPaid() && !entryIsOwner
                            && !entitledEntryIds.contains(entry.getId());
                    items.add(toEntryResponse(fav, entry, locked));
                } else {
                    orphanIds.add(fav.getId());
                }
            } else {
                Collection collection = collectionsById.get(fav.getItemId());
                if (collection != null) {
                    boolean collIsOwner = isOwner(userId, viewerUsername, collection.getUserId(), collection.getAuthorUsername());
                    boolean collLocked = collection.isPaid() && !collIsOwner
                            && !entitledCollIds.contains(collection.getId());
                    items.add(toCollectionResponse(fav, collection, collLocked));
                } else {
                    orphanIds.add(fav.getId());
                }
            }
        }

        // Lazy cleanup: remove orphaned favorites
        for (String orphanId : orphanIds) {
            try {
                favoriteRepository.deleteByTenantIdAndId(tenantId, orphanId);
                logger.info("Removed orphaned favorite id={}", orphanId);
            } catch (Exception e) {
                logger.warn("Failed to clean up orphaned favorite id={}: {}", orphanId, e.getMessage());
            }
        }

        return new FavoritePageResponse(
                items,
                favoritePage.getNumber(),
                favoritePage.getSize(),
                favoritePage.getTotalElements() - orphanIds.size(),
                favoritePage.getTotalPages());
    }

    private FavoriteItemResponse toEntryResponse(Favorite fav, Entry entry, boolean locked) {
        boolean unlocked = entry.isPaid() && !locked;
        return new FavoriteItemResponse(
                fav.getId(),
                entry.getId(),
                "entry",
                mapEntryType(entry.getType()),
                entry.getTitle(),
                entry.getAuthorUsername(),
                entry.getAuthorAvatarUrl(),
                entry.getAuthorBadge(),
                entry.getPublishedAt() != null ? entry.getPublishedAt().format(ISO_FORMATTER) : null,
                entry.getThumbnailR2Key(),
                null, // coverUrl — entries use thumbnail
                entry.getDurationSec(),
                null, // collectionType
                null, // itemsCount
                locked,
                unlocked,
                fav.getAddedAt() != null ? fav.getAddedAt().format(ISO_FORMATTER) : null,
                entry.getThumbnailVariantsPrefix(),
                null // coverVariantsPrefix — entries use thumbnail
        );
    }

    private FavoriteItemResponse toCollectionResponse(Favorite fav, Collection collection, boolean locked) {
        boolean unlocked = collection.isPaid() && !locked;
        return new FavoriteItemResponse(
                fav.getId(),
                collection.getId(),
                "collection",
                null, // entryType — not applicable for collections
                collection.getTitle(),
                collection.getAuthorUsername(),
                collection.getAuthorAvatarUrl(),
                collection.getAuthorBadge(),
                collection.getPublishedAt() != null ? collection.getPublishedAt().format(ISO_FORMATTER) : null,
                null, // thumbnailUrl
                collection.getCoverR2Key(),
                null, // durationSec
                collection.getCollectionType() != null ? collection.getCollectionType().name().toLowerCase() : null,
                collection.getItems() != null ? collection.getItems().size() : null,
                locked,
                unlocked,
                fav.getAddedAt() != null ? fav.getAddedAt().format(ISO_FORMATTER) : null,
                null, // thumbnailVariantsPrefix — collections use cover
                collection.getCoverVariantsPrefix()
        );
    }

    private String mapEntryType(EntryType type) {
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
     * Check ownership by userId match OR authorUsername fallback.
     */
    private boolean isOwner(String viewerUserId, String viewerUsername,
                            String itemUserId, String itemAuthorUsername) {
        if (viewerUserId != null && viewerUserId.equals(itemUserId)) return true;
        return viewerUsername != null && viewerUsername.equals(itemAuthorUsername);
    }
}
