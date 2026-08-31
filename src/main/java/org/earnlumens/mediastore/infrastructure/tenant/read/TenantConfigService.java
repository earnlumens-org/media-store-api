package org.earnlumens.mediastore.infrastructure.tenant.read;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches {@link TenantReadModel} lookups by subdomain to avoid a Mongo round-trip
 * on every request. The fee schedule changes rarely; a short in-memory TTL is a
 * good tradeoff between freshness and throughput.
 * <p>
 * The TTL is 60 s (Phase 3, task 3.2 of SCALABILITY-AUDIT.md — P1-4): this cache
 * is per-instance, so with N instances a fee change propagates instance by
 * instance — the short TTL bounds that divergence window to ≤60 s without
 * needing a shared cache or pub/sub invalidation.
 * <p>
 * <b>Security note.</b> Only ACTIVE tenants are returned to callers. Blocked
 * or deleted tenants cause a fall-through to {@link Optional#empty()} so that
 * downstream code applies the safest default (e.g. global platform fee, no
 * tenant split).
 */
@Service
public class TenantConfigService {

    private static final Logger logger = LoggerFactory.getLogger(TenantConfigService.class);
    private static final Duration TTL = Duration.ofSeconds(60);

    private final TenantReadRepository repository;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    /** Separate keyspace: custom-domain host → tenant (same TTL as the subdomain cache). */
    private final ConcurrentHashMap<String, CacheEntry> customDomainCache = new ConcurrentHashMap<>();

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
            // Custom-domain entries resolve to the same tenant document; drop
            // any host cached for this subdomain so admin edits (domain
            // suspended, plan applied) propagate as fast as subdomain lookups.
            customDomainCache.entrySet().removeIf(e -> e.getValue().value()
                    .map(t -> subdomain.equals(t.getSubdomain())).orElse(false));
            logger.debug("TenantConfigService: cache invalidated for subdomain={}", subdomain);
        }
    }

    /**
     * Resolves a tenant by custom domain (custom-domain-upgrade 2A.2).
     * Only returns a hit when the domain may serve right now:
     * {@code customDomainStatus == ACTIVE} && tenant ACTIVE && {@code isPro(now)}.
     * Pending/suspended/expired domains and unknown hosts resolve to empty —
     * callers must treat that as tenant-not-found, never as a fallback to a
     * default tenant. Cached 60 s per host, including negative results.
     */
    public Optional<TenantReadModel> findActiveByCustomDomain(String host) {
        if (host == null || host.isBlank()) return Optional.empty();
        String key = host.trim().toLowerCase();

        CacheEntry entry = customDomainCache.get(key);
        Instant now = Instant.now();
        if (entry != null && entry.expiresAt().isAfter(now)) {
            // Re-evaluate the time gate on every read so a plan that expires
            // mid-TTL stops serving without waiting for the cache to roll.
            return entry.value().filter(t -> t.isCustomDomainServable(now));
        }

        Optional<TenantReadModel> fresh = repository.findByCustomDomain(key)
                .filter(t -> t.isCustomDomainServable(now));
        customDomainCache.put(key, new CacheEntry(fresh, now.plus(TTL)));
        return fresh;
    }

    /**
     * Returns the canonical {@code tenantId} (i.e. subdomain) of every
     * ACTIVE tenant on the platform. Intended for cross-tenant
     * maintenance jobs that must iterate over every tenant exactly once
     * (e.g. badge expiration). Always hits Mongo — there is no caching
     * here because the result drives mutation, not per-request lookups.
     */
    public List<String> findAllActiveTenantIds() {
        return repository.findByStatus("ACTIVE").stream()
                .map(TenantReadModel::getSubdomain)
                .filter(s -> s != null && !s.isBlank())
                .toList();
    }

    private record CacheEntry(Optional<TenantReadModel> value, Instant expiresAt) {}
}
