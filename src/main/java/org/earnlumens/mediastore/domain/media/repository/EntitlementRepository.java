package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.Entitlement;
import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;
import org.earnlumens.mediastore.domain.media.model.TargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface EntitlementRepository {

    boolean existsByTenantIdAndUserIdAndEntryIdAndStatus(
            String tenantId, String userId, String entryId, EntitlementStatus status);

    /** Full entitlement for an entry (used to verify the backing order before unlocking). */
    Optional<Entitlement> findByTenantIdAndUserIdAndEntryIdAndStatus(
            String tenantId, String userId, String entryId, EntitlementStatus status);

    /** Full collection entitlements (used to verify the backing orders before unlocking). */
    List<Entitlement> findByTenantIdAndUserIdAndCollectionIdsAndStatus(
            String tenantId, String userId, List<String> collectionIds, EntitlementStatus status);

    /** Check if user has an active collection entitlement */
    boolean existsByTenantIdAndUserIdAndTargetTypeAndCollectionIdAndStatus(
            String tenantId, String userId, TargetType targetType, String collectionId, EntitlementStatus status);

    /**
     * Return the subset of {@code entryIds} for which the user has an active entitlement.
     * Used for batch "is unlocked?" checks (e.g. favorites list).
     */
    Set<String> findEntitledEntryIds(
            String tenantId, String userId, List<String> entryIds, EntitlementStatus status);

    /** Return the subset of collectionIds for which the user has an active entitlement */
    Set<String> findEntitledCollectionIds(
            String tenantId, String userId, List<String> collectionIds, EntitlementStatus status);

    Page<Entitlement> findByTenantIdAndUserIdAndStatus(
            String tenantId, String userId, EntitlementStatus status, Pageable pageable);

    /** Paginated entitlements filtered by targetType (ENTRY or COLLECTION) */
    Page<Entitlement> findByTenantIdAndUserIdAndTargetTypeAndStatus(
            String tenantId, String userId, TargetType targetType, EntitlementStatus status, Pageable pageable);

    /** All entry IDs the user is entitled to (unpaginated, for purchased feed). */
    Set<String> findAllEntitledEntryIds(String tenantId, String userId, EntitlementStatus status);

    /** All collection IDs the user is entitled to (unpaginated, for purchased feed). */
    Set<String> findAllEntitledCollectionIds(String tenantId, String userId, EntitlementStatus status);

    /** All ACTIVE collection entitlements of the user, with their {@code grantedAt} (union rule, D-20). */
    List<Entitlement> findAllCollectionEntitlements(String tenantId, String userId, EntitlementStatus status);

    /** All ACTIVE direct entry entitlements of the user, with their {@code grantedAt} (purchased version, N8-lite). */
    List<Entitlement> findAllEntryEntitlements(String tenantId, String userId, EntitlementStatus status);

    /**
     * True when any entitlement — in any status — references the collection.
     * Guards the hard delete (H2: a collection with buyers cannot be deleted).
     */
    boolean existsByTenantIdAndCollectionId(String tenantId, String collectionId);

    /** Outcome of {@link #upsertPurchase}. */
    enum UpsertOutcome { CREATED, REACTIVATED, UNCHANGED }

    /**
     * One entitlement per right (S-06 / H11): keyed by
     * {@code (tenantId, userId, targetType, entryId | collectionId)}.
     * <ul>
     *   <li>absent ⇒ inserted as given ({@code CREATED});</li>
     *   <li>{@code REVOKED} ⇒ reactivated with the new {@code orderId},
     *       {@code grantedAt} and {@code franchiseId} ({@code REACTIVATED}) —
     *       the right is evaluated from the new purchase;</li>
     *   <li>already {@code ACTIVE} ⇒ untouched, {@code grantedAt} preserved
     *       (R-30: a repeated watchdog call never moves the right date).</li>
     * </ul>
     * Idempotent and safe under the H9 unique indexes.
     */
    UpsertOutcome upsertPurchase(Entitlement entitlement);

    /**
     * Subset of {@code orderIds} that already have a backing entitlement.
     * Cross-tenant on purpose — used by the payment reconciliation watchdog to
     * find COMPLETED orders whose entitlement was never created.
     */
    Set<String> findOrderIdsWithEntitlements(java.util.Collection<String> orderIds);

    /**
     * Every ACTIVE PURCHASE entitlement of {@code targetType = ENTRY} on the
     * given entry — path (a) of the dispute trigger (DATA-MODEL.md §8.8).
     */
    List<Entitlement> findActivePurchasesByEntryId(String tenantId, String entryId);

    /**
     * Every ACTIVE PURCHASE entitlement of {@code targetType = COLLECTION} on
     * any of the given collections — path (b) of the dispute trigger.
     */
    List<Entitlement> findActivePurchasesByCollectionIds(String tenantId, List<String> collectionIds);

    Entitlement save(Entitlement entitlement);
}
