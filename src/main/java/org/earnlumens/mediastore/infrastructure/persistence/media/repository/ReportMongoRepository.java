package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import org.earnlumens.mediastore.infrastructure.persistence.media.entity.ReportEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReportMongoRepository extends MongoRepository<ReportEntity, String> {

    /** Check if user already reported this entry. */
    boolean existsByTenantIdAndReporterUserIdAndEntryId(String tenantId, String reporterUserId, String entryId);

    /** Count this user's reports in a time window (rate-limiting). */
    long countByTenantIdAndReporterUserIdAndCreatedAtAfter(String tenantId, String reporterUserId, LocalDateTime after);

    /** All reports for an entry (for aggregation / threshold). */
    List<ReportEntity> findByTenantIdAndEntryId(String tenantId, String entryId);

    /** Count distinct reports for an entry. */
    long countByTenantIdAndEntryId(String tenantId, String entryId);

    /** Count total reports ever filed by this user. */
    long countByTenantIdAndReporterUserId(String tenantId, String reporterUserId);

    /** Count how many reports by this user were dismissed (abuse indicator). */
    long countByTenantIdAndReporterUserIdAndResolution(String tenantId, String reporterUserId, String resolution);

    /** All reports against entries by a specific creator (creator history). */
    long countByTenantIdAndCreatorUserId(String tenantId, String creatorUserId);

    /** Count resolved reports against a creator where content was removed/sanctioned. */
    long countByTenantIdAndCreatorUserIdAndResolutionIn(String tenantId, String creatorUserId, List<String> resolutions);
}
