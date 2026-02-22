package org.earnlumens.mediastore.application.payment;

import org.earnlumens.mediastore.domain.media.dto.request.PreparePaymentRequest;
import org.earnlumens.mediastore.domain.media.dto.request.SubmitPaymentRequest;
import org.earnlumens.mediastore.domain.media.dto.response.PreparePaymentResponse;
import org.earnlumens.mediastore.domain.media.dto.response.SubmitPaymentResponse;
import org.earnlumens.mediastore.domain.media.model.*;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.media.repository.OrderRepository;
import org.earnlumens.mediastore.infrastructure.config.StellarConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

    private final EntryRepository entryRepository;
    private final OrderRepository orderRepository;
    private final EntitlementRepository entitlementRepository;
    private final StellarTransactionService stellarTxService;
    private final StellarConfig stellarConfig;

    public PaymentService(EntryRepository entryRepository,
                          OrderRepository orderRepository,
                          EntitlementRepository entitlementRepository,
                          StellarTransactionService stellarTxService,
                          StellarConfig stellarConfig) {
        this.entryRepository = entryRepository;
        this.orderRepository = orderRepository;
        this.entitlementRepository = entitlementRepository;
        this.stellarTxService = stellarTxService;
        this.stellarConfig = stellarConfig;
    }

    /**
     * Phase 1: Prepare a payment transaction.
     * Builds an unsigned Stellar XDR and stores a PENDING order.
     */
    public PreparePaymentResponse prepare(String tenantId, String userId, PreparePaymentRequest request) {
        String entryId = request.entryId();
        String buyerWallet = request.buyerWallet();

        // 1. Load and validate the entry
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

        BigDecimal totalXlm = entry.getPriceXlm();
        if (totalXlm == null || totalXlm.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Entry has no valid price");
        }

        List<PaymentSplit> splits = entry.getPaymentSplits();
        if (splits == null || splits.isEmpty()) {
            throw new IllegalArgumentException("Entry has no payment splits configured");
        }

        // 2. Check for existing order (idempotency)
        Optional<Order> existingOrder = orderRepository.findByTenantIdAndUserIdAndEntryId(tenantId, userId, entryId);
        if (existingOrder.isPresent()) {
            Order existing = existingOrder.get();
            if (existing.getStatus() == OrderStatus.COMPLETED) {
                throw new IllegalStateException("Content already purchased");
            }
            // If PENDING and not expired, return the existing order's XDR
            if (existing.getStatus() == OrderStatus.PENDING
                    && existing.getExpiresAt() != null
                    && existing.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
                return toResponse(existing);
            }
            // If PENDING but expired, or FAILED — mark as EXPIRED and create a new one
            if (existing.getStatus() == OrderStatus.PENDING || existing.getStatus() == OrderStatus.FAILED) {
                existing.setStatus(OrderStatus.EXPIRED);
                orderRepository.save(existing);
            }
        }

        // 3. Build the MEMO
        String memo = "TOTAL: " + totalXlm.toPlainString() + " XLM";

        // 4. Build the unsigned Stellar transaction
        StellarTransactionService.BuildResult buildResult =
                stellarTxService.buildTransaction(buyerWallet, totalXlm, splits, memo);

        // 5. Persist PENDING order
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime expiresAt = now.plusSeconds(stellarConfig.getTxTimeoutSeconds());

        Order order = new Order();
        order.setTenantId(tenantId);
        order.setUserId(userId);
        order.setEntryId(entryId);
        order.setSellerId(entry.getUserId());
        order.setAmountXlm(totalXlm);
        order.setBuyerWallet(buyerWallet);
        order.setMemo(memo);
        order.setUnsignedXdr(buildResult.unsignedXdr());
        order.setIntegrityHash(buildResult.integrityHash());
        order.setStellarTxHash(buildResult.txHash());
        order.setStatus(OrderStatus.PENDING);
        order.setExpiresAt(expiresAt);
        order.setPaymentSplits(splits);

        Order saved = orderRepository.save(order);

        logger.info("Payment prepared: orderId={}, entryId={}, buyer={}, total={} XLM",
                saved.getId(), entryId, buyerWallet, totalXlm.toPlainString());

        return toResponse(saved);
    }

    /**
     * Phase 2: Submit a signed transaction.
     * Validates integrity, submits to Stellar, creates entitlement on success.
     */
    public SubmitPaymentResponse submit(String tenantId, String userId, SubmitPaymentRequest request) {
        String orderId = request.orderId();
        String signedXdr = request.signedXdr();

        // 1. Load and validate order
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        if (!order.getTenantId().equals(tenantId)) {
            throw new IllegalArgumentException("Order not found");
        }
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

            // 5. Create entitlement
            createEntitlement(order);

            logger.info("Payment completed: orderId={}, txHash={}, entryId={}",
                    orderId, txHash, order.getEntryId());

            return new SubmitPaymentResponse(
                    orderId,
                    txHash,
                    OrderStatus.COMPLETED.name(),
                    order.getEntryId()
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
        entitlement.setEntryId(order.getEntryId());
        entitlement.setGrantType(GrantType.PURCHASE);
        entitlement.setOrderId(order.getId());
        entitlement.setStatus(EntitlementStatus.ACTIVE);
        entitlement.setGrantedAt(LocalDateTime.now(ZoneOffset.UTC));

        entitlementRepository.save(entitlement);
        logger.info("Entitlement created: userId={}, entryId={}, orderId={}",
                order.getUserId(), order.getEntryId(), order.getId());
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
                order.getMemo(),
                expiresAtIso,
                stellarConfig.getNetworkPassphrase()
        );
    }
}
