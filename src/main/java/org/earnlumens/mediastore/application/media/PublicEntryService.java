package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.response.PublicEntryPageResponse;
import org.earnlumens.mediastore.domain.media.dto.response.PublicEntryResponse;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.user.model.User;
import org.earnlumens.mediastore.domain.user.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service for public (unauthenticated) entry queries.
 * Returns PUBLISHED entries enriched with author info.
 */
@Service
public class PublicEntryService {

    private final EntryRepository entryRepository;
    private final UserRepository userRepository;

    public PublicEntryService(EntryRepository entryRepository, UserRepository userRepository) {
        this.entryRepository = entryRepository;
        this.userRepository = userRepository;
    }

    /**
     * Returns a paginated list of PUBLISHED entries for the given tenant,
     * ordered by publishedAt descending (most recent first).
     */
    public PublicEntryPageResponse getPublishedEntries(String tenantId, int page, int size) {
        Page<Entry> entryPage = entryRepository.findByTenantIdAndStatus(
                tenantId, EntryStatus.PUBLISHED, PageRequest.of(page, size));

        // Batch-fetch users for all entries in the page
        List<String> userIds = entryPage.getContent().stream()
                .map(Entry::getUserId)
                .distinct()
                .toList();

        Map<String, User> usersById = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));

        List<PublicEntryResponse> content = entryPage.getContent().stream()
                .map(entry -> toPublicResponse(entry, usersById))
                .toList();

        return new PublicEntryPageResponse(
                content,
                entryPage.getNumber(),
                entryPage.getSize(),
                entryPage.getTotalElements(),
                entryPage.getTotalPages()
        );
    }

    private PublicEntryResponse toPublicResponse(Entry entry, Map<String, User> usersById) {
        User author = usersById.get(entry.getUserId());
        String authorName = author != null ? author.getUsername() : entry.getUserId();
        String authorAvatarUrl = author != null ? author.getProfileImageUrl() : null;
        String publishedAt = entry.getPublishedAt() != null
                ? entry.getPublishedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                : null;

        return new PublicEntryResponse(
                entry.getId(),
                mapType(entry.getType()),
                entry.getTitle(),
                entry.getDescription(),
                authorName,
                authorAvatarUrl,
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
}
