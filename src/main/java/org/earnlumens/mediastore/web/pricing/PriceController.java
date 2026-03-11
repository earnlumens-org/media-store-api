package org.earnlumens.mediastore.web.pricing;

import org.earnlumens.mediastore.infrastructure.external.pricing.PriceSnapshot;
import org.earnlumens.mediastore.infrastructure.external.pricing.XlmUsdPriceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public endpoint exposing the current XLM/USD price.
 * Used by the frontend to display real-time price conversions.
 */
@RestController
@RequestMapping("/public/price")
public class PriceController {

    private static final Logger logger = LoggerFactory.getLogger(PriceController.class);

    private final XlmUsdPriceService priceService;

    public PriceController(XlmUsdPriceService priceService) {
        this.priceService = priceService;
    }

    /**
     * GET /public/price/xlm-usd — returns the latest cached XLM/USD price with audit metadata.
     */
    @GetMapping("/xlm-usd")
    public ResponseEntity<?> getXlmUsdPrice() {
        try {
            PriceSnapshot snapshot = priceService.getPrice();
            return ResponseEntity.ok(Map.of(
                    "price", snapshot.price(),
                    "timestamp", snapshot.timestamp().toString(),
                    "source", snapshot.sourceUsed(),
                    "mode", snapshot.mode().name()
            ));
        } catch (IllegalStateException e) {
            logger.error("XLM/USD price unavailable: {}", e.getMessage());
            return ResponseEntity.status(503).body(Map.of(
                    "error", "XLM/USD price temporarily unavailable"
            ));
        }
    }
}
