package org.earnlumens.mediastore.web.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthCheckController {

    @Value("${spring.profiles.active:NOT_SET}")
    private String activeProfile;

    @Autowired
    private MongoTemplate mongoTemplate;

    @GetMapping("/public")
    public String getPublicContent() {
        try {
            long count = mongoTemplate.getCollection("founders").countDocuments();
            return "public content | profile=" + activeProfile + " | founders=" + count;
        } catch (Exception e) {
            return "public content | profile=" + activeProfile + " | db error: " + e.getMessage();
        }
    }
}
