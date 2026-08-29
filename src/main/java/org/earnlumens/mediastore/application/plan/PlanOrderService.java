package org.earnlumens.mediastore.application.plan;

import org.earnlumens.mediastore.application.payment.StellarTransactionService;
import org.earnlumens.mediastore.domain.media.model.Order;
import org.earnlumens.mediastore.domain.media.model.OrderStatus;
import org.earnlumens.mediastore.domain.media.model.PaymentSplit;
import org.earnlumens.mediastore.domain.media.model.SplitRole;
import org.earnlumens.mediastore.infrastructure.billing.read.PlatformBillingConfigReadModel;
import org.earnlumens.mediastore.infrastructure.billing.read.PlatformBillingConfigReadRepository;
import org.earnlumens.mediastore.infrastructure.config.PlatformConfig;
import org.earnlumens.mediastore.infrastructure.config.StellarConfig;
import org.earnlumens.mediastore.infrastructure.external.admin.PlanApplyClient;
import org.earnlumens.mediastore.infrastructure.external.pricing.XlmUsdPriceService;
import org.earnlumens.mediastore.infrastructure.persistence.plan.PlanOrderDocument;
import org.earnlumens.mediastore.infrastructure.persistence.plan.PlanOrderMongoRepository;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantConfigService;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantReadModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Two-phase Stellar purchase of the Pro plan (custom-domain-upgrade 1B).
 *
 * <p>Reuses the audited payment pipeline of {@link StellarTransactionService}
 * (build unsigned XDR → user signs → strict verification → Horizon submission
 * → mandatory on-chain confirmation) with plan-specific rules:
 * <ul>
 *   <li>Only the tenant OWNER can buy ({@code ownerOauthUserId} verified
 *       against the tenants read model, never from the client).</li>
 *   <li>Price comes from the global {@code platform_billing_config}
 *       (SUPERADMIN-controlled), converted USD→XLM at the locked snapshot
 *       rate. 100% goes to the platform wallet.</li>
 *   <li>Orders live in {@code plan_orders}; admin-api applies the plan
 *       (callback + backup sweep) because it is the only writer of
 *       {@code tenants}.</li>
 *   <li>Renewal is the same purchase — admin-api extends from
 *       {@code max(now, planExpiresAt)}.</li>
 * </ul>
 */
@Service
public class PlanOrderService {

    private static final Logger logger = LoggerFactory.getLogger(PlanOrderService.class);

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100.00");
    private static final Pattern STELLAR_PUBLIC_KEY = Pattern.compile("^G[A-Z2-7]{55}$");
    private static final FindAndModifyOptions RETURN_NEW = FindAndModifyOptions.options().returnNew(true);

    /** On-chain confirmation polling (mirrors PaymentService). */
    private static final int ONCHAIN_VERIFY_MAX_ATTEMPTS = 10;
    private static final int ONCHAIN_VERIFY_CONFIRMED_ATTEMPTS = 4;
    private static final long ONCHAIN_VERIFY_DELAY_MS = 2_000L;
    /** Grace after the tx window before a stuck PROCESSING order is finalized. */
    private static final long RECONCILE_GRACE_SECONDS = 120L;

    public enum PlanPeriod { MONTHLY, YEARLY }

    public record PreparePlanResponse(String orderId, String unsignedXdr, String period,
                                      BigDecimal amountUsd, BigDecimal amountXlm,
                                      BigDecimal xlmUsdRate, String memo, LocalDateTime expiresAt) {}

    public record PlanOrderStatusResponse(String orderId, String status, String period,
                                          BigDecimal amountUsd, BigDecimal amountXlm,
                                          String stellarTxHash, LocalDateTime completedAt) {}

    private final PlanOrderMongoRepository repository;
    private final MongoTemplate mongoTemplate;
    private final TenantConfigService tenantConfigService;
    private final PlatformBillingConfigReadRepository billingConfigRepository;
    private final StellarTransactionService stellarTxService;
    private final StellarConfig stellarConfig;
    private final PlatformConfig platformConfig;
    private final XlmUsdPriceService xlmUsdPriceService;
    private final PlanApplyClient planApplyClient;

    public PlanOrderService(PlanOrderMongoRepository repository,
                            MongoTemplate mongoTemplate,
                            TenantConfigService tenantConfigService,
                            PlatformBillingConfigReadRepository billingConfigRepository,
                            StellarTransactionService stellarTxService,
                            StellarConfig stellarConfig,
                            PlatformConfig platformConfig,
                            XlmUsdPriceService xlmUsdPriceService,
                            PlanApplyClient planApplyClient) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.tenantConfigService = tenantConfigService;
        this.billingConfigRepository = billingConfigRepository;
        this.stellarTxService = stellarTxService;
        this.stellarConfig = stellarConfig;
        this.platformConfig = platformConfig;
        this.xlmUsdPriceService = xlmUsdPriceService;
        this.planApplyClient = planApplyClient;
    }

    // ------------------------------------------------------------------ prepare

    public PreparePlanResponse prepare(String tenantId, String userId, String rawPeriod, String buyerWallet) {
        PlanPeriod period = parsePeriod(rawPeriod);
        if (buyerWallet == null || !STELLAR_PUBLIC_KEY.matcher(buyerWallet).matches()) {
            throw new IllegalArgumentException("Invalid buyer wallet");
        }

        TenantReadModel tenant = requireOwnedActiveTenant(tenantId, userId);

        // Existing-order hygiene: reuse a live PENDING order for the same
        // period+wallet, expire stale ones, and never allow a second payment
        // while a PROCESSING order could still land on-chain.
        PlanOrderDocument reusable = processExistingOrders(tenantId, userId, period, buyerWallet);
        if (reusable != null) {
            return toPrepareResponse(reusable);
        }

        // Price is ALWAYS read server-side from the global billing config.
        PlatformBillingConfigReadModel cfg = billingConfigRepository
                .findById(PlatformBillingConfigReadModel.SINGLETON_ID)
                .orElseGet(PlatformBillingConfigReadModel::new);
        BigDecimal amountUsd = (period == PlanPeriod.YEARLY
                ? cfg.getPlanPriceYearlyUsd() : cfg.getPlanPriceMonthlyUsd())
                .setScale(2, RoundingMode.HALF_UP);

        var snapshot = xlmUsdPriceService.getPrice();
        BigDecimal rate = snapshot != null ? snapshot.price() : null;
        if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("XLM/USD price unavailable — cannot process plan purchases");
        }
        BigDecimal totalXlm = amountUsd.divide(rate, 7, RoundingMode.CEILING);

        String platformWallet = platformConfig.getWallet();
        List<PaymentSplit> splits = List.of(new PaymentSplit(platformWallet, SplitRole.PLATFORM, ONE_HUNDRED));
        if (!stellarTxService.isAccountActiveCached(platformWallet)) {
            logger.error("Platform wallet not active on Stellar — plan purchases blocked: {}", platformWallet);
            throw new IllegalStateException("SPLIT_WALLET_NOT_ACTIVE");
        }

        String memo = "PRO " + (period == PlanPeriod.YEARLY ? "1Y" : "1M") + ": "
                + totalXlm.setScale(4, RoundingMode.DOWN).stripTrailingZeros().toPlainString() + " XLM";

        StellarTransactionService.BuildResult buildResult =
                stellarTxService.buildTransaction(buyerWallet, totalXlm, splits, memo);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        PlanOrderDocument order = new PlanOrderDocument();
        order.setTenantId(tenantId);
        order.setOwnerOauthUserId(userId);
        order.setPeriod(period.name());
        order.setAmountUsd(amountUsd);
        order.setAmountXlm(totalXlm);
        order.setXlmUsdRate(rate);
        order.setStatus(OrderStatus.PENDING.name());
        order.setBuyerWallet(buyerWallet);
        order.setMemo(memo);
        order.setUnsignedXdr(buildResult.unsignedXdr());
        order.setIntegrityHash(buildResult.integrityHash());
        order.setStellarTxHash(buildResult.txHash());
        order.setExpiresAt(now.plusSeconds(stellarConfig.getTxTimeoutSeconds()));

        PlanOrderDocument saved = repository.save(order);
        logger.info("Plan order prepared: orderId={}, tenant={}, period={}, amount=${} ({} XLM)",
                saved.getId(), tenantId, period, amountUsd.toPlainString(), totalXlm.toPlainString());
        return toPrepareResponse(saved);
    }

    // ------------------------------------------------------------------ submit

    public PlanOrderStatusResponse submit(String tenantId, String userId, String orderId, String signedXdr) {
        if (orderId == null || orderId.isBlank() || signedXdr == null || signedXdr.isBlank()) {
            throw new IllegalArgumentException("orderId and signedXdr are required");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // 1. Atomic CAS lock PENDING → PROCESSING (tenant + owner + expiry enforced).
        Query lock = Query.query(Criteria.where("_id").is(orderId)
                .and("tenantId").is(tenantId)
                .and("ownerOauthUserId").is(userId)
                .and("status").is(OrderStatus.PENDING.name())
                .and("expiresAt").gt(now));
        Update toProcessing = new Update()
                .set("status", OrderStatus.PROCESSING.name())
                .set("signedXdr", signedXdr);
        PlanOrderDocument order = mongoTemplate.findAndModify(lock, toProcessing, RETURN_NEW, PlanOrderDocument.class);
        if (order == null) {
            throw explainLockFailure(tenantId, userId, orderId, now);
        }

        // 2. Strict verification of the signed XDR against the persisted order.
        final org.stellar.sdk.Transaction transaction;
        try {
            transaction = stellarTxService.verifySignedXdrAgainstOrder(signedXdr, toFacade(order));
        } catch (SecurityException e) {
            transition(orderId, OrderStatus.PROCESSING, OrderStatus.FAILED);
            logger.error("Plan order: rejected tampered/mismatched signed XDR: orderId={}: {}",
                    orderId, e.getMessage());
            throw new IllegalArgumentException("Signed transaction does not match the prepared order");
        }

        String txHash = order.getStellarTxHash();

        // 3. Anti-replay: one tx hash pays for a plan at most once.
        if (repository.existsByStatusAndStellarTxHashAndIdNot(OrderStatus.COMPLETED.name(), txHash, orderId)) {
            transition(orderId, OrderStatus.PROCESSING, OrderStatus.FAILED);
            logger.error("Plan order replay blocked: txHash={} already consumed (orderId={})", txHash, orderId);
            throw new IllegalStateException("Transaction hash already used by a completed order");
        }

        // 4. Submit and confirm on-chain — Horizon is the only source of truth.
        StellarTransactionService.SubmissionOutcome outcome = stellarTxService.submitTransaction(transaction);
        if (outcome == StellarTransactionService.SubmissionOutcome.REJECTED) {
            transition(orderId, OrderStatus.PROCESSING, OrderStatus.FAILED);
            throw new RuntimeException("Payment failed: transaction rejected by the Stellar network");
        }
        boolean confirmed = confirmOnChainWithRetry(txHash, order,
                outcome == StellarTransactionService.SubmissionOutcome.UNKNOWN);
        if (!confirmed) {
            transition(orderId, OrderStatus.PROCESSING, OrderStatus.FAILED);
            logger.error("Plan payment not confirmed on-chain: orderId={}, txHash={}", orderId, txHash);
            throw new RuntimeException("Payment failed: transaction could not be confirmed on the Stellar network");
        }

        // 5. CAS PROCESSING → COMPLETED, then hand off to admin-api (best effort —
        // the sweep is the safety net; the money is already on-chain).
        PlanOrderDocument completed = completeOrder(orderId, OrderStatus.PROCESSING);
        if (completed == null) {
            throw new IllegalStateException("Order state changed concurrently during completion");
        }
        notifyApply(orderId);

        logger.info("Plan order completed: orderId={}, tenant={}, period={}, txHash={}",
                orderId, tenantId, completed.getPeriod(), txHash);
        return toStatusResponse(completed);
    }

    // ------------------------------------------------------------------ status

    public PlanOrderStatusResponse getOrderStatus(String tenantId, String userId, String orderId) {
        PlanOrderDocument order = repository.findByIdAndTenantIdAndOwnerOauthUserId(orderId, tenantId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));
        return toStatusResponse(order);
    }

    // ------------------------------------------------------------------ internals

    private TenantReadModel requireOwnedActiveTenant(String tenantId, String userId) {
        TenantReadModel tenant = tenantConfigService.findActiveBySubdomain(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        if (tenant.getOwnerOauthUserId() == null || !tenant.getOwnerOauthUserId().equals(userId)) {
            throw new IllegalArgumentException("PLAN_OWNER_ONLY");
        }
        return tenant;
    }

    /**
     * Handles pre-existing orders before creating a new one (subset of
     * PaymentService.processExistingOrders — COMPLETED never blocks because
     * renewals are legitimate repeat purchases).
     */
    private PlanOrderDocument processExistingOrders(String tenantId, String userId,
                                                    PlanPeriod period, String buyerWallet) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<PlanOrderDocument> open = repository.findByTenantIdAndOwnerOauthUserIdAndStatusIn(
                tenantId, userId,
                List.of(OrderStatus.PENDING.name(), OrderStatus.PROCESSING.name()));
        PlanOrderDocument reusable = null;
        for (PlanOrderDocument existing : open) {
            if (OrderStatus.PENDING.name().equals(existing.getStatus())) {
                boolean live = existing.getExpiresAt() != null && existing.getExpiresAt().isAfter(now);
                if (live && existing.getPeriod().equals(period.name())
                        && buyerWallet.equals(existing.getBuyerWallet())) {
                    reusable = existing;
                } else if (!live) {
                    transition(existing.getId(), OrderStatus.PENDING, OrderStatus.EXPIRED);
                }
            } else { // PROCESSING — reconcile against the chain before deciding.
                if (existing.getStellarTxHash() != null
                        && stellarTxService.verifyTransactionOnChain(existing.getStellarTxHash(), toFacade(existing))) {
                    PlanOrderDocument completed = completeOrder(existing.getId(), OrderStatus.PROCESSING);
                    if (completed != null) {
                        logger.warn("Plan order reconciliation: recovered confirmed payment orderId={}", existing.getId());
                        notifyApply(existing.getId());
                    }
                    continue; // plan paid & applied — a NEW order (renewal) may proceed.
                }
                boolean windowClosed = existing.getExpiresAt() == null
                        || !existing.getExpiresAt().plusSeconds(RECONCILE_GRACE_SECONDS).isAfter(now);
                if (windowClosed) {
                    transition(existing.getId(), OrderStatus.PROCESSING, OrderStatus.EXPIRED);
                } else {
                    // A submit may be in flight — blocking here prevents double payment.
                    throw new IllegalStateException("PAYMENT_IN_PROGRESS");
                }
            }
        }
        return reusable;
    }

    private boolean confirmOnChainWithRetry(String txHash, PlanOrderDocument order, boolean ambiguousOutcome) {
        int attempts = ambiguousOutcome ? ONCHAIN_VERIFY_MAX_ATTEMPTS : ONCHAIN_VERIFY_CONFIRMED_ATTEMPTS;
        Order facade = toFacade(order);
        for (int i = 0; i < attempts; i++) {
            if (i > 0) {
                try {
                    Thread.sleep(ONCHAIN_VERIFY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (stellarTxService.verifyTransactionOnChain(txHash, facade)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adapter so the plan order can flow through the exact XDR-verification and
     * on-chain-confirmation code paths audited for content purchases (they only
     * read hash, buyer wallet, memo, amount and split snapshot).
     */
    private Order toFacade(PlanOrderDocument doc) {
        Order facade = new Order();
        facade.setId(doc.getId());
        facade.setStellarTxHash(doc.getStellarTxHash());
        facade.setBuyerWallet(doc.getBuyerWallet());
        facade.setMemo(doc.getMemo());
        facade.setAmountXlm(doc.getAmountXlm());
        facade.setPaymentSplits(List.of(
                new PaymentSplit(platformConfig.getWallet(), SplitRole.PLATFORM, ONE_HUNDRED)));
        return facade;
    }

    private void transition(String orderId, OrderStatus from, OrderStatus to) {
        Query q = Query.query(Criteria.where("_id").is(orderId).and("status").is(from.name()));
        mongoTemplate.findAndModify(q, new Update().set("status", to.name()), RETURN_NEW, PlanOrderDocument.class);
    }

    private PlanOrderDocument completeOrder(String orderId, OrderStatus from) {
        Query q = Query.query(Criteria.where("_id").is(orderId).and("status").is(from.name()));
        Update u = new Update()
                .set("status", OrderStatus.COMPLETED.name())
                .set("completedAt", LocalDateTime.now(ZoneOffset.UTC));
        return mongoTemplate.findAndModify(q, u, RETURN_NEW, PlanOrderDocument.class);
    }

    private void notifyApply(String orderId) {
        try {
            planApplyClient.apply(orderId);
        } catch (RuntimeException e) {
            logger.warn("Plan apply callback failed for order {} (sweep will retry): {}", orderId, e.getMessage());
        }
    }

    private RuntimeException explainLockFailure(String tenantId, String userId, String orderId, LocalDateTime now) {
        PlanOrderDocument existing = repository.findByIdAndTenantIdAndOwnerOauthUserId(orderId, tenantId, userId)
                .orElse(null);
        if (existing == null) {
            return new IllegalArgumentException("Order not found");
        }
        if (OrderStatus.PENDING.name().equals(existing.getStatus())
                && existing.getExpiresAt() != null && !existing.getExpiresAt().isAfter(now)) {
            transition(orderId, OrderStatus.PENDING, OrderStatus.EXPIRED);
            return new IllegalStateException("Order has expired");
        }
        return new IllegalStateException("Order is not in PENDING state (current: " + existing.getStatus() + ")");
    }

    private static PlanPeriod parsePeriod(String raw) {
        try {
            return PlanPeriod.valueOf(raw == null ? "" : raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("period must be MONTHLY or YEARLY");
        }
    }

    private static PreparePlanResponse toPrepareResponse(PlanOrderDocument o) {
        return new PreparePlanResponse(o.getId(), o.getUnsignedXdr(), o.getPeriod(),
                o.getAmountUsd(), o.getAmountXlm(), o.getXlmUsdRate(), o.getMemo(), o.getExpiresAt());
    }

    private static PlanOrderStatusResponse toStatusResponse(PlanOrderDocument o) {
        return new PlanOrderStatusResponse(o.getId(), o.getStatus(), o.getPeriod(),
                o.getAmountUsd(), o.getAmountXlm(), o.getStellarTxHash(), o.getCompletedAt());
    }
}
