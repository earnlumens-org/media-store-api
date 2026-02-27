package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.response.PurchasedEntryPageResponse;
import org.earnlumens.mediastore.domain.media.dto.response.PurchasedEntryResponse;
import org.earnlumens.mediastore.domain.media.model.Entitlement;
import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for listing a user's purchased content.
 * Joins entitlements with entry data in a single paginated response.
 */
@Service
public class PurchaseListService {

    private static final Logger logger = LoggerFactory.getLogger(PurchaseListService.class);
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final EntitlementRepository entitlementRepository;
    private final EntryRepository entryRepository;

    public PurchaseListService(EntitlementRepository entitlementRepository,
                               EntryRepository entryRepository) {
        this.entitlementRepository = entitlementRepository;
        this.entryRepository = entryRepository;
    }

    /**
     * Returns a paginated list of entries the user has purchased (ACTIVE entitlements).
     * Sorted by grantedAt descending (most recent purchases first).
     */
    public PurchasedEntryPageResponse listPurchases(String tenantId, String userId,
                                                     int page, int size) {
        // 1. Get paginated entitlements
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

        // 2. Batch-load all referenced entries
        List<String> entryIds = entitlements.stream()
                .map(Entitlement::getEntryId)
                .toList();

        Map<String, Entry> entriesById = entryRepository
                .findByTenantIdAndIdIn(tenantId, entryIds)
                .stream()
                .collect(Collectors.toMap(Entry::getId, e -> e));

        // 3. Join entitlements with entries, skip any orphaned entitlements
        List<PurchasedEntryResponse> items = entitlements.stream()
                .filter(ent -> entriesById.containsKey(ent.getEntryId()))
                .map(ent -> {
                    Entry entry = entriesById.get(ent.getEntryId());
                    return toResponse(entry, ent);
                })
                .toList();

        return new PurchasedEntryPageResponse(
                items,
                entitlementPage.getNumber(),
                entitlementPage.getSize(),
                entitlementPage.getTotalElements(),
                entitlementPage.getTotalPages());
    }

    private PurchasedEntryResponse toResponse(Entry entry, Entitlement entitlement) {
        return new PurchasedEntryResponse(
                entry.getId(),
                entry.getType() != null ? entry.getType().name().toLowerCase() : "resource",
                entry.getTitle(),
                entry.getDescription(),
                entry.getAuthorUsername(),
                entry.getAuthorAvatarUrl(),
                entry.getPublishedAt() != null ? entry.getPublishedAt().format(ISO_FORMATTER) : null,
                entry.getThumbnailR2Key(),
                entry.getPreviewR2Key(),
                entry.getDurationSec(),
                entry.isPaid(),
                entry.getPriceXlm(),
                entry.getTags(),
                entitlement.getGrantedAt() != null
                        ? entitlement.getGrantedAt().format(ISO_FORMATTER)
                        : null
        );
    }
}
