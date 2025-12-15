package org.earnlumens.mediastore.web.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthCheckController {

    @Value("${spring.profiles.active:NOT_SET}")
    private String activeProfile;

    @GetMapping("/public")
    public String getPublicContent() {
        return "public content | profile=" + activeProfile;
    }
}
