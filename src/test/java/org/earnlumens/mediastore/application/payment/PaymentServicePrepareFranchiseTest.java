package org.earnlumens.mediastore.application.payment;

import org.earnlumens.mediastore.domain.media.dto.request.PreparePaymentRequest;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.PaymentSplit;
import org.earnlumens.mediastore.domain.media.model.SplitRole;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.media.repository.OrderRepository;
import org.earnlumens.mediastore.infrastructure.franchise.read.FranchiseReadModel;
import org.earnlumens.mediastore.infrastructure.franchise.read.FranchiseReadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Prepare-time franchise guard: a franchise owner buying through their OWN
 * storefront would self-discount the purchase by the franchise commission, so
 * prepare() rejects it with FRANCHISE_SELF_PURCHASE. The check is by user
 * identity (oauth id), NOT by wallet — connecting a different wallet in the
 * same session cannot bypass it.
 */
class PaymentServicePrepareFranchiseTest {

    private static final String TENANT = "earnlumens";
    private static final String BUYER = "buyer-oauth-id";
    private static final String SELLER = "seller-oauth-id";
    private static final String ENTRY_ID = "entry1";
    private static final String SLUG = "beta";
    private static final String BUYER_WALLET = "G" + "A".repeat(55);

    private EntryRepository entryRepository;
    private OrderRepository orderRepository;
    private FranchiseReadRepository franchiseReadRepository;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        entryRepository = mock(EntryRepository.class);
        orderRepository = mock(OrderRepository.class);
        franchiseReadRepository = mock(FranchiseReadRepository.class);
        service = new PaymentService(
                entryRepository, null, orderRepository, null,
                mock(StellarTransactionService.class), null, null, null, null,
                franchiseReadRepository);

        Entry entry = new Entry();
        entry.setUserId(SELLER);
        entry.setPaid(true);
        entry.setStatus(EntryStatus.PUBLISHED);
        entry.setPriceXlm(new BigDecimal("10"));
        entry.setPaymentSplits(List.of(
                new PaymentSplit("G" + "S".repeat(55), SplitRole.SELLER, new BigDecimal("90.00"))));
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID)).thenReturn(Optional.of(entry));
        when(orderRepository.findAllByTenantIdAndUserIdAndEntryId(TENANT, BUYER, ENTRY_ID))
                .thenReturn(List.of());
    }

    private FranchiseReadModel franchise(String ownerOauthUserId) {
        FranchiseReadModel f = new FranchiseReadModel();
        ReflectionTestUtils.setField(f, "status", "ACTIVE");
        ReflectionTestUtils.setField(f, "ownerOauthUserId", ownerOauthUserId);
        return f;
    }

    @Test
    void prepare_rejectsWhenBuyerOwnsTheFranchise() {
        when(franchiseReadRepository.findByTenantIdAndSlug(TENANT, SLUG))
                .thenReturn(Optional.of(franchise(BUYER)));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.prepare(TENANT, BUYER,
                        new PreparePaymentRequest(ENTRY_ID, null, SLUG, BUYER_WALLET)));

        assertEquals("FRANCHISE_SELF_PURCHASE", ex.getMessage());
    }

    @Test
    void prepare_allowsOtherBuyersThroughTheFranchise() {
        when(franchiseReadRepository.findByTenantIdAndSlug(TENANT, SLUG))
                .thenReturn(Optional.of(franchise("someone-else")));

        // Passing the self-purchase guard means reaching the split build, which
        // needs the (deliberately null) tenant config — any exception OTHER
        // than FRANCHISE_SELF_PURCHASE proves the guard let the buyer through.
        Exception ex = assertThrows(Exception.class,
                () -> service.prepare(TENANT, BUYER,
                        new PreparePaymentRequest(ENTRY_ID, null, SLUG, BUYER_WALLET)));

        org.junit.jupiter.api.Assertions.assertNotEquals("FRANCHISE_SELF_PURCHASE", ex.getMessage());
    }
}
