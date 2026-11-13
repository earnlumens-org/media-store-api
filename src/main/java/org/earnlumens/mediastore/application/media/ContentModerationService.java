package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.model.Collection;
import org.earnlumens.mediastore.domain.media.model.CollectionItem;
import org.earnlumens.mediastore.domain.media.model.Dispute;
import org.earnlumens.mediastore.domain.media.model.DisputeStatus;
import org.earnlumens.mediastore.domain.media.model.Entitlement;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.ModerationBlock;
import org.earnlumens.mediastore.domain.media.model.ModerationCase;
import org.earnlumens.mediastore.domain.media.model.ModerationCaseKind;
import org.earnlumens.mediastore.domain.media.model.ModerationCaseResolution;
import org.earnlumens.mediastore.domain.media.model.StatusChangeRecord;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.domain.media.repository.DisputeRepository;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.media.repository.ModerationCaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Content takedowns, preventive blocks, reinstatements and the dispute
 * trigger (MYCELIUM-RESILIENCE.md H3 / D-01 / D-09 / D-13 / D-15 / D-16,
 * DATA-MODEL.md §8.8).
 *
 * <p>Every block is an explicit action that creates a {@link ModerationCase}
 * (the {@code moderationCaseId} the administrator resolves in bulk), stamps
 * {@code moderationBlock} on the content and opens <b>one dispute per
 * affected purchase</b> — the union of direct entry entitlements and
 * collection entitlements whose right covers the entry. The entitlement is
 * never revoked, no refund is issued and no dispute leaves {@code OPEN}
 * here: reinstating clears the block and access returns automatically.
 */
@Service
public class ContentModerationService {

    private static final Logger logger = LoggerFactory.getLogger(ContentModerationService.class);

    /** Actor recorded for automatic (system) moderation actions. */
    public static final String SYSTEM_ACTOR = "EarnLumens";

    private final EntryRepository entryRepository;
    private final CollectionRepository collectionRepository;
    private final EntitlementRepository entitlementRepository;
    private final ModerationCaseRepository caseRepository;
    private final DisputeRepository disputeRepository;

    public ContentModerationService(EntryRepository entryRepository,
                                    CollectionRepository collectionRepository,
                                    EntitlementRepository entitlementRepository,
                                    ModerationCaseRepository caseRepository,
                                    DisputeRepository disputeRepository) {
        this.entryRepository = entryRepository;
        this.collectionRepository = collectionRepository;
        this.entitlementRepository = entitlementRepository;
        this.caseRepository = caseRepository;
        this.disputeRepository = disputeRepository;
    }

    // ─── Takedowns ───────────────────────────────────────────────

    /**
     * Moderator's explicit "remove content" on an entry (D-13
     * {@code CONTENT_TAKEDOWN}): status → {@code SUSPENDED} (previous status
     * kept for reinstatement), block set, case created, disputes opened.
     * Idempotent: an already-blocked entry keeps its case and only re-runs
     * the dispute trigger.
     *
     * @throws IllegalArgumentException when the entry does not exist
     */
    public ModerationCase takedownEntry(String tenantId, String entryId, String reason, String actor) {
        Entry entry = requireEntry(tenantId, entryId);
        if (entry.isBlocked()) {
            return reuseCase(tenantId, entry.getModerationBlock().getCaseId());
        }
        requireReason(reason);
        ModerationCase moderationCase = newCase(tenantId, ModerationCaseKind.TAKEDOWN,
                List.of(entryId), List.of(), reason, actor);

        transition(entry, EntryStatus.SUSPENDED, actor, reason);
        entry.setModerationFeedback(reason);
        applyBlock(entry, moderationCase);
        entryRepository.save(entry);
        logger.warn("moderation: TAKEDOWN entry={} tenant={} case={} by={} reason={}",
                entryId, tenantId, moderationCase.getId(), actor, reason);

        openDisputes(moderationCase);
        return moderationCase;
    }

    /**
     * Preventive block of live content pending human review (R-21):
     * HIGH-severity report escalation or an illegal pending edit. Status →
     * {@code IN_REVIEW} so it leaves the feeds and lands in the moderators'
     * queue; buyers get their disputes immediately (D-09).
     */
    public ModerationCase preventiveBlockEntry(String tenantId, Entry entry, String reason, String actor) {
        if (entry.isBlocked()) {
            return reuseCase(tenantId, entry.getModerationBlock().getCaseId());
        }
        requireReason(reason);
        ModerationCase moderationCase = newCase(tenantId, ModerationCaseKind.PREVENTIVE,
                List.of(entry.getId()), List.of(), reason, actor);

        if (entry.getStatus() != EntryStatus.IN_REVIEW) {
            transition(entry, EntryStatus.IN_REVIEW, actor, reason);
        }
        entry.setModerationFeedback(reason);
        applyBlock(entry, moderationCase);
        entryRepository.save(entry);
        logger.warn("moderation: PREVENTIVE block entry={} tenant={} case={} by={} reason={}",
                entry.getId(), tenantId, moderationCase.getId(), actor, reason);

        openDisputes(moderationCase);
        return moderationCase;
    }

    /**
     * Block that accompanies a REJECT of <b>live</b> content (the entry had
     * been published and may have buyers). The caller has already moved the
     * status to {@code REJECTED}; this records the case, stamps the block
     * and opens the disputes. Idempotent like the other entry points.
     */
    public ModerationCase blockRejectedLiveEntry(String tenantId, Entry entry, String reason, String actor) {
        if (entry.isBlocked()) {
            return reuseCase(tenantId, entry.getModerationBlock().getCaseId());
        }
        ModerationCase moderationCase = newCase(tenantId, ModerationCaseKind.TAKEDOWN,
                List.of(entry.getId()), List.of(), reason == null || reason.isBlank() ? "Content rejected" : reason, actor);
        applyBlock(entry, moderationCase);
        entryRepository.save(entry);
        logger.warn("moderation: TAKEDOWN (rejected live content) entry={} tenant={} case={} by={}",
                entry.getId(), tenantId, moderationCase.getId(), actor);
        openDisputes(moderationCase);
        return moderationCase;
    }

    /**
     * Takedown of a collection: the collection page is blocked for everyone
     * and the collection no longer grants access to its entries; one dispute
     * per collection buyer. Entries are not blocked individually — a
     * moderator who wants that takes them down explicitly.
     */
    public ModerationCase takedownCollection(String tenantId, String collectionId, String reason, String actor) {
        Collection collection = collectionRepository.findByTenantIdAndId(tenantId, collectionId)
                .orElseThrow(() -> new IllegalArgumentException("Collection not found"));
        if (collection.isBlocked()) {
            return reuseCase(tenantId, collection.getModerationBlock().getCaseId());
        }
        requireReason(reason);
        ModerationCase moderationCase = newCase(tenantId, ModerationCaseKind.TAKEDOWN,
                List.of(), List.of(collectionId), reason, actor);
        collection.setModerationBlock(new ModerationBlock(
                moderationCase.getId(), reason, actor, moderationCase.getCreatedAt()));
        collection.setModerationFeedback(reason);
        collectionRepository.save(collection);
        logger.warn("moderation: TAKEDOWN collection={} tenant={} case={} by={} reason={}",
                collectionId, tenantId, moderationCase.getId(), actor, reason);
        openDisputes(moderationCase);
        return moderationCase;
    }

    // ─── Reinstatement ───────────────────────────────────────────

    /**
     * Clears the block; the entry returns to the status it had before the
     * moderation action (fallback {@code DRAFT}). Access comes back
     * automatically for every buyer — the entitlement was never touched
     * (§8.8). Disputes stay as they are: resolving them is the
     * administrator's explicit decision (D-09).
     */
    public Entry reinstateEntry(String tenantId, String entryId, String actor) {
        Entry entry = requireEntry(tenantId, entryId);
        if (!entry.isBlocked()) {
            throw new IllegalStateException("Entry is not blocked");
        }
        String caseId = entry.getModerationBlock().getCaseId();
        EntryStatus restoreTo = entry.getPreviousStatus() != null ? entry.getPreviousStatus() : EntryStatus.DRAFT;
        if (entry.getStatus() != restoreTo) {
            transition(entry, restoreTo, actor, "Reinstated by moderation");
        }
        entry.setPreviousStatus(null);
        entry.setModerationBlock(null);
        entry.setModerationFeedback(null);
        entryRepository.save(entry);
        logger.info("moderation: REINSTATED entry={} tenant={} case={} by={} status={}",
                entryId, tenantId, caseId, actor, restoreTo);
        resolveCaseIfFullyReinstated(tenantId, caseId);
        return entry;
    }

    public Collection reinstateCollection(String tenantId, String collectionId, String actor) {
        Collection collection = collectionRepository.findByTenantIdAndId(tenantId, collectionId)
                .orElseThrow(() -> new IllegalArgumentException("Collection not found"));
        if (!collection.isBlocked()) {
            throw new IllegalStateException("Collection is not blocked");
        }
        String caseId = collection.getModerationBlock().getCaseId();
        collection.setModerationBlock(null);
        collection.setModerationFeedback(null);
        collectionRepository.save(collection);
        logger.info("moderation: REINSTATED collection={} tenant={} case={} by={}",
                collectionId, tenantId, caseId, actor);
        resolveCaseIfFullyReinstated(tenantId, caseId);
        return collection;
    }

    // ─── Dispute trigger (D-15) ──────────────────────────────────

    /**
     * Opens one {@code OPEN} dispute per purchase affected by the case —
     * union of (a) direct entry entitlements and (b) collection entitlements
     * whose right covers a blocked entry — plus the buyers of a blocked
     * collection. Idempotent on {@code (case, entitlement)}; re-running it
     * never duplicates nor overwrites an administrator's resolution.
     *
     * @return number of disputes created by this invocation
     */
    public int openDisputes(ModerationCase moderationCase) {
        String tenantId = moderationCase.getTenantId();
        Map<String, Affected> affected = new LinkedHashMap<>();

        for (String entryId : moderationCase.getEntryIds()) {
            // (a) direct purchases of the entry
            for (Entitlement e : entitlementRepository.findActivePurchasesByEntryId(tenantId, entryId)) {
                affected.computeIfAbsent(e.getId(), k -> new Affected(e)).entryIds.add(entryId);
            }
            // (b) collection purchases whose right covers the entry (any collection status)
            List<Collection> parents = collectionRepository.findByTenantIdAndItemsEntryId(tenantId, entryId);
            if (parents.isEmpty()) {
                continue;
            }
            Map<String, Collection> byId = parents.stream()
                    .collect(Collectors.toMap(Collection::getId, c -> c, (a, b) -> a));
            List<Entitlement> collEnts = entitlementRepository.findActivePurchasesByCollectionIds(
                    tenantId, new ArrayList<>(byId.keySet()));
            for (Entitlement e : collEnts) {
                Collection coll = byId.get(e.getCollectionId());
                if (coll != null && rightCoversEntry(coll, entryId, e)) {
                    affected.computeIfAbsent(e.getId(), k -> new Affected(e)).entryIds.add(entryId);
                }
            }
        }

        // Collection-level block: every buyer of the collection is affected,
        // on the items their right covers (D-20)
        for (String collectionId : moderationCase.getCollectionIds()) {
            Optional<Collection> coll = collectionRepository.findByTenantIdAndId(tenantId, collectionId);
            for (Entitlement e : entitlementRepository.findActivePurchasesByCollectionIds(tenantId, List.of(collectionId))) {
                List<String> itemIds = coll.map(c -> c.itemsCoveredBy(e.getGrantedAt()).stream()
                                .map(CollectionItem::getEntryId).toList())
                        .orElse(List.of());
                affected.computeIfAbsent(e.getId(), k -> new Affected(e)).entryIds.addAll(itemIds);
            }
        }

        int created = 0;
        LocalDateTime now = LocalDateTime.now();
        for (Affected a : affected.values()) {
            Dispute d = new Dispute();
            d.setTenantId(tenantId);
            d.setModerationCaseId(moderationCase.getId());
            d.setEntitlementId(a.entitlement.getId());
            d.setOrderId(a.entitlement.getOrderId());
            d.setUserId(a.entitlement.getUserId());
            d.setEntryId(a.entryIds.isEmpty() ? null : a.entryIds.first());
            d.setAffectedEntryIds(new ArrayList<>(a.entryIds));
            d.setCollectionId(a.entitlement.getCollectionId());
            d.setReason(moderationCase.getReason());
            d.setStatus(DisputeStatus.OPEN);
            d.setCreatedAt(now);
            if (disputeRepository.openIfAbsent(d)) {
                created++;
            }
        }
        if (created > 0 || !affected.isEmpty()) {
            logger.info("moderation: case={} tenant={} affected purchases={} new disputes={}",
                    moderationCase.getId(), tenantId, affected.size(), created);
        }
        return created;
    }

    /**
     * Re-runs the dispute trigger for every unresolved case (R-20): a crash
     * between the block and the disputes never leaves a buyer without
     * follow-up. Cross-tenant; caller runs it without tenant context.
     *
     * @return disputes created by this sweep
     */
    public int sweepUnresolvedCases(int limit) {
        int created = 0;
        for (ModerationCase c : caseRepository.findAllUnresolved(limit)) {
            try {
                created += openDisputes(c);
            } catch (Exception e) {
                logger.error("moderation sweep: case={} failed: {}", c.getId(), e.getMessage(), e);
            }
        }
        return created;
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private static final class Affected {
        final Entitlement entitlement;
        final TreeSet<String> entryIds = new TreeSet<>();
        Affected(Entitlement entitlement) { this.entitlement = entitlement; }
    }

    /**
     * Union-by-right-date rule (D-20 / S-04): the buyer's right covers the
     * entry when the collection holds a membership record of it that is
     * active or was removed at or after {@code grantedAt} (R-32: {@code >=}
     * favours the buyer; a missing {@code grantedAt} means "since forever").
     */
    static boolean rightCoversEntry(Collection collection, String entryId, Entitlement entitlement) {
        return collection.getItems().stream()
                .filter(i -> entryId.equals(i.getEntryId()))
                .anyMatch(i -> i.coversRightSince(entitlement.getGrantedAt()));
    }

    private ModerationCase newCase(String tenantId, ModerationCaseKind kind, List<String> entryIds,
                                   List<String> collectionIds, String reason, String actor) {
        ModerationCase c = new ModerationCase();
        c.setTenantId(tenantId);
        c.setKind(kind);
        c.setEntryIds(new ArrayList<>(entryIds));
        c.setCollectionIds(new ArrayList<>(collectionIds));
        c.setReason(reason);
        c.setActor(actor == null || actor.isBlank() ? SYSTEM_ACTOR : actor);
        c.setCreatedAt(LocalDateTime.now());
        return caseRepository.save(c);
    }

    private ModerationCase reuseCase(String tenantId, String caseId) {
        ModerationCase existing = caseRepository.findByTenantIdAndId(tenantId, caseId)
                .orElseThrow(() -> new IllegalStateException(
                        "Content is blocked but its moderation case " + caseId + " does not exist"));
        openDisputes(existing);
        return existing;
    }

    private void applyBlock(Entry entry, ModerationCase moderationCase) {
        entry.setModerationBlock(new ModerationBlock(
                moderationCase.getId(), moderationCase.getReason(),
                moderationCase.getActor(), moderationCase.getCreatedAt()));
        entry.setUpdatedAt(LocalDateTime.now());
    }

    private void transition(Entry entry, EntryStatus to, String actor, String reason) {
        EntryStatus from = entry.getStatus();
        if (from != EntryStatus.SUSPENDED && from != EntryStatus.IN_REVIEW && from != EntryStatus.REJECTED) {
            entry.setPreviousStatus(from);
        }
        entry.getStatusHistory().add(new StatusChangeRecord(from, to,
                actor == null || actor.isBlank() ? SYSTEM_ACTOR : actor, reason));
        entry.setStatus(to);
    }

    private void resolveCaseIfFullyReinstated(String tenantId, String caseId) {
        Optional<ModerationCase> opt = caseRepository.findByTenantIdAndId(tenantId, caseId);
        if (opt.isEmpty()) {
            return;
        }
        ModerationCase c = opt.get();
        boolean anyEntryBlocked = c.getEntryIds().stream()
                .map(id -> entryRepository.findByTenantIdAndId(tenantId, id))
                .anyMatch(e -> e.isPresent() && e.get().isBlocked()
                        && caseId.equals(e.get().getModerationBlock().getCaseId()));
        boolean anyCollectionBlocked = c.getCollectionIds().stream()
                .map(id -> collectionRepository.findByTenantIdAndId(tenantId, id))
                .anyMatch(col -> col.isPresent() && col.get().isBlocked()
                        && caseId.equals(col.get().getModerationBlock().getCaseId()));
        if (!anyEntryBlocked && !anyCollectionBlocked && c.getResolvedAt() == null) {
            c.setResolvedAt(LocalDateTime.now());
            c.setResolution(ModerationCaseResolution.REINSTATED);
            caseRepository.save(c);
        }
    }

    private Entry requireEntry(String tenantId, String entryId) {
        return entryRepository.findByTenantIdAndId(tenantId, entryId)
                .orElseThrow(() -> new IllegalArgumentException("Entry not found"));
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Justification is required");
        }
    }
}
