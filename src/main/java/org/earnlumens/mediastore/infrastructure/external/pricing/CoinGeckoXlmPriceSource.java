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
 * Fetches XLM/USD from CoinGecko public API.
 * <p>
 * Endpoint: GET https://api.coingecko.com/api/v3/simple/price?ids=stellar&amp;vs_currencies=usd
 * Response: {"stellar":{"usd":0.1234}}
 */
@Component
@Order(3)
public class CoinGeckoXlmPriceSource implements XlmUsdPriceSource {

    private static final Logger logger = LoggerFactory.getLogger(CoinGeckoXlmPriceSource.class);
    private static final String URL = "https://api.coingecko.com/api/v3/simple/price?ids=stellar&vs_currencies=usd";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public CoinGeckoXlmPriceSource(@Qualifier("pricingHttpClient") HttpClient httpClient) {
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String name() {
        return "coingecko";
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
            JsonNode usdNode = root.path("stellar").path("usd");

            if (usdNode.isMissingNode() || usdNode.isNull()) {
                logger.warn("[{}] {} — missing stellar.usd in response", name(), Instant.now());
                return Optional.empty();
            }

            // Use asText() → BigDecimal to preserve precision (avoids double rounding)
            BigDecimal price = new BigDecimal(usdNode.asText());
            logger.debug("[{}] fetched price: {}", name(), price);
            return Optional.of(price);

        } catch (Exception e) {
            logger.error("[{}] {} — failed to fetch price: {}", name(), Instant.now(), e.getMessage());
            return Optional.empty();
        }
    }
}
