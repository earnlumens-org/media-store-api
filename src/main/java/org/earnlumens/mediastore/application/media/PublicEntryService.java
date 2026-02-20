package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.response.PublicEntryPageResponse;
import org.earnlumens.mediastore.domain.media.dto.response.PublicEntryResponse;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Service for public (unauthenticated) entry queries.
 * Returns PUBLISHED entries with denormalized author info — no user join needed.
 */
@Service
public class PublicEntryService {

    private final EntryRepository entryRepository;

    public PublicEntryService(EntryRepository entryRepository) {
        this.entryRepository = entryRepository;
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
                .map(this::toPublicResponse)
                .toList();

        return new PublicEntryPageResponse(
                content,
                entryPage.getNumber(),
                entryPage.getSize(),
                entryPage.getTotalElements(),
                entryPage.getTotalPages()
        );
    }

    private PublicEntryResponse toPublicResponse(Entry entry) {
        String publishedAt = entry.getPublishedAt() != null
                ? entry.getPublishedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                : null;

        return new PublicEntryResponse(
                entry.getId(),
                mapType(entry.getType()),
                entry.getTitle(),
                entry.getDescription(),
                entry.getAuthorUsername() != null ? entry.getAuthorUsername() : entry.getUserId(),
                entry.getAuthorAvatarUrl(),
                publishedAt,
                entry.getThumbnailR2Key(),
                entry.getPreviewR2Key(),
                entry.getDurationSec(),
                entry.isPaid(),
                entry.getPriceXlm(),
                entry.getTags()
        );
    }

    /**
     * Maps backend EntryType to the lowercase string the UI expects.
     * ARTICLE → "entry" (matches the UI's generic content card).
     */
    private String mapType(EntryType type) {
        if (type == null) return "entry";
        return switch (type) {
            case VIDEO -> "video";
            case AUDIO -> "audio";
            case IMAGE -> "image";
            case ARTICLE -> "entry";
            case FILE -> "file";
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
            case "entry", "article" -> EntryType.ARTICLE;
            case "file" -> EntryType.FILE;
            default -> null;
        };
    }
}
