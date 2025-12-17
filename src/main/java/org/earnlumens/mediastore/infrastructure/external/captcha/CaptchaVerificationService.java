package org.earnlumens.mediastore.infrastructure.external.captcha;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class CaptchaVerificationService {

    private final String hCaptchaSecretKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public CaptchaVerificationService(
            @Value("${mediastore.sec.hcaptcha:}") String hCaptchaSecretKey,
            ObjectMapper objectMapper
    ) {
        this.hCaptchaSecretKey = hCaptchaSecretKey;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public boolean verify(String captchaResponse) {
        if (!StringUtils.hasText(hCaptchaSecretKey)) {
            throw new IllegalStateException("hCaptcha secret not configured (mediastore.sec.hcaptcha)");
        }
        if (!StringUtils.hasText(captchaResponse)) {
            return false;
        }

        try {
            var sb = new StringBuilder();
            sb.append("response=").append(captchaResponse);
            sb.append("&secret=").append(this.hCaptchaSecretKey);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://hcaptcha.com/siteverify"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(sb.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode hCaptchaResponseObject = this.objectMapper.readTree(response.body());
            JsonNode successNode = hCaptchaResponseObject.get("success");
            return successNode != null && successNode.asBoolean(false);
        } catch (Exception ex) {
            return false;
        }
    }
}
