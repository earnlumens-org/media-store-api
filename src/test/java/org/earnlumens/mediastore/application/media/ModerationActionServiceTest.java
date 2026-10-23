package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.ModerationBlock;
import org.earnlumens.mediastore.domain.media.model.ModerationJob;
import org.earnlumens.mediastore.domain.media.model.ModerationJobStatus;
import org.earnlumens.mediastore.domain.media.model.ModerationScope;
import org.earnlumens.mediastore.domain.media.model.PendingVersion;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.media.repository.ModerationJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Human-moderator paths (admin-api → internal surface): S-05 scope by object, D-13. */
class ModerationActionServiceTest {

    private static final String TENANT = "t1";
    private static final String ENTRY = "e1";

    private EntryRepository entryRepository;
    private ModerationJobRepository jobRepository;
    private ContentVersionService contentVersionService;
    private ContentModerationService contentModerationService;
    private ModerationJobService moderationJobService;
    private ModerationActionService service;

    @BeforeEach
    void setUp() {
        entryRepository = mock(EntryRepository.class);
        jobRepository = mock(ModerationJobRepository.class);
        contentVersionService = mock(ContentVersionService.class);
        contentModerationService = mock(ContentModerationService.class);
        moderationJobService = mock(ModerationJobService.class);
        when(entryRepository.save(any(Entry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jobRepository.findActiveByTenantIdAndEntryId(any(), any())).thenReturn(Optional.empty());
        when(contentVersionService.applyPendingVersion(any(), any(Entry.class))).thenAnswer(inv -> inv.getArgument(1));
        when(contentVersionService.rejectPendingVersion(any(), any(Entry.class), any())).thenAnswer(inv -> inv.getArgument(1));
        service = new ModerationActionService(entryRepository, jobRepository,
                contentVersionService, contentModerationService, moderationJobService);
    }

    private Entry inReview(EntryStatus previous) {
        Entry e = new Entry();
        e.setId(ENTRY);
        e.setTenantId(TENANT);
        e.setUserId("creator");
        e.setType(EntryType.IMAGE);
        e.setStatus(EntryStatus.IN_REVIEW);
        e.setPreviousStatus(previous);
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY)).thenReturn(Optional.of(e));
        return e;
    }

    @Test
    void approveFirstSubmission_goesToApproved() {
        Entry e = inReview(null);
        Entry out = service.approveContent(TENANT, ENTRY, "mod");
        assertThat(out.getStatus()).isEqualTo(EntryStatus.APPROVED);
        assertThat(out.getStatusHistory()).singleElement().satisfies(r -> assertThat(r.getActor()).isEqualTo("mod"));
        verify(moderationJobService).ensurePostApprovalMediaPipeline(TENANT, out);
        verifyNoInteractions(contentModerationService);
    }

    @Test
    void approveEscalatedLiveEntry_restoresItsPublicStatus() {
        inReview(EntryStatus.PUBLISHED);
        Entry out = service.approveContent(TENANT, ENTRY, "mod");
        assertThat(out.getStatus()).isEqualTo(EntryStatus.PUBLISHED);
        assertThat(out.getPreviousStatus()).isNull();
        assertThat(out.getModerationFeedback()).isNull();
    }

    @Test
    void approvePreventivelyBlockedEntry_isTheHumanReviewThatReinstates() {
        Entry e = inReview(EntryStatus.PUBLISHED);
        e.setModerationBlock(new ModerationBlock("case", "high severity", "EarnLumens", LocalDateTime.now()));
        Entry reinstated = new Entry();
        reinstated.setId(ENTRY);
        reinstated.setStatus(EntryStatus.PUBLISHED);
        when(contentModerationService.reinstateEntry(TENANT, ENTRY, "mod")).thenReturn(reinstated);

        Entry out = service.approveContent(TENANT, ENTRY, "mod");

        assertThat(out).isSameAs(reinstated);
        verify(contentModerationService).reinstateEntry(TENANT, ENTRY, "mod");
    }

    @Test
    void approveRequiresInReview() {
        Entry e = inReview(null);
        e.setStatus(EntryStatus.PUBLISHED);
        assertThatThrownBy(() -> service.approveContent(TENANT, ENTRY, "mod")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectFirstSubmission_isPlainRejected() {
        inReview(null);
        Entry out = service.rejectContent(TENANT, ENTRY, "Not allowed", "mod");
        assertThat(out.getStatus()).isEqualTo(EntryStatus.REJECTED);
        assertThat(out.getModerationFeedback()).isEqualTo("Not allowed");
        verifyNoInteractions(contentModerationService);
    }

    @Test
    void rejectEscalatedLiveEntry_isAContentTakedown() {
        Entry e = inReview(EntryStatus.PUBLISHED);
        Entry out = service.rejectContent(TENANT, ENTRY, "Stolen", "mod");
        assertThat(out.getStatus()).isEqualTo(EntryStatus.REJECTED);
        verify(contentModerationService).blockRejectedLiveEntry(TENANT, e, "Stolen", "mod");
    }

    @Test
    void rejectRequiresJustification() {
        inReview(null);
        assertThatThrownBy(() -> service.rejectContent(TENANT, ENTRY, "", "mod")).isInstanceOf(IllegalArgumentException.class);
        verify(entryRepository, never()).save(any());
    }

    @Test
    void takedownDelegatesWithJustification() {
        service.takedown(TENANT, ENTRY, "Illegal", "mod");
        verify(contentModerationService).takedownEntry(TENANT, ENTRY, "Illegal", "mod");
        assertThatThrownBy(() -> service.takedown(TENANT, ENTRY, " ", "mod")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approvePendingVersion_cancelsItsJobAndProjects_withoutTouchingStatus() {
        Entry e = inReview(null);
        e.setStatus(EntryStatus.PUBLISHED);
        PendingVersion slot = new PendingVersion();
        slot.setTitle("New");
        slot.setChangeSummary(new ArrayList<>(List.of("TITLE")));
        e.setPendingVersion(slot);
        ModerationJob job = new ModerationJob();
        job.setId("mod-1");
        job.setScope(ModerationScope.PENDING_VERSION);
        job.setStatus(ModerationJobStatus.COMPLETED);
        when(jobRepository.findActiveByTenantIdAndEntryId(TENANT, ENTRY)).thenReturn(Optional.of(job));

        service.approvePendingVersion(TENANT, ENTRY, "mod");

        verify(moderationJobService).cancelJob(eq(job), anyString());
        verify(contentVersionService).applyPendingVersion(TENANT, e);
        verifyNoInteractions(contentModerationService);
        assertThat(e.getStatus()).isEqualTo(EntryStatus.PUBLISHED);
    }

    @Test
    void rejectPendingVersion_discardsSlot_noDispute() {
        Entry e = inReview(null);
        e.setStatus(EntryStatus.PUBLISHED);
        e.setPendingVersion(new PendingVersion());

        service.rejectPendingVersion(TENANT, ENTRY, "Bad title", "mod");

        verify(contentVersionService).rejectPendingVersion(TENANT, e, "Bad title");
        verifyNoInteractions(contentModerationService);
    }

    @Test
    void pendingVersionActionsRequireASlot() {
        Entry e = inReview(null);
        e.setStatus(EntryStatus.PUBLISHED);
        assertThatThrownBy(() -> service.approvePendingVersion(TENANT, ENTRY, "mod")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.rejectPendingVersion(TENANT, ENTRY, "x", "mod")).isInstanceOf(IllegalStateException.class);
    }
}
