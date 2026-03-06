package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.response.AssetInfo;
import org.earnlumens.mediastore.domain.media.dto.response.PublicEntryPageResponse;
import org.earnlumens.mediastore.domain.media.dto.response.PublicEntryResponse;
import org.earnlumens.mediastore.domain.media.model.AssetStatus;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.MediaKind;
import org.earnlumens.mediastore.domain.media.repository.AssetRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Service for public (unauthenticated) entry queries.
 * Returns PUBLISHED entries with denormalized author info — no user join needed.
 */
@Service
public class PublicEntryService {

    private final EntryRepository entryRepository;
    private final AssetRepository assetRepository;

    public PublicEntryService(EntryRepository entryRepository, AssetRepository assetRepository) {
        this.entryRepository = entryRepository;
        this.assetRepository = assetRepository;
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
                    entryRepository.incrementViewCount(entryId);

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

        return new PublicEntryResponse(
                entry.getId(),
                mapType(entry.getType()),
                entry.getTitle(),
                entry.getDescription(),
                entry.getResourceContent(),
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
                entry.getTags(),
                assetInfo
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
}
