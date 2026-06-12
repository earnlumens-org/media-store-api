package org.earnlumens.mediastore.infrastructure.config;

import com.mongodb.connection.ConnectionPoolSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Explicit MongoDB connection-pool sizing (Phase 1 of SCALABILITY-AUDIT.md).
 * <p>
 * The driver default of 100 connections per instance multiplied by Cloud Run
 * autoscaling can exhaust the Atlas connection limit (e.g. M10 = 1500
 * connections at ~15 instances). Capping the pool per instance keeps total
 * connections proportional to instance count and predictable.
 * <p>
 * Tunable via env vars without a rebuild:
 * {@code MONGO_MAX_POOL_SIZE} (default 40), {@code MONGO_MIN_POOL_SIZE}
 * (default 5), {@code MONGO_MAX_CONN_IDLE_MS} (default 60000).
 */
@Configuration
public class MongoPoolConfig {

    @Bean
    public MongoClientSettingsBuilderCustomizer mongoPoolCustomizer(
            @Value("${MONGO_MAX_POOL_SIZE:40}") int maxPoolSize,
            @Value("${MONGO_MIN_POOL_SIZE:5}") int minPoolSize,
            @Value("${MONGO_MAX_CONN_IDLE_MS:60000}") long maxConnIdleMs) {
        return builder -> builder.applyToConnectionPoolSettings((ConnectionPoolSettings.Builder pool) -> pool
                .maxSize(maxPoolSize)
                .minSize(minPoolSize)
                .maxConnectionIdleTime(maxConnIdleMs, TimeUnit.MILLISECONDS));
    }
}
