package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.model.Asset;
import org.earnlumens.mediastore.domain.media.model.AssetStatus;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryVersion;
import org.earnlumens.mediastore.domain.media.model.MediaKind;
import org.earnlumens.mediastore.domain.media.model.PendingVersion;
import org.earnlumens.mediastore.domain.media.repository.AssetRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The single pending-edit slot of an already-published entry
 * (MYCELIUM-RESILIENCE.md N9 / D-13 / D-14, DATA-MODEL.md §2.3).
 *
 * <p>Invariant: <b>the live fields of an entry in a staged status are never
 * written by an edit</b>; they change only when {@link #applyPendingVersion}
 * projects an approved slot onto them. Rejecting the slot changes nothing
 * visible. The entry's denormalized fields are always the projection of
 * {@code currentVersion}; the previous state is kept in {@code versions[]}
 * so that what a buyer acquired is never lost (creator side of D-14 — the
 * buyer's choice of version is N8 and not implemented here).
 */
@Service
public class ContentVersionService {

    private static final Logger logger = LoggerFactory.getLogger(ContentVersionService.class);

    /** Statuses whose edits go to the slot instead of the live document (N9 action 4). */
    public static final Set<EntryStatus> STAGED_STATUSES =
            EnumSet.of(EntryStatus.PUBLISHED, EntryStatus.UNLISTED, EntryStatus.ARCHIVED);

    public static final String PART_TITLE = "TITLE";
    public static final String PART_DESCRIPTION = "DESCRIPTION";
    public static final String PART_RESOURCE_TEXT = "RESOURCE_TEXT";
    public static final String PART_TAGS = "TAGS";
    public static final String PART_THUMBNAIL = "THUMBNAIL";
    public static final String PART_PREVIEW = "PREVIEW";

    private final EntryRepository entryRepository;
    private final AssetRepository assetRepository;
    private final ThumbnailJobService thumbnailJobService;

    public ContentVersionService(EntryRepository entryRepository,
                                 AssetRepository assetRepository,
                                 ThumbnailJobService thumbnailJobService) {
        this.entryRepository = entryRepository;
        this.assetRepository = assetRepository;
        this.thumbnailJobService = thumbnailJobService;
    }

    /** True when edits of this entry must be staged rather than applied live. */
    public static boolean usesPendingVersion(Entry entry) {
        return entry.getStatus() != null && STAGED_STATUSES.contains(entry.getStatus());
    }

    /**
     * N8-lite (S-07): the version a right that started at {@code rightStart}
     * acquired — the last approved version with {@code approvedAt <= rightStart}
     * (or v1 when none qualifies). Returns {@code null} when the buyer should
     * simply read the live document: the entry has no {@code versions[]}
     * (absent = v1, S-01) or the purchased version <b>is</b> the current one,
     * whose fields are the live projection. A {@code null} {@code rightStart}
     * (historical entitlement without a date) counts as "since forever".
     */
    public static EntryVersion purchasedVersion(Entry entry, LocalDateTime rightStart) {
        List<EntryVersion> versions = entry.getVersions();
        if (versions.isEmpty()) {
            return null;
        }
        EntryVersion best = null;
        for (EntryVersion v : versions) {
            boolean approvedBeforeRight = rightStart != null
                    && (v.getApprovedAt() == null || !v.getApprovedAt().isAfter(rightStart));
            if (approvedBeforeRight && (best == null || v.getVersion() > best.getVersion())) {
                best = v;
            }
        }
        if (best == null) {
            best = versions.stream().min(Comparator.comparingInt(EntryVersion::getVersion)).orElse(null);
        }
        if (best == null || best.getVersion() >= entry.currentVersionOrOne()) {
            return null;
        }
        return best;
    }

    /**
     * Returns the slot to edit: the existing pending edit when there is one
     * (a newer edit builds on it), otherwise a fresh slot. Nothing is saved;
     * the caller fills the fields, then calls {@link #finalizeStaging}.
     */
    public PendingVersion slotFor(Entry entry) {
        PendingVersion slot = entry.getPendingVersion();
        return slot != null ? slot : new PendingVersion();
    }

    /**
     * Recomputes the change summary against the live entry, stamps
     * {@code submittedAt} and attaches the slot to the entry (not saved).
     * A slot that no longer differs from the live entry is dropped.
     *
     * @return true when the entry now carries a pending edit
     */
    public boolean finalizeStaging(Entry entry, PendingVersion slot) {
        List<String> parts = new ArrayList<>();
        if (slot.getTitle() != null && !Objects.equals(slot.getTitle(), entry.getTitle())) parts.add(PART_TITLE);
        if (slot.getDescription() != null && !Objects.equals(slot.getDescription(), entry.getDescription())) parts.add(PART_DESCRIPTION);
        if (slot.getResourceContent() != null && !Objects.equals(slot.getResourceContent(), entry.getResourceContent())) parts.add(PART_RESOURCE_TEXT);
        if (slot.getTags() != null && !Objects.equals(slot.getTags(), entry.getTags())) parts.add(PART_TAGS);
        if (slot.getThumbnailR2Key() != null && !Objects.equals(slot.getThumbnailR2Key(), entry.getThumbnailR2Key())) parts.add(PART_THUMBNAIL);
        if (slot.getPreviewR2Key() != null && !Objects.equals(slot.getPreviewR2Key(), entry.getPreviewR2Key())) parts.add(PART_PREVIEW);

        if (parts.isEmpty()) {
            entry.setPendingVersion(null);
            return false;
        }
        slot.setChangeSummary(parts);
        slot.setSubmittedAt(LocalDateTime.now());
        entry.setPendingVersion(slot);
        return true;
    }

    /** Merged view (slot over live) of the fields the moderation job must inspect. */
    public static String effectiveTitle(Entry e) {
        PendingVersion p = e.getPendingVersion();
        return p != null && p.getTitle() != null ? p.getTitle() : e.getTitle();
    }

    public static String effectiveDescription(Entry e) {
        PendingVersion p = e.getPendingVersion();
        return p != null && p.getDescription() != null ? p.getDescription() : e.getDescription();
    }

    public static String effectiveResourceContent(Entry e) {
        PendingVersion p = e.getPendingVersion();
        return p != null && p.getResourceContent() != null ? p.getResourceContent() : e.getResourceContent();
    }

    public static List<String> effectiveTags(Entry e) {
        PendingVersion p = e.getPendingVersion();
        return p != null && p.getTags() != null ? p.getTags() : e.getTags();
    }

    public static String effectiveThumbnailR2Key(Entry e) {
        PendingVersion p = e.getPendingVersion();
        return p != null && p.getThumbnailR2Key() != null ? p.getThumbnailR2Key() : e.getThumbnailR2Key();
    }

    public static String effectivePreviewR2Key(Entry e) {
        PendingVersion p = e.getPendingVersion();
        return p != null && p.getPreviewR2Key() != null ? p.getPreviewR2Key() : e.getPreviewR2Key();
    }

    /**
     * Approval: the previous live state becomes a version (reconstructing v1
     * on the first approval, S-01), the slot is projected onto the entry as
     * {@code currentVersion + 1}, and the slot is cleared. {@code status} is
     * untouched. Thumbnail / preview variants are regenerated when those
     * keys changed.
     *
     * @return the entry after projection, or the unchanged entry when there was nothing pending
     */
    public Entry applyPendingVersion(String tenantId, Entry entry) {
        PendingVersion slot = entry.getPendingVersion();
        if (slot == null) {
            return entry;
        }
        int current = entry.currentVersionOrOne();
        List<EntryVersion> versions = entry.getVersions();
        if (versions.isEmpty()) {
            // "Absent = v1": the first approved edit materialises the history
            // with the reconstructed v1 (approvedAt = publishedAt).
            EntryVersion v1 = snapshot(tenantId, entry, current, entry.getPublishedAt(), List.of(), null);
            versions.add(v1);
        }

        boolean thumbnailChanged = slot.getThumbnailR2Key() != null
                && !Objects.equals(slot.getThumbnailR2Key(), entry.getThumbnailR2Key());
        boolean previewChanged = slot.getPreviewR2Key() != null
                && !Objects.equals(slot.getPreviewR2Key(), entry.getPreviewR2Key());

        if (slot.getTitle() != null) entry.setTitle(slot.getTitle());
        if (slot.getDescription() != null) entry.setDescription(slot.getDescription());
        if (slot.getResourceContent() != null) entry.setResourceContent(slot.getResourceContent());
        if (slot.getTags() != null) entry.setTags(new ArrayList<>(slot.getTags()));
        if (thumbnailChanged) {
            entry.setThumbnailR2Key(slot.getThumbnailR2Key());
            entry.setThumbnailVariantsPrefix(null);
        }
        if (previewChanged) {
            entry.setPreviewR2Key(slot.getPreviewR2Key());
            entry.setPreviewVariantsPrefix(null);
        }

        int next = current + 1;
        LocalDateTime now = LocalDateTime.now();
        versions.add(snapshot(tenantId, entry, next, now, slot.getChangeSummary(), slot.getCreatorNote()));
        entry.setVersions(versions);
        entry.setCurrentVersion(next);
        entry.setPendingVersion(null);
        entry.setModerationFeedback(null);
        entry.setUpdatedAt(now);
        Entry saved = entryRepository.save(entry);
        logger.info("pendingVersion: APPROVED entry={} tenant={} v{} → v{} parts={}",
                entry.getId(), tenantId, current, next, slot.getChangeSummary());

        if (thumbnailChanged || previewChanged) {
            try {
                thumbnailJobService.enqueueForEntry(tenantId, saved);
            } catch (Exception e) {
                logger.warn("pendingVersion: failed to enqueue thumbnail jobs for entry={} — {}",
                        entry.getId(), e.getMessage());
            }
        }
        return saved;
    }

    /**
     * Rejection ({@code CHANGES_REJECTED}): the slot is discarded, the reason
     * goes to {@code moderationFeedback}, nothing visible changes and the
     * status stays as it is (D-13). Never opens a dispute.
     */
    public Entry rejectPendingVersion(String tenantId, Entry entry, String reason) {
        if (entry.getPendingVersion() == null) {
            return entry;
        }
        entry.setPendingVersion(null);
        entry.setModerationFeedback(reason);
        entry.setUpdatedAt(LocalDateTime.now());
        Entry saved = entryRepository.save(entry);
        logger.info("pendingVersion: REJECTED entry={} tenant={} status={} reason={}",
                entry.getId(), tenantId, entry.getStatus(), reason);
        return saved;
    }

    private EntryVersion snapshot(String tenantId, Entry entry, int version, LocalDateTime approvedAt,
                                  List<String> changeSummary, String creatorNote) {
        EntryVersion v = new EntryVersion();
        v.setVersion(version);
        v.setAssetId(latestFullAssetId(tenantId, entry.getId()));
        v.setHlsR2Prefix(entry.getHlsR2Prefix());
        v.setTitle(entry.getTitle());
        v.setDescription(entry.getDescription());
        v.setResourceContent(entry.getResourceContent());
        v.setTags(entry.getTags() == null ? null : new ArrayList<>(entry.getTags()));
        v.setThumbnailR2Key(entry.getThumbnailR2Key());
        v.setPreviewR2Key(entry.getPreviewR2Key());
        v.setApprovedAt(approvedAt != null ? approvedAt : entry.getCreatedAt());
        v.setChangeSummary(changeSummary == null ? new ArrayList<>() : new ArrayList<>(changeSummary));
        v.setCreatorNote(creatorNote);
        return v;
    }

    /** Most recent FULL asset (READY preferred) — never a query that assumes uniqueness (H8). */
    private String latestFullAssetId(String tenantId, String entryId) {
        List<Asset> fulls = assetRepository.findByTenantIdAndEntryId(tenantId, entryId).stream()
                .filter(a -> a.getKind() == MediaKind.FULL)
                .toList();
        return fulls.stream()
                .filter(a -> a.getStatus() == AssetStatus.READY)
                .max(Comparator.comparing(Asset::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .or(() -> fulls.stream()
                        .max(Comparator.comparing(Asset::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))))
                .map(Asset::getId)
                .orElse(null);
    }
}
