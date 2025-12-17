package org.earnlumens.mediastore.application.waitlist;

import org.earnlumens.mediastore.domain.waitlist.dto.request.WaitlistRequest;
import org.earnlumens.mediastore.domain.waitlist.dto.response.WaitlistStatsResponse;
import org.earnlumens.mediastore.domain.waitlist.model.Feedback;
import org.earnlumens.mediastore.domain.waitlist.model.Founder;
import org.earnlumens.mediastore.domain.waitlist.repository.FeedbackRepository;
import org.earnlumens.mediastore.domain.waitlist.repository.FounderRepository;
import org.earnlumens.mediastore.infrastructure.external.captcha.CaptchaVerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WaitlistServiceTest {

    @Mock
    private FounderRepository founderRepository;

    @Mock
    private FeedbackRepository feedbackRepository;

    @Mock
    private CaptchaVerificationService captchaVerificationService;

    private WaitlistService waitlistService;

    @BeforeEach
    void setUp() {
        waitlistService = new WaitlistService(founderRepository, feedbackRepository, captchaVerificationService);
    }

    @Test
    void register_whenCaptchaInvalid_throwsIllegalArgumentException() {
        WaitlistRequest request = new WaitlistRequest();
        request.setEmail("test@example.com");
        request.setCaptchaResponse("bad-token");
        request.setFeedback("hello");

        when(captchaVerificationService.verify(anyString())).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> waitlistService.register(request));

        verifyNoInteractions(founderRepository);
        verifyNoInteractions(feedbackRepository);
    }

    @Test
    void register_whenNewEmailAndFeedback_savesFounderAndFeedback() {
        WaitlistRequest request = new WaitlistRequest();
        request.setEmail("new@example.com");
        request.setCaptchaResponse("ok-token");
        request.setFeedback("I want this");

        when(captchaVerificationService.verify(anyString())).thenReturn(true);
        when(founderRepository.existsByEmail("new@example.com")).thenReturn(false);

        Founder savedFounder = new Founder("new@example.com");
        savedFounder.setId("founder-1");
        when(founderRepository.save(any(Founder.class))).thenReturn(savedFounder);

        waitlistService.register(request);

        verify(founderRepository).save(any(Founder.class));

        ArgumentCaptor<Feedback> feedbackCaptor = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(feedbackCaptor.capture());
        Feedback savedFeedback = feedbackCaptor.getValue();
        assertEquals("founder-1", savedFeedback.getUserId());
        assertEquals("I want this", savedFeedback.getFeedback());
    }

    @Test
    void register_whenExistingEmailAndFeedback_savesFeedbackWithExistingFounderId() {
        WaitlistRequest request = new WaitlistRequest();
        request.setEmail("existing@example.com");
        request.setCaptchaResponse("ok-token");
        request.setFeedback("more info");

        when(captchaVerificationService.verify(anyString())).thenReturn(true);
        when(founderRepository.existsByEmail("existing@example.com")).thenReturn(true);

        Founder existing = new Founder("existing@example.com");
        existing.setId("founder-99");
        when(founderRepository.findByEmail("existing@example.com")).thenReturn(Optional.of(existing));

        waitlistService.register(request);

        verify(founderRepository, never()).save(any());

        ArgumentCaptor<Feedback> feedbackCaptor = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(feedbackCaptor.capture());
        assertEquals("founder-99", feedbackCaptor.getValue().getUserId());
        assertEquals("more info", feedbackCaptor.getValue().getFeedback());
    }

    @Test
    void register_whenExistingEmailAndBlankFeedback_doesNotSaveFeedback() {
        WaitlistRequest request = new WaitlistRequest();
        request.setEmail("existing@example.com");
        request.setCaptchaResponse("ok-token");
        request.setFeedback("   ");

        when(captchaVerificationService.verify(anyString())).thenReturn(true);
        when(founderRepository.existsByEmail("existing@example.com")).thenReturn(true);

        Founder existing = new Founder("existing@example.com");
        existing.setId("founder-99");
        when(founderRepository.findByEmail("existing@example.com")).thenReturn(Optional.of(existing));

        waitlistService.register(request);

        verify(feedbackRepository, never()).save(any());
        verify(founderRepository, never()).save(any());
    }

    @Test
    void getStats_whenNoGroupedCounts_returns15DaysWithSameTotal() {
        when(founderRepository.count()).thenReturn(10L);
        when(founderRepository.countFoundersGroupedByEntryDate(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(Collections.emptyList());

        WaitlistStatsResponse response = waitlistService.getStats();

        assertNotNull(response);
        assertNotNull(response.getStats());
        assertEquals(15, response.getStats().size());
        assertTrue(response.getStats().values().stream().allMatch(v -> v == 10L));

        verify(founderRepository).count();
        verify(founderRepository).countFoundersGroupedByEntryDate(any(LocalDateTime.class), any(LocalDateTime.class));
    }
}
