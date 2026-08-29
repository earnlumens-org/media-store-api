package org.earnlumens.mediastore.infrastructure.external.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Notifies admin-api that a plan order reached COMPLETED so the Pro plan is
 * applied to the tenant immediately (custom-domain-upgrade 1B.3).
 *
 * <p>Best-effort by design: any failure here is only a latency problem —
 * admin-api's {@code PlanOrderSweepTask} re-drives COMPLETED-but-unapplied
 * orders every 5 minutes, so this call is never retried nor blocks the
 * payment response. Mirrors the {@code TenantCacheInvalidator} pattern used
 * in the opposite direction.
 *
 * <p><b>Security:</b> authenticates with the shared secret {@code X-Plan-Secret}
 * ({@code mediastore.adminApi.planApplySecret} ↔ Secret Manager
 * {@code plan-apply-secret}); refuses to call while unconfigured. The target
 * is admin-api's {@code /api/internal/**} surface, exempt from edge auth, so
 * the direct {@code .run.app} URL works.
 */
@Component
public class PlanApplyClient {

    private static final Logger logger = LoggerFactory.getLogger(PlanApplyClient.class);
    private static final String SECRET_PLACEHOLDER = "CHANGE_ME_IN_ENV";

    private final String baseUrl;
    private final String sharedSecret;
    private final HttpClient http;

    public PlanApplyClient(
            @Value("${mediastore.adminApi.baseUrl:}") String baseUrl,
            @Value("${mediastore.adminApi.planApplySecret:}") String sharedSecret) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.sharedSecret = sharedSecret == null ? "" : sharedSecret;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /** Fire-and-forget apply callback. Returns true when admin-api confirmed application. */
    public boolean apply(String orderId) {
        if (orderId == null || orderId.isBlank()) return false;
        if (baseUrl.isEmpty()) {
            logger.warn("PlanApplyClient: skipping — mediastore.adminApi.baseUrl is not configured "
                    + "(the admin-api sweep will apply order {})", orderId);
            return false;
        }
        if (sharedSecret.isBlank() || SECRET_PLACEHOLDER.equals(sharedSecret)) {
            logger.warn("PlanApplyClient: skipping — mediastore.adminApi.planApplySecret is not configured "
                    + "(the admin-api sweep will apply order {})", orderId);
            return false;
        }
        try {
            // orderId is a Mongo ObjectId hex string — reject anything else so
            // the hand-built JSON below can never be injected into.
            if (!orderId.matches("^[A-Za-z0-9-]{1,64}$")) {
                logger.warn("PlanApplyClient: refusing — orderId has unexpected charset");
                return false;
            }
            String body = "{\"orderId\":\"" + orderId + "\"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/internal/plan/apply"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Plan-Secret", sharedSecret)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 == 2) {
                logger.info("PlanApplyClient: plan apply acknowledged for order {}", orderId);
                return true;
            }
            logger.warn("PlanApplyClient: admin-api returned status={} for order {} (sweep will retry)",
                    resp.statusCode(), orderId);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logger.warn("PlanApplyClient: interrupted while applying order {}", orderId);
        } catch (Exception ex) {
            logger.warn("PlanApplyClient: apply call failed for order {} (sweep will retry): {}",
                    orderId, ex.getMessage());
        }
        return false;
    }
}
