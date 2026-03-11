package org.earnlumens.mediastore.infrastructure.external.pricing;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Configures a shared {@link HttpClient} for all XLM/USD price source implementations.
 * Shared client = one connection pool, one TLS context, consistent timeouts.
 */
@Configuration
public class PricingConfig {

    @Bean("pricingHttpClient")
    public HttpClient pricingHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
