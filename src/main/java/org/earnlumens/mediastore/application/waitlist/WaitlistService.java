package org.earnlumens.mediastore.application.waitlist;

import org.earnlumens.mediastore.domain.waitlist.dto.request.WaitlistRequest;
import org.earnlumens.mediastore.domain.waitlist.dto.response.WaitlistStatsResponse;
import org.earnlumens.mediastore.domain.waitlist.model.Feedback;
import org.earnlumens.mediastore.domain.waitlist.model.Founder;
import org.earnlumens.mediastore.domain.waitlist.model.FounderCountByDate;
import org.earnlumens.mediastore.domain.waitlist.repository.FeedbackRepository;
import org.earnlumens.mediastore.domain.waitlist.repository.FounderRepository;
import org.earnlumens.mediastore.infrastructure.external.captcha.CaptchaVerificationService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class WaitlistService {

    private final FounderRepository founderRepository;
    private final FeedbackRepository feedbackRepository;
    private final CaptchaVerificationService captchaVerificationService;

    public WaitlistService(
            FounderRepository founderRepository,
            FeedbackRepository feedbackRepository,
            CaptchaVerificationService captchaVerificationService
    ) {
        this.founderRepository = founderRepository;
        this.feedbackRepository = feedbackRepository;
        this.captchaVerificationService = captchaVerificationService;
    }

    public void register(WaitlistRequest waitlistRequest) {
        boolean captchaSuccess = captchaVerificationService.verify(waitlistRequest.getCaptchaResponse());
        if (!captchaSuccess) {
            throw new IllegalArgumentException("CAPTCHA_INVALID");
        }

        String email = waitlistRequest.getEmail();
        String feedback = waitlistRequest.getFeedback();

        if (founderRepository.existsByEmail(email)) {
            Optional<Founder> founder = founderRepository.findByEmail(email);
            if (founder.isPresent() && StringUtils.hasText(feedback)) {
                feedbackRepository.save(new Feedback(founder.get().getId(), feedback));
            }
            return;
        }

        Founder founder = founderRepository.save(new Founder(email));
        if (StringUtils.hasText(feedback)) {
            feedbackRepository.save(new Feedback(founder.getId(), feedback));
        }
    }

    public WaitlistStatsResponse getStats() {
        long founderCount = founderRepository.count();

        LocalDate today = LocalDate.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        Map<String, Long> map = new LinkedHashMap<>();
        for (int i = 1; i <= 15; i++) {
            LocalDate date = today.minusDays(15L - i);
            String dateString = date.format(formatter);
            map.put(dateString, -1L);
        }

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(14);
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.atTime(23, 59, 59);

        List<FounderCountByDate> fcList = founderRepository.countFoundersGroupedByEntryDate(startDateTime, endDateTime);
        for (int i = fcList.size() - 1; i >= 0; i--) {
            FounderCountByDate fc = fcList.get(i);
            LocalDate newEntryDate = fc.getEntryDate().plusDays(1).toLocalDate();
            map.put(newEntryDate.toString(), founderCount);
            founderCount = founderCount - fc.getTotalFounders();
        }

        for (Map.Entry<String, Long> entry : map.entrySet()) {
            String key = entry.getKey();
            Long value = entry.getValue();
            if (value == -1L) {
                map.put(key, founderCount);
            } else {
                founderCount = value;
            }
        }

        return new WaitlistStatsResponse(map);
    }
}
