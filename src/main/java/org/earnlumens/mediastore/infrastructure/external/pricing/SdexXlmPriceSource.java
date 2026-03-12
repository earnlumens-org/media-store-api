package org.earnlumens.mediastore.infrastructure.external.pricing;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Fetches XLM/USD from the Stellar Decentralized Exchange (SDEX) orderbook.
 * <p>
 * Queries the Horizon {@code /order_book} endpoint for XLM (native) → USDC.
 * Computes the mid-market price: average of the best bid and best ask.
 * <p>
 * This source is <b>not</b> annotated with {@code @Order} because it is used
 * as the primary source outside the regular source rotation (see {@link XlmUsdPriceService}).
 * It does not participate in the {@code List<XlmUsdPriceSource>} injection.
 */
@Component
public class SdexXlmPriceSource {

    private static final Logger logger = LoggerFactory.getLogger(SdexXlmPriceSource.class);

    /**
     * Centre.io USDC issuer on Stellar mainnet.
     * On testnet, the orderbook will likely be empty — the caller handles the empty result gracefully.
     */
    private static final String USDC_ISSUER = "GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String horizonUrl;

    @Autowired
    public SdexXlmPriceSource(
            @Qualifier("pricingHttpClient") HttpClient httpClient,
            org.earnlumens.mediastore.infrastructure.config.StellarConfig stellarConfig) {
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
        // Use the configured Horizon URL (testnet or mainnet) from StellarConfig
        this.horizonUrl = stellarConfig.getHorizonUrl();
    }

    /** Package-private constructor for testing. */
    SdexXlmPriceSource(HttpClient httpClient, String horizonUrl) {
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
        this.horizonUrl = horizonUrl;
    }

    public String name() {
        return "sdex";
    }

    /**
     * Fetch the mid-market XLM/USD price from the SDEX orderbook.
     * <p>
     * The orderbook is queried as: selling = native (XLM), buying = USDC.
     * The best bid price = how much USDC someone will pay per XLM.
     * The best ask price = how much USDC someone wants per XLM.
     * Mid-market = (best_bid + best_ask) / 2.
     *
     * @return the mid-market price, or empty if unavailable / no liquidity
     */
    public Optional<BigDecimal> fetchPrice() {
        String url = horizonUrl + "/order_book"
                + "?selling_asset_type=native"
                + "&buying_asset_type=credit_alphanum4"
                + "&buying_asset_code=USDC"
                + "&buying_asset_issuer=" + USDC_ISSUER
                + "&limit=1";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.warn("[{}] {} — HTTP {} from Horizon orderbook", name(), Instant.now(), response.statusCode());
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(response.body());

            // bids = buyers of XLM (selling USDC) → "price" = USDC per XLM
            JsonNode bids = root.path("bids");
            JsonNode asks = root.path("asks");

            if (!bids.isArray() || bids.isEmpty() || !asks.isArray() || asks.isEmpty()) {
                logger.warn("[{}] {} — empty orderbook (no bids or asks)", name(), Instant.now());
                return Optional.empty();
            }

            BigDecimal bestBid = new BigDecimal(bids.get(0).path("price").asText());
            BigDecimal bestAsk = new BigDecimal(asks.get(0).path("price").asText());

            if (bestBid.compareTo(BigDecimal.ZERO) <= 0 || bestAsk.compareTo(BigDecimal.ZERO) <= 0) {
                logger.warn("[{}] {} — invalid bid/ask prices: bid={}, ask={}", name(), Instant.now(), bestBid, bestAsk);
                return Optional.empty();
            }

            BigDecimal midPrice = bestBid.add(bestAsk).divide(BigDecimal.valueOf(2), 7, RoundingMode.HALF_UP);
            logger.debug("[{}] fetched price: {} (bid={}, ask={})", name(), midPrice, bestBid, bestAsk);
            return Optional.of(midPrice);

        } catch (Exception e) {
            logger.error("[{}] {} — failed to fetch SDEX price: {}", name(), Instant.now(), e.getMessage());
            return Optional.empty();
        }
    }
}
