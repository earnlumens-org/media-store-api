package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.ReportReason;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.ReportEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.ReportMongoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * H3 action 3: report escalation keeps buyers' access (IN_REVIEW without a
 * block) unless the aggregate severity is HIGH, which places a PREVENTIVE
 * block through {@link ContentModerationService}.
 */
class ReportServiceEscalationTest {

    private static final String TENANT = "t1";
    private static final String ENTRY = "e1";

    private ReportMongoRepository reports;
    private EntryRepository entries;
    private ContentModerationService moderation;
    private ReportService service;
    private Entry entry;

    @BeforeEach
    void setUp() {
        reports = mock(ReportMongoRepository.class);
        entries = mock(EntryRepository.class);
        moderation = mock(ContentModerationService.class);
        service = new ReportService(reports, entries, mock(CollectionRepository.class), moderation);

        entry = new Entry();
        entry.setId(ENTRY);
        entry.setTenantId(TENANT);
        entry.setUserId("creator");
        entry.setType(EntryType.VIDEO);
        entry.setStatus(EntryStatus.PUBLISHED);
        entry.setPaid(true);
        when(entries.findByTenantIdAndId(TENANT, ENTRY)).thenReturn(Optional.of(entry));
        when(entries.save(any(Entry.class))).thenAnswer(inv -> inv.getArgument(0));

        when(reports.existsByTenantIdAndReporterUserIdAndEntryId(any(), any(), any())).thenReturn(false);
        when(reports.countByTenantIdAndReporterUserIdAndCreatedAtAfter(any(), any(), any())).thenReturn(0L);
        when(reports.countByTenantIdAndReporterUserId(any(), any())).thenReturn(0L);
        when(reports.countByTenantIdAndCreatorUserId(any(), any())).thenReturn(0L);
        when(reports.countByTenantIdAndCreatorUserIdAndResolutionIn(any(), any(), any())).thenReturn(0L);
        when(reports.findByTenantIdAndEntryId(any(), any())).thenReturn(List.of());
        when(reports.save(any(ReportEntity.class))).thenAnswer(inv -> {
            ReportEntity r = inv.getArgument(0);
            r.setId("rep-1");
            return r;
        });
    }

    @Test
    void lowSeverityEscalation_movesToInReviewWithoutBlock() {
        // Third distinct report ⇒ escalation by count; no HIGH report on record
        when(reports.countByTenantIdAndEntryId(TENANT, ENTRY)).thenReturn(3L);
        when(reports.countByTenantIdAndEntryIdAndSeverity(TENANT, ENTRY, "HIGH")).thenReturn(0L);

        service.submitReport(TENANT, "reporter", "rep", ENTRY, ReportReason.OFF_TOPIC, null);

        assertThat(entry.getStatus()).isEqualTo(EntryStatus.IN_REVIEW);
        assertThat(entry.getPreviousStatus()).isEqualTo(EntryStatus.PUBLISHED);
        assertThat(entry.isBlocked()).isFalse();
        verifyNoInteractions(moderation);
    }

    @Test
    void highSeverityEscalation_placesPreventiveBlock() {
        when(reports.countByTenantIdAndEntryId(TENANT, ENTRY)).thenReturn(3L);
        when(reports.countByTenantIdAndEntryIdAndSeverity(TENANT, ENTRY, "HIGH")).thenReturn(1L);

        service.submitReport(TENANT, "reporter", "rep", ENTRY, ReportReason.CSAM, null);

        verify(moderation).preventiveBlockEntry(eq(TENANT), eq(entry), anyString(), eq("EarnLumens"));
        verify(entries, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void belowThreshold_noEscalation() {
        when(reports.countByTenantIdAndEntryId(TENANT, ENTRY)).thenReturn(1L);

        service.submitReport(TENANT, "reporter", "rep", ENTRY, ReportReason.OTHER, null);

        assertThat(entry.getStatus()).isEqualTo(EntryStatus.PUBLISHED);
        verifyNoInteractions(moderation);
    }
}
