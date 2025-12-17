package org.earnlumens.mediastore.web.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
public class HealthCheckController {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckController.class);

    @Value("${spring.profiles.active:NOT_SET}")
    private String activeProfile;

    @Autowired
    private MongoTemplate mongoTemplate;

    @GetMapping("/public")
    public String getPublicContent() {
        try {
            long count = mongoTemplate.getCollection("founders").countDocuments();
            return "public content | profile=" + activeProfile + " | founders in db=" + count;
        } catch (Exception e) {
            log.warn("HealthCheck Mongo check failed", e);
            return "public content | profile=" + activeProfile + " | db error";
        }
    }
}
