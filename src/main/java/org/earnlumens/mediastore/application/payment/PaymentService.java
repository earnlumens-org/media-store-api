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
 *   2. submit()  — validates integrity, submits signed tx, creates entitlement
 *
 * Security invariants:
 *   - Prices and splits are ALWAYS read from the database, never from the client.
 *   - The unsigned XDR integrity is verified via SHA-256 before submission.
 *   - Only the backend submits transactions to the Stellar network.
 *   - Owner cannot purchase their own content.
 *   - Duplicate purchases are rejected (unique index on userId + entryId).
 */
@Service
public class PaymentService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100.00");

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
     * Validates integrity, submits to Stellar, creates entitlement on success.
     */
    public SubmitPaymentResponse submit(String tenantId, String userId, SubmitPaymentRequest request) {
        String orderId = request.orderId();
        String signedXdr = request.signedXdr();

        // 1. Load and validate order
        Order order = orderRepository.findByTenantIdAndId(tenantId, orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        if (!order.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Order does not belong to this user");
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new IllegalStateException("Order is not in PENDING state (current: " + order.getStatus() + ")");
        }
        if (order.getExpiresAt() != null && order.getExpiresAt().isBefore(LocalDateTime.now(ZoneOffset.UTC))) {
            order.setStatus(OrderStatus.EXPIRED);
            orderRepository.save(order);
            throw new IllegalStateException("Order has expired");
        }

        // 2. Mark order as PROCESSING (prevents double-spend)
        order.setStatus(OrderStatus.PROCESSING);
        order.setSignedXdr(signedXdr);
        orderRepository.save(order);

        try {
            // 3. Submit to Stellar network
            String txHash = stellarTxService.submitTransaction(signedXdr);

            // 4. Mark order COMPLETED
            order.setStellarTxHash(txHash);
            order.setStatus(OrderStatus.COMPLETED);
            order.setCompletedAt(LocalDateTime.now(ZoneOffset.UTC));
            orderRepository.save(order);

            // 5. Expire all other PENDING orders for this buyer.
            // A successful submission changes the buyer's on-chain sequence number,
            // which invalidates the XDR in any previously prepared orders (tx_bad_seq).
            expireStalePendingOrders(order.getTenantId(), order.getUserId(), order.getId());

            // 6. Create entitlement
            createEntitlement(order);

            logger.info("Payment completed: orderId={}, txHash={}, entryId={}, collectionId={}",
                    orderId, txHash, order.getEntryId(), order.getCollectionId());

            return new SubmitPaymentResponse(
                    orderId,
                    txHash,
                    OrderStatus.COMPLETED.name(),
                    order.getEntryId(),
                    order.getCollectionId()
            );

        } catch (Exception e) {
            // Mark order as FAILED
            order.setStatus(OrderStatus.FAILED);
            orderRepository.save(order);
            logger.error("Payment submission failed: orderId={}", orderId, e);
            throw new RuntimeException("Payment failed: " + e.getMessage(), e);
        }
    }

    /**
     * Creates an ACTIVE entitlement for the buyer after successful payment.
     */
    private void createEntitlement(Order order) {
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

        entitlementRepository.save(entitlement);
        logger.info("Entitlement created: userId={}, entryId={}, collectionId={}, orderId={}",
                order.getUserId(), order.getEntryId(), order.getCollectionId(), order.getId());
    }

    /**
     * Expires all other PENDING orders for the same buyer.
     * After a successful Stellar submission the buyer's on-chain sequence number
     * has been incremented, so any previously built XDR is no longer valid (tx_bad_seq).
     * Expiring them forces a fresh prepare on retry.
     */
    private void expireStalePendingOrders(String tenantId, String userId, String completedOrderId) {
        List<Order> pendingOrders = orderRepository.findAllByTenantIdAndUserIdAndStatus(
                tenantId, userId, OrderStatus.PENDING);

        int expired = 0;
        for (Order pending : pendingOrders) {
            if (pending.getId().equals(completedOrderId)) continue;
            pending.setStatus(OrderStatus.EXPIRED);
            orderRepository.save(pending);
            expired++;
        }

        if (expired > 0) {
            logger.info("Expired {} stale PENDING orders for buyer userId={} after successful payment",
                    expired, userId);
        }
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
                    && t.getTenantFeePercent().compareTo(BigDecimal.ZERO) > 0
                    && t.getTenantWallet() != null
                    && !t.getTenantWallet().isBlank()) {
                tenantPercent = t.getTenantFeePercent();
                tenantWallet = t.getTenantWallet();
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

        for (PaymentSplit split : entrySplits) {
            BigDecimal normalizedPercent = split.getPercent()
                    .multiply(nonReservedTotal)
                    .divide(entryTotal, 2, RoundingMode.HALF_UP);
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
