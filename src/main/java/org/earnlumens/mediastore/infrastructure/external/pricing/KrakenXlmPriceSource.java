package org.earnlumens.mediastore.infrastructure.external.pricing;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Fetches XLM/USD from Kraken public API.
 * <p>
 * Endpoint: GET https://api.kraken.com/0/public/Ticker?pair=XLMUSD
 * Response: {"error":[], "result":{"XXLMZUSD":{"c":["0.1234","123.456"],...}}}
 * Field "c" = last trade closed: [price, lot_volume]
 */
@Component
@Order(2)
public class KrakenXlmPriceSource implements XlmUsdPriceSource {

    private static final Logger logger = LoggerFactory.getLogger(KrakenXlmPriceSource.class);
    private static final String URL = "https://api.kraken.com/0/public/Ticker?pair=XLMUSD";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public KrakenXlmPriceSource(@Qualifier("pricingHttpClient") HttpClient httpClient) {
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String name() {
        return "kraken";
    }

    @Override
    public Optional<BigDecimal> fetchPrice() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(URL))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.warn("[{}] {} — HTTP {} at {}", name(), Instant.now(), response.statusCode(), URL);
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(response.body());

            // Check for Kraken API errors
            JsonNode errors = root.path("error");
            if (errors.isArray() && !errors.isEmpty()) {
                logger.warn("[{}] {} — API errors: {}", name(), Instant.now(), errors);
                return Optional.empty();
            }

            // Find the XLM pair (Kraken uses "XXLMZUSD" internally)
            JsonNode result = root.path("result");
            for (String key : result.propertyNames()) {
                if (key.contains("XLM")) {
                    JsonNode lastTrade = result.path(key).path("c");
                    if (lastTrade.isArray() && !lastTrade.isEmpty()) {
                        String priceStr = lastTrade.get(0).asText(null);
                        if (priceStr != null && !priceStr.isBlank()) {
                            BigDecimal price = new BigDecimal(priceStr);
                            logger.debug("[{}] fetched price: {}", name(), price);
                            return Optional.of(price);
                        }
                    }
                }
            }

            logger.warn("[{}] {} — XLM pair not found in response", name(), Instant.now());
            return Optional.empty();

        } catch (Exception e) {
            logger.error("[{}] {} — failed to fetch price: {}", name(), Instant.now(), e.getMessage());
            return Optional.empty();
        }
    }
}
