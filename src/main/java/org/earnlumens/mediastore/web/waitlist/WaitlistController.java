package org.earnlumens.mediastore.web.waitlist;

import jakarta.validation.Valid;
import org.earnlumens.mediastore.application.waitlist.WaitlistService;
import org.earnlumens.mediastore.domain.waitlist.dto.request.WaitlistRequest;
import org.earnlumens.mediastore.domain.waitlist.dto.response.MessageResponse;
import org.earnlumens.mediastore.domain.waitlist.dto.response.WaitlistStatsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/waitlist")
public class WaitlistController {

    private static final Logger log = LoggerFactory.getLogger(WaitlistController.class);

    private final WaitlistService waitlistService;

    public WaitlistController(WaitlistService waitlistService) {
        this.waitlistService = waitlistService;
    }

    @PostMapping("/subscribe")
    public ResponseEntity<?> subscribe(@Valid @RequestBody WaitlistRequest waitlistRequest) {
        try {
            waitlistService.register(waitlistRequest);
            return ResponseEntity.ok(new MessageResponse("User registered successfully!"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new MessageResponse("Error: Captcha error, try again!"));
        } catch (Exception ex) {
            log.error("Waitlist subscribe failed", ex);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        try {
            WaitlistStatsResponse stats = waitlistService.getStats();
            return ResponseEntity.ok(stats);
        } catch (Exception ex) {
            log.error("Waitlist stats failed", ex);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
