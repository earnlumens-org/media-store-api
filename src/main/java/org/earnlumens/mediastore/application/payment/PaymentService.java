package org.earnlumens.mediastore.application.payment;

import org.earnlumens.mediastore.domain.media.dto.request.PreparePaymentRequest;
import org.earnlumens.mediastore.domain.media.dto.request.SubmitPaymentRequest;
import org.earnlumens.mediastore.domain.media.dto.response.PreparePaymentResponse;
import org.earnlumens.mediastore.domain.media.dto.response.SubmitPaymentResponse;
import org.earnlumens.mediastore.domain.media.model.*;
import org.earnlumens.mediastore.domain.media.repository.CollectionRepository;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.media.repository.OrderRepository;
import org.earnlumens.mediastore.infrastructure.config.StellarConfig;
import org.earnlumens.mediastore.infrastructure.external.pricing.XlmUsdPriceService;
import org.earnlumens.mediastore.infrastructure.franchise.read.FranchiseReadModel;
import org.earnlumens.mediastore.infrastructure.franchise.read.FranchiseReadRepository;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantConfigService;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantReadModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import org.earnlumens.mediastore.infrastructure.config.PlatformConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates the two-phase payment flow:
 *   1. prepare() — validates entry, builds Stellar tx, persists PENDING order
 *   2. submit()  — verifies XDR integrity, submits, confirms on-chain, creates entitlement
 *
 * Security invariants:
 *   - Prices and splits are ALWAYS read from the database, never from the client.
 *   - The signed XDR is cryptographically matched (tx hash) against the prepared
 *     transaction, and amount/asset/destination/memo are contrasted field-by-field
 *     against the persisted order BEFORE submission.
 *   - Only the backend submits transactions to the Stellar network, and an order
 *     is only COMPLETED after Horizon confirms the exact tx hash on-chain.
 *   - State transitions are atomic compare-and-swap operations (race-condition safe).
 *   - A Stellar tx hash can unlock content at most once (anti-replay).
 *   - Owner cannot purchase their own content.
 *   - Duplicate purchases are rejected (unique index on userId + entryId).
 */
@Service
public class PaymentService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100.00");

    /** On-chain confirmation polling for ambiguous Horizon submissions. */
    private static final int ONCHAIN_VERIFY_MAX_ATTEMPTS = 5;
    private static final long ONCHAIN_VERIFY_DELAY_MS = 2_000L;

    private final EntryRepository entryRepository;
    private final CollectionRepository collectionRepository;
    private final OrderRepository orderRepository;
    private final EntitlementRepository entitlementRepository;
    private final StellarTransactionService stellarTxService;
    private final StellarConfig stellarConfig;
    private final PlatformConfig platformConfig;
    private final XlmUsdPriceService xlmUsdPriceService;
    private final TenantConfigService tenantConfigService;
    private final FranchiseReadRepository franchiseReadRepository;

    public PaymentService(EntryRepository entryRepository,
                          CollectionRepository collectionRepository,
                          OrderRepository orderRepository,
                          EntitlementRepository entitlementRepository,
                          StellarTransactionService stellarTxService,
                          StellarConfig stellarConfig,
                          PlatformConfig platformConfig,
                          XlmUsdPriceService xlmUsdPriceService,
                          TenantConfigService tenantConfigService,
                          FranchiseReadRepository franchiseReadRepository) {
        this.entryRepository = entryRepository;
        this.collectionRepository = collectionRepository;
        this.orderRepository = orderRepository;
        this.entitlementRepository = entitlementRepository;
        this.stellarTxService = stellarTxService;
        this.stellarConfig = stellarConfig;
        this.platformConfig = platformConfig;
        this.xlmUsdPriceService = xlmUsdPriceService;
        this.tenantConfigService = tenantConfigService;
        this.franchiseReadRepository = franchiseReadRepository;
    }

    /**
     * Phase 1: Prepare a payment transaction.
     * Builds an unsigned Stellar XDR and stores a PENDING order.
     * Supports both entry and collection purchases.
     */
    public PreparePaymentResponse prepare(String tenantId, String userId, PreparePaymentRequest request) {
        String entryId = request.entryId();
        String collectionId = request.collectionId();
        String buyerWallet = request.buyerWallet();

        boolean isCollectionPurchase = collectionId != null && !collectionId.isBlank();
        boolean isEntryPurchase = entryId != null && !entryId.isBlank();

        if (!isCollectionPurchase && !isEntryPurchase) {
            throw new IllegalArgumentException("Either entryId or collectionId is required");
        }
        if (isCollectionPurchase && isEntryPurchase) {
            throw new IllegalArgumentException("Cannot specify both entryId and collectionId");
        }

        // Resolve target properties
        String sellerId;
        BigDecimal totalXlm;
        BigDecimal originalAmountUsd = null;
        BigDecimal xlmUsdRate = null;
        String priceCurrency;
        List<PaymentSplit> targetSplits;

        if (isCollectionPurchase) {
            // ── Collection purchase ──
            Collection collection = collectionRepository.findByTenantIdAndId(tenantId, collectionId)
                    .orElseThrow(() -> new IllegalArgumentException("Collection not found"));

            if (!collection.isPaid()) {
                throw new IllegalArgumentException("Collection is not paid content");
            }
            if (collection.getStatus() != CollectionStatus.PUBLISHED) {
                throw new IllegalArgumentException("Collection is not published");
            }
            if (userId.equals(collection.getUserId())) {
                throw new IllegalArgumentException("Cannot purchase your own content");
            }

            sellerId = collection.getUserId();
            priceCurrency = collection.getPriceCurrency() != null
                    ? collection.getPriceCurrency().name() : "XLM";
            targetSplits = collection.getPaymentSplits();

            if (targetSplits == null || targetSplits.isEmpty()) {
                throw new IllegalArgumentException("Collection has no payment splits configured");
            }

            if (collection.getPriceCurrency() == PriceCurrency.USD) {
                BigDecimal usdAmount = collection.getPriceUsd();
                if (usdAmount == null || usdAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("Collection has no valid USD price");
                }
                var snapshot = xlmUsdPriceService.getPrice();
                BigDecimal rate = snapshot != null ? snapshot.price() : null;
                if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalStateException("XLM/USD price unavailable — cannot process USD payments");
                }
                totalXlm = usdAmount.divide(rate, 7, RoundingMode.CEILING);
                originalAmountUsd = usdAmount;
                xlmUsdRate = rate;
            } else {
                totalXlm = collection.getPriceXlm();
                if (totalXlm == null || totalXlm.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("Collection has no valid price");
                }
            }

            // Check for existing orders
            List<Order> existingOrders = orderRepository.findAllByTenantIdAndUserIdAndCollectionId(
                    tenantId, userId, collectionId);
            Order reusableOrder = processExistingOrders(existingOrders);
            if (reusableOrder != null) {
                return toResponse(reusableOrder);
            }
        } else {
            // ── Entry purchase (existing logic) ──
            Entry entry = entryRepository.findByTenantIdAndId(tenantId, entryId)
                    .orElseThrow(() -> new IllegalArgumentException("Entry not found"));

            if (!entry.isPaid()) {
                throw new IllegalArgumentException("Entry is not paid content");
            }
            if (entry.getStatus() != EntryStatus.PUBLISHED) {
                throw new IllegalArgumentException("Entry is not published");
            }
            if (userId.equals(entry.getUserId())) {
                throw new IllegalArgumentException("Cannot purchase your own content");
            }

            sellerId = entry.getUserId();
            priceCurrency = entry.getPriceCurrency() != null
                    ? entry.getPriceCurrency().name() : "XLM";
            targetSplits = entry.getPaymentSplits();

            if (targetSplits == null || targetSplits.isEmpty()) {
                throw new IllegalArgumentException("Entry has no payment splits configured");
            }

            if (entry.getPriceCurrency() == PriceCurrency.USD) {
                BigDecimal usdAmount = entry.getPriceUsd();
                if (usdAmount == null || usdAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("Entry has no valid USD price");
                }
                var snapshot = xlmUsdPriceService.getPrice();
                BigDecimal rate = snapshot != null ? snapshot.price() : null;
                if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalStateException("XLM/USD price unavailable — cannot process USD payments");
                }
                totalXlm = usdAmount.divide(rate, 7, RoundingMode.CEILING);
                originalAmountUsd = usdAmount;
                xlmUsdRate = rate;
                logger.info("USD→XLM conversion: ${} / {} = {} XLM",
                        usdAmount.toPlainString(), rate.toPlainString(), totalXlm.toPlainString());
            } else {
                totalXlm = entry.getPriceXlm();
                if (totalXlm == null || totalXlm.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("Entry has no valid price");
                }
            }

            // Check for existing orders
            List<Order> existingOrders = orderRepository.findAllByTenantIdAndUserIdAndEntryId(
                    tenantId, userId, entryId);
            Order reusableOrder = processExistingOrders(existingOrders);
            if (reusableOrder != null) {
                return toResponse(reusableOrder);
            }
        }

        // Resolve the franchise storefront, if this purchase came through one
        // (/f/<slug>). An unknown or disabled franchise blocks the sale so a
        // taken-down beta cannot keep selling.
        FranchiseReadModel franchise = null;
        String franchiseSlug = request.franchiseSlug();
        if (franchiseSlug != null && !franchiseSlug.isBlank()) {
            franchise = franchiseReadRepository
                    .findByTenantIdAndSlug(tenantId, franchiseSlug.trim().toLowerCase())
                    .filter(FranchiseReadModel::isActive)
                    .orElseThrow(() -> new IllegalArgumentException("Franchise not available"));
        }

        // Build the full payment splits (platform + optional tenant + seller/collaborator + franchise)
        List<PaymentSplit> splits = buildFullSplits(tenantId, targetSplits, franchise);

        // Defence in depth: every split destination was validated as an active
        // Stellar account when it was registered, but accounts can be merged or
        // removed afterwards. Re-validate (cached + fail-open) BEFORE the buyer
        // signs, so a dead destination yields an actionable 400 here instead of
        // an op_no_destination failure after the user already signed.
        for (PaymentSplit split : splits) {
            if (!stellarTxService.isAccountActiveCached(split.getWallet())) {
                logger.error("Split wallet no longer active on Stellar: role={}, wallet={}, tenant={}, {}={}",
                        split.getRole(), split.getWallet(), tenantId,
                        isCollectionPurchase ? "collectionId" : "entryId",
                        isCollectionPurchase ? collectionId : entryId);
                throw new IllegalArgumentException("SPLIT_WALLET_NOT_ACTIVE");
            }
        }

        // Build the MEMO
        String memo = "TOTAL: " + totalXlm.toPlainString() + " XLM";

        // Build the unsigned Stellar transaction
        StellarTransactionService.BuildResult buildResult =
                stellarTxService.buildTransaction(buyerWallet, totalXlm, splits, memo);

        // Persist PENDING order
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime expiresAt = now.plusSeconds(stellarConfig.getTxTimeoutSeconds());

        Order order = new Order();
        order.setTenantId(tenantId);
        order.setUserId(userId);
        order.setSellerId(sellerId);
        order.setFranchiseId(franchise != null ? franchise.getId() : null);
        order.setAmountXlm(totalXlm);
        order.setOriginalAmountUsd(originalAmountUsd);
        order.setXlmUsdRate(xlmUsdRate);
        order.setPriceCurrency(priceCurrency);
        order.setBuyerWallet(buyerWallet);
        order.setMemo(memo);
        order.setUnsignedXdr(buildResult.unsignedXdr());
        order.setIntegrityHash(buildResult.integrityHash());
        order.setStellarTxHash(buildResult.txHash());
        order.setStatus(OrderStatus.PENDING);
        order.setExpiresAt(expiresAt);
        order.setPaymentSplits(splits);

        if (isCollectionPurchase) {
            order.setTargetType(TargetType.COLLECTION);
            order.setCollectionId(collectionId);
        } else {
            order.setTargetType(TargetType.ENTRY);
            order.setEntryId(entryId);
        }

        Order saved = orderRepository.save(order);

        logger.info("Payment prepared: orderId={}, {}={}, buyer={}, total={} XLM",
                saved.getId(),
                isCollectionPurchase ? "collectionId" : "entryId",
                isCollectionPurchase ? collectionId : entryId,
                buyerWallet, totalXlm.toPlainString());

        return toResponse(saved);
    }

    /**
     * Process existing orders: reject if COMPLETED, return reusable PENDING, expire stale ones.
     */
    private Order processExistingOrders(List<Order> existingOrders) {
        Order reusableOrder = null;
        for (Order existing : existingOrders) {
            if (existing.getStatus() == OrderStatus.COMPLETED) {
                throw new IllegalStateException("Content already purchased");
            }
            if (existing.getStatus() == OrderStatus.PENDING
                    && existing.getExpiresAt() != null
                    && existing.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
                reusableOrder = existing;
                continue;
            }
            if (existing.getStatus() == OrderStatus.PENDING || existing.getStatus() == OrderStatus.FAILED) {
                existing.setStatus(OrderStatus.EXPIRED);
                orderRepository.save(existing);
            }
        }
        return reusableOrder;
    }

    /**
     * Phase 2: Submit a signed transaction.
     *
     * Hardened pipeline:
     * <ol>
     *   <li>Atomic CAS lock PENDING → PROCESSING (ownership + tenant + expiry
     *       enforced inside the same atomic query — no race window).</li>
     *   <li>Strict verification of the client-supplied signed XDR against the
     *       persisted order: tx hash equality plus field-by-field contrast of
     *       source account, memo, native asset, destinations and amounts.</li>
     *   <li>Anti-replay: rejects any tx hash already consumed by another COMPLETED order.</li>
     *   <li>Submission to Horizon with classified outcome; ambiguous outcomes
     *       (timeouts) are resolved by querying the official Horizon
     *       GET /transactions/{hash} endpoint — never assumed.</li>
     *   <li>Order is COMPLETED only after Horizon confirms the exact tx hash
     *       on-chain as successful, via an atomic CAS PROCESSING → COMPLETED.</li>
     *   <li>Entitlement is created only from a confirmed COMPLETED order.</li>
     * </ol>
     */
    public SubmitPaymentResponse submit(String tenantId, String userId, SubmitPaymentRequest request) {
        String orderId = request.orderId();
        String signedXdr = request.signedXdr();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // 1. Atomically lock the order: PENDING → PROCESSING.
        // The CAS query enforces tenant, ownership, PENDING status and non-expired
        // window in a single atomic operation, so two concurrent submits of the
        // same order can never both proceed (double-spend / double-entitlement guard).
        Order order = orderRepository.tryLockForProcessing(tenantId, orderId, userId, signedXdr, now)
                .orElseThrow(() -> explainLockFailure(tenantId, orderId, userId, now));

        // 2. Verify the signed XDR is EXACTLY the transaction this backend prepared.
        // Hash equality covers every field; explicit checks of amount, native asset,
        // destinations and memo against the DB snapshot add defense in depth.
        final org.stellar.sdk.Transaction transaction;
        try {
            transaction = stellarTxService.verifySignedXdrAgainstOrder(signedXdr, order);
        } catch (SecurityException e) {
            failOrder(tenantId, orderId);
            logger.error("Rejected tampered/mismatched signed XDR: orderId={}, userId={}: {}",
                    orderId, userId, e.getMessage());
            throw new IllegalArgumentException("Signed transaction does not match the prepared order");
        }

        String expectedTxHash = order.getStellarTxHash();

        // 3. Anti-replay: a Stellar tx hash may unlock content at most once.
        if (orderRepository.existsCompletedByStellarTxHashExcludingOrder(expectedTxHash, orderId)) {
            failOrder(tenantId, orderId);
            logger.error("Replay attempt blocked: txHash={} already consumed by another order (orderId={})",
                    expectedTxHash, orderId);
            throw new IllegalStateException("Transaction hash already used by a completed order");
        }

        // 4. Submit to the Stellar network and resolve the real outcome.
        StellarTransactionService.SubmissionOutcome outcome = stellarTxService.submitTransaction(transaction);
        if (outcome == StellarTransactionService.SubmissionOutcome.REJECTED) {
            failOrder(tenantId, orderId);
            throw new RuntimeException("Payment failed: transaction rejected by the Stellar network");
        }

        // 5. Server-side confirmation against Horizon — the ONLY source of truth.
        // Even when Horizon's submit response claimed success, we re-verify the
        // exact tx hash on-chain (existence, successful=true, ledger inclusion,
        // source account) before releasing anything.
        boolean confirmed = confirmOnChainWithRetry(expectedTxHash, order,
                outcome == StellarTransactionService.SubmissionOutcome.UNKNOWN);
        if (!confirmed) {
            failOrder(tenantId, orderId);
            logger.error("Payment not confirmed on-chain: orderId={}, txHash={}", orderId, expectedTxHash);
            throw new RuntimeException("Payment failed: transaction could not be confirmed on the Stellar network");
        }

        // 6. Atomic CAS PROCESSING → COMPLETED. If another thread already moved
        // the state, do NOT create a second entitlement.
        Order completed = orderRepository.tryComplete(tenantId, orderId, expectedTxHash,
                        LocalDateTime.now(ZoneOffset.UTC))
                .orElseThrow(() -> new IllegalStateException(
                        "Order state changed concurrently during completion (orderId=" + orderId + ")"));

        // 7. Expire all other PENDING orders for this buyer (single atomic bulk update).
        // A successful submission changes the buyer's on-chain sequence number,
        // which invalidates the XDR in any previously prepared orders (tx_bad_seq).
        long expired = orderRepository.expirePendingOrdersForUserExcept(tenantId, userId, orderId);
        if (expired > 0) {
            logger.info("Expired {} stale PENDING orders for buyer userId={} after successful payment",
                    expired, userId);
        }

        // 8. Create entitlement from the confirmed COMPLETED order (idempotent).
        createEntitlement(completed);

        logger.info("Payment completed: orderId={}, txHash={}, entryId={}, collectionId={}",
                orderId, expectedTxHash, completed.getEntryId(), completed.getCollectionId());

        return new SubmitPaymentResponse(
                orderId,
                expectedTxHash,
                OrderStatus.COMPLETED.name(),
                completed.getEntryId(),
                completed.getCollectionId()
        );
    }

    /**
     * Confirms the tx on-chain via Horizon. When the submission outcome was
     * ambiguous (timeout), polls a few times to let the ledger close before
     * giving a final verdict. Never returns true without positive confirmation.
     */
    private boolean confirmOnChainWithRetry(String txHash, Order order, boolean ambiguousOutcome) {
        int attempts = ambiguousOutcome ? ONCHAIN_VERIFY_MAX_ATTEMPTS : 2;
        for (int i = 0; i < attempts; i++) {
            if (i > 0) {
                try {
                    Thread.sleep(ONCHAIN_VERIFY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (stellarTxService.verifyTransactionOnChain(txHash, order)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Diagnoses why the atomic PROCESSING lock could not be acquired and maps it
     * to the appropriate error. Also expires the order if its window elapsed.
     */
    private RuntimeException explainLockFailure(String tenantId, String orderId, String userId, LocalDateTime now) {
        Optional<Order> existing = orderRepository.findByTenantIdAndId(tenantId, orderId);
        if (existing.isEmpty()) {
            return new IllegalArgumentException("Order not found");
        }
        Order order = existing.get();
        if (!order.getUserId().equals(userId)) {
            return new IllegalArgumentException("Order does not belong to this user");
        }
        if (order.getStatus() == OrderStatus.PENDING
                && order.getExpiresAt() != null && !order.getExpiresAt().isAfter(now)) {
            orderRepository.tryTransitionStatus(tenantId, orderId, OrderStatus.PENDING, OrderStatus.EXPIRED);
            return new IllegalStateException("Order has expired");
        }
        return new IllegalStateException("Order is not in PENDING state (current: " + order.getStatus() + ")");
    }

    /** Atomically marks a PROCESSING order as FAILED (CAS — never downgrades COMPLETED). */
    private void failOrder(String tenantId, String orderId) {
        orderRepository.tryTransitionStatus(tenantId, orderId, OrderStatus.PROCESSING, OrderStatus.FAILED)
                .ifPresentOrElse(
                        o -> logger.info("Order marked FAILED: orderId={}", orderId),
                        () -> logger.warn("Could not mark order FAILED (state already changed): orderId={}", orderId));
    }

    /**
     * Creates an ACTIVE entitlement for the buyer after a confirmed payment.
     * Hard precondition: the backing order MUST be COMPLETED with an on-chain tx hash.
     * Idempotent: a duplicate-key on the unique (userId + entryId/collectionId)
     * index means the entitlement already exists and is treated as success.
     */
    private void createEntitlement(Order order) {
        if (order.getStatus() != OrderStatus.COMPLETED || order.getStellarTxHash() == null) {
            throw new IllegalStateException(
                    "Refusing to create entitlement: order is not a confirmed COMPLETED payment (orderId="
                            + order.getId() + ", status=" + order.getStatus() + ")");
        }

        Entitlement entitlement = new Entitlement();
        entitlement.setTenantId(order.getTenantId());
        entitlement.setUserId(order.getUserId());
        entitlement.setTargetType(order.getTargetType() != null ? order.getTargetType() : TargetType.ENTRY);
        entitlement.setEntryId(order.getEntryId());
        entitlement.setCollectionId(order.getCollectionId());
        entitlement.setFranchiseId(order.getFranchiseId());
        entitlement.setGrantType(GrantType.PURCHASE);
        entitlement.setOrderId(order.getId());
        entitlement.setStatus(EntitlementStatus.ACTIVE);
        entitlement.setGrantedAt(LocalDateTime.now(ZoneOffset.UTC));

        try {
            entitlementRepository.save(entitlement);
        } catch (DuplicateKeyException e) {
            logger.warn("Entitlement already exists (idempotent create): userId={}, orderId={}",
                    order.getUserId(), order.getId());
            return;
        }
        logger.info("Entitlement created: userId={}, entryId={}, collectionId={}, orderId={}",
                order.getUserId(), order.getEntryId(), order.getCollectionId(), order.getId());
    }

    /**
     * Builds the full payment split list combining:
     * <ul>
     *   <li>PLATFORM — EarnLumens fee. Sourced from the tenant's
     *       {@code platformFeePercent} when an ACTIVE tenant doc exists,
     *       otherwise from the global {@link PlatformConfig}.</li>
     *   <li>TENANT — optional fee for the tenant operator (only when
     *       {@code tenantFeePercent > 0} and {@code tenantWallet} is set).</li>
     *   <li>SELLER / COLLABORATOR — the entry's own splits, re-scaled so all
     *       percentages (platform + tenant + entry) sum to 100%.</li>
     * </ul>
     * If the tenant doc is missing or BLOCKED we fall back to a two-way split
     * (PLATFORM + entry) using the global config so purchases keep working.
     *
     * <p>When the sale is made through a {@code franchise} ("beta"), a FRANCHISE
     * split is carved <b>out of the tenant's own share</b> equal to
     * {@code tenantPercent * commissionPercent / 100}. The final price and every
     * other split (platform, seller, collaborators) are unchanged — only the
     * tenant's portion is divided between the tenant and its franchise. A
     * franchise therefore only earns when the tenant has a non-zero fee to
     * share.
     */
    private List<PaymentSplit> buildFullSplits(String tenantId, List<PaymentSplit> entrySplits,
                                               FranchiseReadModel franchise) {
        BigDecimal platformPercent = platformConfig.getFeePercent();
        String platformWallet = platformConfig.getWallet();
        BigDecimal tenantPercent = BigDecimal.ZERO;
        String tenantWallet = null;

        Optional<TenantReadModel> tenantConfig = tenantConfigService.findActiveBySubdomain(tenantId);
        if (tenantConfig.isPresent()) {
            TenantReadModel t = tenantConfig.get();
            if (t.getPlatformFeePercent() != null) {
                platformPercent = t.getPlatformFeePercent();
            }
            if (t.getTenantFeePercent() != null
                    && t.getTenantFeePercent().compareTo(BigDecimal.ZERO) > 0) {
                if (t.getTenantWallet() != null && !t.getTenantWallet().isBlank()) {
                    tenantPercent = t.getTenantFeePercent();
                    tenantWallet = t.getTenantWallet();
                } else {
                    // Fail-open by design (the sale must go through), but never
                    // silently: the tenant is losing its commission on this sale.
                    logger.warn("Tenant {} has tenantFeePercent={} but no wallet configured — "
                                    + "TENANT split omitted, tenant earns nothing on this sale",
                            tenantId, t.getTenantFeePercent());
                }
            }
        }

        BigDecimal reservedPercent = platformPercent.add(tenantPercent);
        if (reservedPercent.compareTo(ONE_HUNDRED) >= 0) {
            throw new IllegalStateException(
                    "Invalid fee configuration for tenant=" + tenantId
                            + ": platform% + tenant% must be < 100 (got " + reservedPercent + ")");
        }
        BigDecimal nonReservedTotal = ONE_HUNDRED.subtract(reservedPercent);

        BigDecimal entryTotal = entrySplits.stream()
                .map(PaymentSplit::getPercent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (entryTotal.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Entry splits total is zero — invalid configuration");
        }

        // Carve the franchise commission out of the tenant's own share. The
        // reserved total (and therefore the seller's portion) is untouched, so
        // the final price stays constant.
        BigDecimal franchisePercent = BigDecimal.ZERO;
        String franchiseWallet = null;
        if (franchise != null
                && tenantWallet != null
                && tenantPercent.compareTo(BigDecimal.ZERO) > 0
                && franchise.getCommissionPercent() != null
                && franchise.getCommissionPercent().compareTo(BigDecimal.ZERO) > 0
                && franchise.getPayoutWallet() != null
                && !franchise.getPayoutWallet().isBlank()) {
            franchisePercent = tenantPercent
                    .multiply(franchise.getCommissionPercent())
                    .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
            if (franchisePercent.compareTo(tenantPercent) > 0) {
                franchisePercent = tenantPercent;
            }
            franchiseWallet = franchise.getPayoutWallet();
        }
        BigDecimal effectiveTenantPercent = tenantPercent.subtract(franchisePercent);

        List<PaymentSplit> fullSplits = new ArrayList<>();
        fullSplits.add(new PaymentSplit(platformWallet, SplitRole.PLATFORM, platformPercent));
        if (tenantWallet != null && effectiveTenantPercent.compareTo(BigDecimal.ZERO) > 0) {
            fullSplits.add(new PaymentSplit(tenantWallet, SplitRole.TENANT, effectiveTenantPercent));
        }
        if (franchiseWallet != null && franchisePercent.compareTo(BigDecimal.ZERO) > 0) {
            fullSplits.add(new PaymentSplit(franchiseWallet, SplitRole.FRANCHISE, franchisePercent));
        }

        // Rescale the entry splits (SELLER/COLLABORATOR) into the non-reserved
        // portion. The first N-1 splits are rounded to 2 decimals (HALF_UP);
        // the LAST split receives the exact remainder so the percents always
        // sum to exactly 100.00 — independent rounding could otherwise drift
        // by ±0.01 per split and silently change the total charged on-chain.
        BigDecimal assigned = BigDecimal.ZERO;
        for (int i = 0; i < entrySplits.size(); i++) {
            PaymentSplit split = entrySplits.get(i);
            BigDecimal normalizedPercent;
            if (i == entrySplits.size() - 1) {
                normalizedPercent = nonReservedTotal.subtract(assigned);
                if (normalizedPercent.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalStateException(
                            "Split normalization produced a non-positive remainder for the last split"
                                    + " — invalid split configuration");
                }
            } else {
                normalizedPercent = split.getPercent()
                        .multiply(nonReservedTotal)
                        .divide(entryTotal, 2, RoundingMode.HALF_UP);
                assigned = assigned.add(normalizedPercent);
            }
            fullSplits.add(new PaymentSplit(split.getWallet(), split.getRole(), normalizedPercent));
        }

        return fullSplits;
    }

    private PreparePaymentResponse toResponse(Order order) {
        String expiresAtIso = order.getExpiresAt() != null
                ? order.getExpiresAt().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                : null;

        return new PreparePaymentResponse(
                order.getId(),
                order.getUnsignedXdr(),
                order.getIntegrityHash(),
                order.getAmountXlm(),
                order.getOriginalAmountUsd(),
                order.getPriceCurrency(),
                order.getXlmUsdRate(),
                order.getMemo(),
                expiresAtIso,
                stellarConfig.getNetworkPassphrase()
        );
    }
}
