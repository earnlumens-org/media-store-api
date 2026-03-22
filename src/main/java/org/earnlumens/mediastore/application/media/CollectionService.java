package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.request.CreateCollectionRequest;
import org.earnlumens.mediastore.domain.media.dto.request.UpdateCollectionRequest;
import org.earnlumens.mediastore.domain.media.dto.response.CollectionDetailResponse;
import org.earnlumens.mediastore.domain.media.dto.response.CollectionPageResponse;
import org.earnlumens.mediastore.domain.media.dto.response.CollectionResponse;
import org.earnlumens.mediastore.domain.media.model.Collection;
import org.earnlumens.mediastore.domain.media.model.CollectionItem;
import org.earnlumens.mediastore.domain.media.model.CollectionStatus;
import org.earnlumens.mediastore.domain.media.model.CollectionType;
import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.MediaVisibility;
import org.earnlumens.mediastore.domain.media.model.PriceCurrency;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CollectionService {

    private static final Logger logger = LoggerFactory.getLogger(CollectionService.class);
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final CollectionRepository collectionRepository;
    private final EntryRepository entryRepository;
    private final EntitlementRepository entitlementRepository;
    private final UserRepository userRepository;

    public CollectionService(CollectionRepository collectionRepository,
                             EntryRepository entryRepository,
                             EntitlementRepository entitlementRepository,
                             UserRepository userRepository) {
        this.collectionRepository = collectionRepository;
        this.entryRepository = entryRepository;
        this.entitlementRepository = entitlementRepository;
        this.userRepository = userRepository;
    }

    // ── CRUD ──

    public CollectionResponse createCollection(String tenantId, String userId,
                                                CreateCollectionRequest request) {
        Collection collection = new Collection();
        collection.setTenantId(tenantId);
        collection.setUserId(userId);
        collection.setTitle(request.title());
        collection.setDescription(request.description());
        collection.setCollectionType(CollectionType.valueOf(request.collectionType().toUpperCase()));
        collection.setStatus(CollectionStatus.DRAFT);
        collection.setVisibility(request.visibility() != null
                ? MediaVisibility.valueOf(request.visibility().toUpperCase())
                : MediaVisibility.PUBLIC);
        collection.setPaid(Boolean.TRUE.equals(request.isPaid()));
        collection.setPriceXlm(request.priceXlm());
        collection.setPriceUsd(request.priceUsd());
        collection.setPriceCurrency(request.priceCurrency() != null
                ? PriceCurrency.valueOf(request.priceCurrency().toUpperCase())
                : null);
        collection.setSellerWallet(request.sellerWallet());

        if (collection.isPaid() && (collection.getSellerWallet() == null || collection.getSellerWallet().isBlank())) {
            throw new IllegalArgumentException("sellerWallet is required for paid collections");
        }

        // Denormalize author info for fast reads
        userRepository.findByOauthUserId(userId).ifPresent(user -> {
            collection.setAuthorUsername(user.getUsername());
            collection.setAuthorAvatarUrl(user.getProfileImageUrl());
        });

        Collection saved = collectionRepository.save(collection);
        logger.info("Created collection id={} for userId={}", saved.getId(), userId);
        return toResponse(saved, false, false);
    }

    public boolean updateCollection(String tenantId, String userId, String collectionId,
                                     UpdateCollectionRequest request) {
        Optional<Collection> opt = collectionRepository.findByTenantIdAndId(tenantId, collectionId);
        if (opt.isEmpty() || !opt.get().getUserId().equals(userId)) {
            return false;
        }

        Collection collection = opt.get();

        if (request.title() != null) collection.setTitle(request.title());
        if (request.description() != null) collection.setDescription(request.description());
        if (request.visibility() != null) {
            collection.setVisibility(MediaVisibility.valueOf(request.visibility().toUpperCase()));
        }
        if (request.isPaid() != null) collection.setPaid(request.isPaid());
        if (request.priceXlm() != null) collection.setPriceXlm(request.priceXlm());
        if (request.priceUsd() != null) collection.setPriceUsd(request.priceUsd());
        if (request.priceCurrency() != null) {
            collection.setPriceCurrency(PriceCurrency.valueOf(request.priceCurrency().toUpperCase()));
        }
        if (request.sellerWallet() != null) collection.setSellerWallet(request.sellerWallet());

        collectionRepository.save(collection);
        return true;
    }

    public boolean publishCollection(String tenantId, String userId, String collectionId) {
        Optional<Collection> opt = collectionRepository.findByTenantIdAndId(tenantId, collectionId);
        if (opt.isEmpty() || !opt.get().getUserId().equals(userId)) {
            return false;
        }

        Collection collection = opt.get();
        if (collection.getStatus() != CollectionStatus.DRAFT
                && collection.getStatus() != CollectionStatus.ARCHIVED) {
            throw new IllegalArgumentException("Only DRAFT or ARCHIVED collections can be published");
        }

        if (collection.isPaid() && (collection.getSellerWallet() == null || collection.getSellerWallet().isBlank())) {
            throw new IllegalArgumentException("sellerWallet is required for paid collections");
        }

        collection.setStatus(CollectionStatus.PUBLISHED);
        collection.setPublishedAt(LocalDateTime.now());
        collectionRepository.save(collection);
        logger.info("Published collection id={}", collectionId);
        return true;
    }

    public boolean archiveCollection(String tenantId, String userId, String collectionId) {
        Optional<Collection> opt = collectionRepository.findByTenantIdAndId(tenantId, collectionId);
        if (opt.isEmpty() || !opt.get().getUserId().equals(userId)) {
            return false;
        }

        Collection collection = opt.get();
        collection.setStatus(CollectionStatus.ARCHIVED);
        collectionRepository.save(collection);
        logger.info("Archived collection id={}", collectionId);
        return true;
    }

    public boolean unarchiveCollection(String tenantId, String userId, String collectionId) {
        Optional<Collection> opt = collectionRepository.findByTenantIdAndId(tenantId, collectionId);
        if (opt.isEmpty() || !opt.get().getUserId().equals(userId)) {
            return false;
        }

        Collection collection = opt.get();
        if (collection.getStatus() != CollectionStatus.ARCHIVED) {
            throw new IllegalArgumentException("Only ARCHIVED collections can be unarchived");
        }

        collection.setStatus(CollectionStatus.DRAFT);
        collectionRepository.save(collection);
        logger.info("Unarchived collection id={}", collectionId);
        return true;
    }

    public boolean deleteCollection(String tenantId, String userId, String collectionId) {
        Optional<Collection> opt = collectionRepository.findByTenantIdAndId(tenantId, collectionId);
        if (opt.isEmpty() || !opt.get().getUserId().equals(userId)) {
            return false;
        }

        Collection collection = opt.get();
        if (collection.getStatus() != CollectionStatus.DRAFT) {
            throw new IllegalArgumentException("Only DRAFT collections can be deleted");
        }

        collectionRepository.deleteByTenantIdAndId(tenantId, collectionId);
        logger.info("Deleted collection id={}", collectionId);
        return true;
    }

    // ── Item Management ──

    public boolean addItem(String tenantId, String userId, String collectionId, String entryId) {
        Optional<Collection> opt = collectionRepository.findByTenantIdAndId(tenantId, collectionId);
        if (opt.isEmpty() || !opt.get().getUserId().equals(userId)) {
            return false;
        }

        // Verify the entry exists in this tenant
        if (entryRepository.findByTenantIdAndId(tenantId, entryId).isEmpty()) {
            throw new IllegalArgumentException("Entry not found: " + entryId);
        }

        Collection collection = opt.get();
        List<CollectionItem> items = new ArrayList<>(collection.getItems());

        // Prevent duplicates
        boolean alreadyPresent = items.stream().anyMatch(i -> i.getEntryId().equals(entryId));
        if (alreadyPresent) {
            return true; // idempotent
        }

        int nextPosition = items.stream().mapToInt(CollectionItem::getPosition).max().orElse(-1) + 1;
        items.add(new CollectionItem(entryId, nextPosition));
        collection.setItems(items);
        collectionRepository.save(collection);
        return true;
    }

    public boolean removeItem(String tenantId, String userId, String collectionId, String entryId) {
        Optional<Collection> opt = collectionRepository.findByTenantIdAndId(tenantId, collectionId);
        if (opt.isEmpty() || !opt.get().getUserId().equals(userId)) {
            return false;
        }

        Collection collection = opt.get();
        List<CollectionItem> items = new ArrayList<>(collection.getItems());
        boolean removed = items.removeIf(i -> i.getEntryId().equals(entryId));
        if (!removed) {
            return false;
        }

        // Re-index positions
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setPosition(i);
        }
        collection.setItems(items);
        collectionRepository.save(collection);
        return true;
    }

    public boolean reorderItems(String tenantId, String userId, String collectionId, List<String> entryIds) {
        Optional<Collection> opt = collectionRepository.findByTenantIdAndId(tenantId, collectionId);
        if (opt.isEmpty() || !opt.get().getUserId().equals(userId)) {
            return false;
        }

        Collection collection = opt.get();
        Set<String> currentEntryIds = collection.getItems().stream()
                .map(CollectionItem::getEntryId).collect(Collectors.toSet());

        // Verify all entryIds are in the current items
        if (!currentEntryIds.equals(Set.copyOf(entryIds))) {
            throw new IllegalArgumentException("Provided entryIds don't match current collection items");
        }

        List<CollectionItem> reordered = new ArrayList<>();
        for (int i = 0; i < entryIds.size(); i++) {
            reordered.add(new CollectionItem(entryIds.get(i), i));
        }
        collection.setItems(reordered);
        collectionRepository.save(collection);
        return true;
    }

    // ── Queries ──

    /** Public feed: PUBLISHED + PUBLIC collections */
    public CollectionPageResponse getPublicCollections(String tenantId, int page, int size) {
        Page<Collection> collectionPage = collectionRepository.findByTenantIdAndStatusAndVisibility(
                tenantId, CollectionStatus.PUBLISHED, MediaVisibility.PUBLIC, PageRequest.of(page, size));

        List<CollectionResponse> items = collectionPage.getContent().stream()
                .map(c -> toResponse(c, false, false))
                .toList();

        return new CollectionPageResponse(items, page, size,
                collectionPage.getTotalElements(), collectionPage.getTotalPages());
    }

    /** Creator dashboard: all collections by user */
    public CollectionPageResponse getMyCollections(String tenantId, String userId, int page, int size) {
        Page<Collection> collectionPage = collectionRepository.findByTenantIdAndUserId(
                tenantId, userId, PageRequest.of(page, size));

        List<CollectionResponse> items = collectionPage.getContent().stream()
                .map(c -> toResponse(c, false, false))
                .toList();

        return new CollectionPageResponse(items, page, size,
                collectionPage.getTotalElements(), collectionPage.getTotalPages());
    }

    /** Public collection detail with hydrated entries and per-entry access status */
    public Optional<CollectionDetailResponse> getCollectionDetail(String tenantId, String collectionId, String userId) {
        Optional<Collection> opt = collectionRepository.findByTenantIdAndId(tenantId, collectionId);
        if (opt.isEmpty()) {
            return Optional.empty();
        }

        Collection collection = opt.get();
        boolean isOwner = userId != null && userId.equals(collection.getUserId());

        // Non-owner can only see published collections
        if (!isOwner && collection.getStatus() != CollectionStatus.PUBLISHED) {
            return Optional.empty();
        }

        // Compute locked/unlocked for the collection itself
        boolean hasCollEntitlement = false;
        if (collection.isPaid() && !isOwner && userId != null) {
            hasCollEntitlement = entitlementRepository
                    .existsByTenantIdAndUserIdAndTargetTypeAndCollectionIdAndStatus(
                            tenantId, userId,
                            org.earnlumens.mediastore.domain.media.model.TargetType.COLLECTION,
                            collectionId, EntitlementStatus.ACTIVE);
        }
        final boolean collLocked = collection.isPaid() && !isOwner && !hasCollEntitlement;
        final boolean collUnlocked = collection.isPaid() && !isOwner && hasCollEntitlement;

        // Hydrate entries
        List<CollectionItem> sortedItems = collection.getItems().stream()
                .sorted((a, b) -> Integer.compare(a.getPosition(), b.getPosition()))
                .toList();
        List<String> entryIds = sortedItems.stream().map(CollectionItem::getEntryId).toList();

        Map<String, Entry> entriesById = entryIds.isEmpty() ? Map.of()
                : entryRepository.findByTenantIdAndIdIn(tenantId, entryIds).stream()
                        .collect(Collectors.toMap(Entry::getId, e -> e));

        // Batch-check individual entry entitlements (only if user doesn't have collection access)
        Set<String> entitledEntryIds = Set.of();
        if (!collUnlocked && !isOwner && userId != null) {
            List<String> paidEntryIds = entriesById.values().stream()
                    .filter(Entry::isPaid)
                    .map(Entry::getId)
                    .toList();
            entitledEntryIds = entitlementRepository.findEntitledEntryIds(
                    tenantId, userId, paidEntryIds, EntitlementStatus.ACTIVE);
        }

        Set<String> finalEntitledEntryIds = entitledEntryIds;
        List<CollectionDetailResponse.CollectionEntryItem> entryItems = sortedItems.stream()
                .filter(item -> entriesById.containsKey(item.getEntryId()))
                .map(item -> {
                    Entry entry = entriesById.get(item.getEntryId());
                    boolean entryLocked;
                    boolean entryUnlocked;
                    if (!entry.isPaid() || isOwner || collUnlocked) {
                        entryLocked = false;
                        entryUnlocked = entry.isPaid();
                    } else {
                        entryUnlocked = finalEntitledEntryIds.contains(entry.getId());
                        entryLocked = !entryUnlocked;
                    }

                    return new CollectionDetailResponse.CollectionEntryItem(
                            entry.getId(),
                            item.getPosition(),
                            entry.getType() != null ? entry.getType().name().toLowerCase() : "resource",
                            entry.getTitle(),
                            entry.getDescription(),
                            entry.getAuthorUsername(),
                            entry.getThumbnailR2Key(),
                            entry.getDurationSec(),
                            entry.isPaid(),
                            entry.getPriceXlm(),
                            entryLocked,
                            entryUnlocked
                    );
                })
                .toList();

        return Optional.of(new CollectionDetailResponse(
                collection.getId(),
                collection.getTitle(),
                collection.getDescription(),
                collection.getCollectionType() != null ? collection.getCollectionType().name() : null,
                collection.getCoverR2Key(),
                collection.getStatus() != null ? collection.getStatus().name() : null,
                collection.getVisibility() != null ? collection.getVisibility().name() : null,
                collection.getAuthorUsername(),
                collection.getAuthorAvatarUrl(),
                collection.getPublishedAt() != null ? collection.getPublishedAt().format(ISO_FORMATTER) : null,
                collection.isPaid(),
                collection.getPriceXlm(),
                collection.getPriceUsd(),
                collection.getPriceCurrency() != null ? collection.getPriceCurrency().name() : null,
                collection.getItems().size(),
                collLocked,
                collUnlocked,
                isOwner,
                entryItems
        ));
    }

    /** Find purchasable collections containing a specific entry (for COLLECTION_ONLY entries) */
    public List<CollectionResponse> getCollectionsContainingEntry(String tenantId, String entryId) {
        return collectionRepository.findByTenantIdAndStatusAndItemsEntryId(
                        tenantId, CollectionStatus.PUBLISHED, entryId)
                .stream()
                .filter(Collection::isPaid)
                .map(c -> toResponse(c, true, false))
                .toList();
    }

    // ── Helpers ──

    private CollectionResponse toResponse(Collection collection, boolean locked, boolean unlocked) {
        return new CollectionResponse(
                collection.getId(),
                collection.getTitle(),
                collection.getDescription(),
                collection.getCollectionType() != null ? collection.getCollectionType().name() : null,
                collection.getCoverR2Key(),
                collection.getStatus() != null ? collection.getStatus().name() : null,
                collection.getVisibility() != null ? collection.getVisibility().name() : null,
                collection.getAuthorUsername(),
                collection.getAuthorAvatarUrl(),
                collection.getPublishedAt() != null ? collection.getPublishedAt().format(ISO_FORMATTER) : null,
                collection.isPaid(),
                collection.getPriceXlm(),
                collection.getPriceUsd(),
                collection.getPriceCurrency() != null ? collection.getPriceCurrency().name() : null,
                collection.getItems() != null ? collection.getItems().size() : 0,
                locked,
                unlocked
        );
    }
}
