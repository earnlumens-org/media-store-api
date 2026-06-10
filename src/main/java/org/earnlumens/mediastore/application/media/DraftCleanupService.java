package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.response.CleanupResult;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.UploadSession;
import org.earnlumens.mediastore.domain.media.repository.AssetRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.media.repository.UploadSessionRepository;
import org.earnlumens.mediastore.infrastructure.r2.R2StorageService;
import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cleans up orphaned DRAFT entries that have no associated assets.
 * An entry is considered orphaned if it has been in DRAFT status for more than 24 hours
 * and has zero assets (i.e. the upload was never completed).
 * <p>
 * The 24-hour window ensures entries being actively uploaded are never touched.
 * <p>
 * Also reclaims R2 storage: bytes uploaded for an orphaned draft (the upload
 * succeeded but finalize never ran) and stale upload sessions (abandoned
 * single-PUT objects and incomplete multipart uploads).
 */
@Service
public class DraftCleanupService {

    private static final Logger logger = LoggerFactory.getLogger(DraftCleanupService.class);
    private static final int ORPHAN_THRESHOLD_HOURS = 24;
    private static final int STALE_SESSION_THRESHOLD_HOURS = 48;

    private final EntryRepository entryRepository;
    private final AssetRepository assetRepository;
    private final UploadSessionRepository uploadSessionRepository;
    private final R2StorageService r2StorageService;

    public DraftCleanupService(EntryRepository entryRepository,
                               AssetRepository assetRepository,
                               UploadSessionRepository uploadSessionRepository,
                               R2StorageService r2StorageService) {
        this.entryRepository = entryRepository;
        this.assetRepository = assetRepository;
        this.uploadSessionRepository = uploadSessionRepository;
        this.r2StorageService = r2StorageService;
    }

    /**
     * Finds and deletes orphaned DRAFT entries older than 24 hours with no assets.
     *
     * @return statistics about the cleanup operation
     */
    public CleanupResult cleanOrphanedDrafts() {
        long start = System.currentTimeMillis();
        String tenantId = TenantContext.require();
        LocalDateTime cutoff = LocalDateTime.now().minusHours(ORPHAN_THRESHOLD_HOURS);

        List<Entry> staleDrafts = entryRepository.findByTenantIdAndStatusAndCreatedAtBefore(tenantId, EntryStatus.DRAFT, cutoff);

        logger.info("Cleanup: found {} stale DRAFT entries older than {} for tenant={}", staleDrafts.size(), cutoff, tenantId);

        Map<String, Integer> byType = new HashMap<>();
        int deleted = 0;

        for (Entry entry : staleDrafts) {
            boolean hasAssets = !assetRepository
                    .findByTenantIdAndEntryId(entry.getTenantId(), entry.getId())
                    .isEmpty();

            if (!hasAssets) {
                // Reclaim any bytes that were uploaded but never finalized
                // (the upload PUT succeeded but /finalize never ran).
                try {
                    r2StorageService.deleteByPrefix("private/media/" + entry.getId() + "/");
                    r2StorageService.deleteByPrefix("public/media/" + entry.getId() + "/");
                } catch (Exception e) {
                    logger.warn("Cleanup: R2 prefix delete failed for entry={}: {}", entry.getId(), e.getMessage());
                }

                entryRepository.deleteByTenantIdAndId(entry.getTenantId(), entry.getId());
                byType.merge(entry.getType().name(), 1, Integer::sum);
                deleted++;
                logger.debug("Cleanup: deleted orphaned entry id={}, type={}, tenant={}, createdAt={}",
                        entry.getId(), entry.getType(), entry.getTenantId(), entry.getCreatedAt());
            }
        }

        int staleSessions = cleanStaleUploadSessions();

        long durationMs = System.currentTimeMillis() - start;
        logger.info("Cleanup complete: deleted {} orphaned drafts, {} stale upload sessions in {}ms — byType={}",
                deleted, staleSessions, durationMs, byType);

        return new CleanupResult(deleted, byType, cutoff, durationMs);
    }

    /**
     * Aborts and removes upload sessions that never completed: frees stored
     * multipart parts (R2 bills for them) and deletes abandoned single-PUT
     * objects whose entry was never finalized.
     *
     * @return number of sessions cleaned
     */
    private int cleanStaleUploadSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(STALE_SESSION_THRESHOLD_HOURS);
        List<UploadSession> stale = uploadSessionRepository
                .findByStatusAndCreatedAtBefore(UploadSession.Status.PENDING, cutoff);

        int cleaned = 0;
        for (UploadSession session : stale) {
            try {
                if (session.isMultipart() && session.getS3UploadId() != null) {
                    r2StorageService.abortMultipartUpload(session.getR2Key(), session.getS3UploadId());
                } else if (session.getR2Key() != null) {
                    // Only delete if no asset references this key (finalize never ran).
                    boolean referenced = assetRepository
                            .findByTenantIdAndEntryId(session.getTenantId(), session.getEntryId())
                            .stream()
                            .anyMatch(a -> session.getR2Key().equals(a.getR2Key()));
                    if (!referenced) {
                        r2StorageService.deleteObject(session.getR2Key());
                    }
                }
                session.setStatus(UploadSession.Status.ABORTED);
                uploadSessionRepository.save(session);
                cleaned++;
            } catch (Exception e) {
                logger.warn("Cleanup: stale session {} cleanup failed: {}", session.getId(), e.getMessage());
            }
        }
        return cleaned;
    }
}
