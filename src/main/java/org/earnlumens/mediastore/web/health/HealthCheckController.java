package org.earnlumens.mediastore.web.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthCheckController {

    @GetMapping("/public")
    public String getPublicContent() {
        // Intentionally generic: avoid leaking environment/DB status.
        return "XX days for reset";
    }
}
