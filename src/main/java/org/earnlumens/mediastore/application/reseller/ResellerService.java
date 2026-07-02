package org.earnlumens.mediastore.application.reseller;

import org.earnlumens.mediastore.application.payment.StellarTransactionService;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.infrastructure.reseller.ResellerLink;
import org.earnlumens.mediastore.infrastructure.reseller.ResellerLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Self-service for resellers: a logged-in user generates and manages links that
 * earn them a commission for distributing another creator's paid content.
 *
 * <p>The tenant is always resolved from the request host by the controller,
 * never trusted from the client. Ownership is re-checked against the database on
 * every call so a link can only be read or edited by the user who created it.
 *
 * <p>The commission percent is never frozen on the link: {@link #listMyLinks}
 * and the purchase flow both read it live from the entry, so a creator's change
 * takes effect on the next sale of every existing link.
 */
@Service
public class ResellerService {

    private static final Logger logger = LoggerFactory.getLogger(ResellerService.class);
    private static final Pattern STELLAR_PUBLIC_KEY = Pattern.compile("^G[A-Z2-7]{55}$");
    private static final String CODE_ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    /** 22 base-62 chars ≈ 131 bits of entropy — unguessable and opaque. */
    private static final int CODE_LENGTH = 22;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EntryRepository entryRepository;
    private final ResellerLinkRepository linkRepository;
    private final StellarTransactionService stellarTransactionService;

    public ResellerService(EntryRepository entryRepository,
                           ResellerLinkRepository linkRepository,
                           StellarTransactionService stellarTransactionService) {
        this.entryRepository = entryRepository;
        this.linkRepository = linkRepository;
        this.stellarTransactionService = stellarTransactionService;
    }

    /**
     * Describes whether/how an entry can be resold and its current price, so the
     * "EARN LUMENS" modal can show an orientative commission. {@code callerUserId}
     * may be null for the public endpoint; when present, {@code ownContent} flags
     * the creator so the UI can hide the generate action.
     */
    public ResellerEntryInfo getEntryResellerInfo(String tenantId, String callerUserId, String entryId) {
        Entry entry = entryRepository.findByTenantIdAndId(tenantId, entryId)
                .orElseThrow(() -> new ResellerException(ResellerErrorCode.ENTRY_NOT_FOUND, 404));
        boolean enabled = entry.isPaid() && entry.isResellerEnabled();
        boolean ownContent = callerUserId != null && callerUserId.equals(entry.getUserId());
        return new ResellerEntryInfo(
                entry.getId(),
                entry.getTitle(),
                entry.getType() != null ? entry.getType().name().toLowerCase() : "resource",
                enabled,
                entry.getResellerCommissionPercent(),
                entry.getPriceXlm(),
                entry.getPriceUsd(),
                entry.getPriceCurrency() != null ? entry.getPriceCurrency().name() : null,
                ownContent);
    }

    /**
     * Returns the caller's existing link for an entry, or creates one. A user
     * has at most one link per entry, so a repeat request returns the same code.
     * The wallet is validated (format + funded on-chain) because it becomes a
     * payment-split destination; an unfunded wallet would fail every sale.
     */
    public ResellerLinkView getOrCreateLink(String tenantId, String userId, String entryId, String wallet) {
        Entry entry = entryRepository.findByTenantIdAndId(tenantId, entryId)
                .orElseThrow(() -> new ResellerException(ResellerErrorCode.ENTRY_NOT_FOUND, 404));
        if (!entry.isPaid() || !entry.isResellerEnabled()) {
            throw new ResellerException(ResellerErrorCode.ENTRY_NOT_RESELLABLE, 409);
        }
        if (userId.equals(entry.getUserId())) {
            throw new ResellerException(ResellerErrorCode.OWN_CONTENT, 409);
        }

        Optional<ResellerLink> existing =
                linkRepository.findByTenantIdAndEntryIdAndResellerUserId(tenantId, entryId, userId);
        if (existing.isPresent()) {
            return toView(existing.get(), entry);
        }

        String normalizedWallet = validateWallet(wallet);

        ResellerLink link = new ResellerLink();
        link.setTenantId(tenantId);
        link.setEntryId(entryId);
        link.setResellerUserId(userId);
        link.setResellerWallet(normalizedWallet);
        link.setCode(generateUniqueCode(tenantId));
        try {
            ResellerLink saved = linkRepository.save(link);
            logger.info("Created reseller link: tenantId={}, entryId={}, userId={}, id={}",
                    tenantId, entryId, userId, saved.getId());
            return toView(saved, entry);
        } catch (DuplicateKeyException race) {
            // Concurrent create for the same (tenant, entry, user): return the winner.
            return linkRepository.findByTenantIdAndEntryIdAndResellerUserId(tenantId, entryId, userId)
                    .map(l -> toView(l, entry))
                    .orElseThrow(() -> race);
        }
    }

    /** All links owned by the caller, joined with each entry's live state. */
    public List<ResellerLinkView> listMyLinks(String tenantId, String userId) {
        return linkRepository.findByTenantIdAndResellerUserId(tenantId, userId).stream()
                .map(link -> {
                    Entry entry = entryRepository.findByTenantIdAndId(tenantId, link.getEntryId()).orElse(null);
                    return toView(link, entry);
                })
                .toList();
    }

    /** Change the payout wallet of an owned link. The code is unchanged. */
    public ResellerLinkView updateLinkWallet(String tenantId, String userId, String linkId, String wallet) {
        ResellerLink link = linkRepository.findByTenantIdAndIdAndResellerUserId(tenantId, linkId, userId)
                .orElseThrow(() -> new ResellerException(ResellerErrorCode.NOT_FOUND, 404));
        String normalizedWallet = validateWallet(wallet);
        link.setResellerWallet(normalizedWallet);
        ResellerLink saved = linkRepository.save(link);
        Entry entry = entryRepository.findByTenantIdAndId(tenantId, link.getEntryId()).orElse(null);
        logger.info("Updated reseller link wallet: tenantId={}, id={}, userId={}", tenantId, linkId, userId);
        return toView(saved, entry);
    }

    private String validateWallet(String wallet) {
        if (wallet == null || !STELLAR_PUBLIC_KEY.matcher(wallet).matches()) {
            throw new ResellerException(ResellerErrorCode.WALLET_FORMAT, 400);
        }
        if (!stellarTransactionService.isAccountActive(wallet)) {
            throw new ResellerException(ResellerErrorCode.WALLET_NOT_ACTIVATED, 400);
        }
        return wallet;
    }

    private String generateUniqueCode(String tenantId) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = randomCode();
            if (linkRepository.findByTenantIdAndCode(tenantId, code).isEmpty()) {
                return code;
            }
        }
        // 131 bits of entropy makes 5 collisions astronomically unlikely.
        throw new IllegalStateException("Unable to generate a unique reseller code");
    }

    private static String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    private ResellerLinkView toView(ResellerLink link, Entry entry) {
        boolean enabled = entry != null && entry.isPaid() && entry.isResellerEnabled();
        return new ResellerLinkView(
                link.getId(),
                link.getEntryId(),
                entry != null ? entry.getTitle() : null,
                entry != null && entry.getType() != null ? entry.getType().name().toLowerCase() : null,
                link.getCode(),
                link.getResellerWallet(),
                enabled,
                entry != null ? entry.getResellerCommissionPercent() : null,
                entry != null ? entry.getPriceXlm() : null,
                entry != null ? entry.getPriceUsd() : null,
                entry != null && entry.getPriceCurrency() != null ? entry.getPriceCurrency().name() : null,
                link.getCreatedAt());
    }
}
