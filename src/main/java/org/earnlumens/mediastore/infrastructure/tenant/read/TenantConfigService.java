package org.earnlumens.mediastore.infrastructure.tenant.read;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches {@link TenantReadModel} lookups by subdomain to avoid a Mongo round-trip
 * on every request. The fee schedule changes rarely; a short in-memory TTL is a
 * good tradeoff between freshness and throughput.
 * <p>
 * <b>Security note.</b> Only ACTIVE tenants are returned to callers. Blocked
 * or deleted tenants cause a fall-through to {@link Optional#empty()} so that
 * downstream code applies the safest default (e.g. global platform fee, no
 * tenant split).
 */
@Service
public class TenantConfigService {

    private static final Logger logger = LoggerFactory.getLogger(TenantConfigService.class);
    private static final Duration TTL = Duration.ofMinutes(5);

    private final TenantReadRepository repository;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public TenantConfigService(TenantReadRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns the ACTIVE tenant configuration matching the given subdomain, or
     * empty if no document exists or the tenant is blocked. The {@code subdomain}
     * value is also what media-store-api uses as its canonical {@code tenantId}.
     */
    public Optional<TenantReadModel> findActiveBySubdomain(String subdomain) {
        if (subdomain == null || subdomain.isBlank()) return Optional.empty();

        CacheEntry entry = cache.get(subdomain);
        Instant now = Instant.now();
        if (entry != null && entry.expiresAt.isAfter(now)) {
            return entry.value;
        }

        Optional<TenantReadModel> fresh = repository.findBySubdomain(subdomain)
                .filter(TenantReadModel::isActive);
        cache.put(subdomain, new CacheEntry(fresh, now.plus(TTL)));
        return fresh;
    }

    /** Drops the cached entry for a subdomain (exposed for tests and admin ops). */
    public void invalidate(String subdomain) {
        if (subdomain != null) {
            cache.remove(subdomain);
            logger.debug("TenantConfigService: cache invalidated for subdomain={}", subdomain);
        }
    }

    private record CacheEntry(Optional<TenantReadModel> value, Instant expiresAt) {}
}
