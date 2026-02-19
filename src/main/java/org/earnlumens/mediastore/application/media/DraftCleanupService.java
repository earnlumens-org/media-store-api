package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.response.CleanupResult;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.repository.AssetRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
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
 */
@Service
public class DraftCleanupService {

    private static final Logger logger = LoggerFactory.getLogger(DraftCleanupService.class);
    private static final int ORPHAN_THRESHOLD_HOURS = 24;

    private final EntryRepository entryRepository;
    private final AssetRepository assetRepository;

    public DraftCleanupService(EntryRepository entryRepository, AssetRepository assetRepository) {
        this.entryRepository = entryRepository;
        this.assetRepository = assetRepository;
    }

    /**
     * Finds and deletes orphaned DRAFT entries older than 24 hours with no assets.
     *
     * @return statistics about the cleanup operation
     */
    public CleanupResult cleanOrphanedDrafts() {
        long start = System.currentTimeMillis();
        LocalDateTime cutoff = LocalDateTime.now().minusHours(ORPHAN_THRESHOLD_HOURS);

        List<Entry> staleDrafts = entryRepository.findByStatusAndCreatedAtBefore(EntryStatus.DRAFT, cutoff);

        logger.info("Cleanup: found {} stale DRAFT entries older than {}", staleDrafts.size(), cutoff);

        Map<String, Integer> byType = new HashMap<>();
        int deleted = 0;

        for (Entry entry : staleDrafts) {
            boolean hasAssets = !assetRepository
                    .findByTenantIdAndEntryId(entry.getTenantId(), entry.getId())
                    .isEmpty();

            if (!hasAssets) {
                entryRepository.deleteById(entry.getId());
                byType.merge(entry.getType().name(), 1, Integer::sum);
                deleted++;
                logger.debug("Cleanup: deleted orphaned entry id={}, type={}, tenant={}, createdAt={}",
                        entry.getId(), entry.getType(), entry.getTenantId(), entry.getCreatedAt());
            }
        }

        long durationMs = System.currentTimeMillis() - start;
        logger.info("Cleanup complete: deleted {} orphaned drafts in {}ms — byType={}",
                deleted, durationMs, byType);

        return new CleanupResult(deleted, byType, cutoff, durationMs);
    }
}
