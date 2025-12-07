package org.earnlumens.media_store_api.web.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthCheckController {

    @GetMapping("/public")
    public String getPublicContent() {
        return "public content";
    }
}
