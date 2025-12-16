package org.earnlumens.mediastore.web.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class HealthCheckControllerTest {

    @Autowired
    private HealthCheckController healthCheckController;

    @Test
    void getPublicContent_ShouldReturnPublicContentWithProfile() {
        // When
        String result = healthCheckController.getPublicContent();

        // Then
        assertNotNull(result);
        assertTrue(result.startsWith("public content | profile="));
    }
}
