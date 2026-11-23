package org.earnlumens.mediastore.application.payment;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.earnlumens.mediastore.application.franchise.FranchiseBrandingUpdate;
import org.earnlumens.mediastore.application.franchise.FranchiseErrorCode;
import org.earnlumens.mediastore.application.franchise.FranchiseException;
import org.earnlumens.mediastore.application.franchise.FranchiseManagementService;
import org.earnlumens.mediastore.application.reseller.ResellerErrorCode;
import org.earnlumens.mediastore.application.reseller.ResellerException;
import org.earnlumens.mediastore.application.reseller.ResellerService;
import org.earnlumens.mediastore.domain.media.dto.request.CreateCollectionRequest;
import org.earnlumens.mediastore.domain.media.dto.request.CreateEntryRequest;
import org.earnlumens.mediastore.domain.media.dto.request.PrepareFastPassRequest;
import org.earnlumens.mediastore.domain.media.dto.request.PreparePaymentRequest;
import org.earnlumens.mediastore.domain.media.dto.request.PreparePublishFeeRequest;
import org.earnlumens.mediastore.domain.media.dto.request.PrepareTipRequest;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.infrastructure.config.PlatformConfig;
import org.earnlumens.mediastore.infrastructure.franchise.read.FranchiseBanReadRepository;
import org.earnlumens.mediastore.infrastructure.franchise.write.FranchiseWriteModel;
import org.earnlumens.mediastore.infrastructure.franchise.write.FranchiseWriteRepository;
import org.earnlumens.mediastore.infrastructure.r2.R2PresignedUrlService;
import org.earnlumens.mediastore.infrastructure.reseller.ResellerLink;
import org.earnlumens.mediastore.infrastructure.reseller.ResellerLinkRepository;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantReadModel;
import org.earnlumens.mediastore.infrastructure.tenant.read.TenantReadRepository;
import org.earnlumens.mediastore.web.reseller.dto.CreateResellerLinkRequest;
import org.earnlumens.mediastore.web.reseller.dto.UpdateResellerWalletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.stellar.sdk.KeyPair;
import org.stellar.sdk.MuxedAccount;
import org.stellar.sdk.StrKey;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MYCELIUM-RESILIENCE.md §0.0, P30-2 (CAP-84): a wallet is only ever a classic
 * {@code G…} account. Muxed accounts ({@code M…}), contracts ({@code C…}) and
 * the Protocol 30 muxed contracts ({@code W…}) are rejected with the existing
 * format error before any Horizon lookup — that lookup fails open.
 */
class StellarAddressTypeRejectionTest {

    private static final String TENANT = "acme";
    private static final String USER = "user-1";

    /** Real strkeys of each type, encoded by the SDK from fixed bytes. */
    static final String ACCOUNT = KeyPair.fromSecretSeed(filled(32, 1)).getAccountId();
    static final String MUXED_ACCOUNT = new MuxedAccount(ACCOUNT, BigInteger.valueOf(1234567890L)).getAddress();
    static final String CONTRACT = StrKey.encodeContract(filled(32, 7));
    static final String MUXED_CONTRACT = StrKey.encodeMuxedContract(
            ByteBuffer.allocate(40).put(filled(32, 7)).putLong(1234567890L).array());

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static byte[] filled(int length, int value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    /** Shared with the per-service tests through {@code @MethodSource}. */
    public static Stream<String> nonAccountAddresses() {
        return Stream.of(MUXED_ACCOUNT, CONTRACT, MUXED_CONTRACT);
    }

    static Stream<Arguments> walletProperties() {
        return Stream.of(
                Arguments.of(CreateEntryRequest.class, "sellerWallet"),
                Arguments.of(CreateCollectionRequest.class, "sellerWallet"),
                Arguments.of(PreparePaymentRequest.class, "buyerWallet"),
                Arguments.of(PrepareTipRequest.class, "buyerWallet"),
                Arguments.of(PrepareFastPassRequest.class, "buyerWallet"),
                Arguments.of(PreparePublishFeeRequest.class, "buyerWallet"),
                Arguments.of(CreateResellerLinkRequest.class, "wallet"),
                Arguments.of(UpdateResellerWalletRequest.class, "wallet"));
    }

    private StellarTransactionService stellar;

    @BeforeEach
    void setUp() {
        stellar = mock(StellarTransactionService.class);
        when(stellar.isAccountActive(any())).thenReturn(true);
    }

    @Test
    void vectorsAreValidStrKeysOfTheirOwnType() {
        assertTrue(StrKey.isValidEd25519PublicKey(ACCOUNT));
        assertTrue(StrKey.isValidMed25519PublicKey(MUXED_ACCOUNT));
        assertTrue(StrKey.isValidContract(CONTRACT));
        assertTrue(StrKey.isValidMuxedContract(MUXED_CONTRACT));
        assertEquals(List.of('G', 'M', 'C', 'W'), Stream.of(ACCOUNT, MUXED_ACCOUNT, CONTRACT, MUXED_CONTRACT)
                .map(address -> address.charAt(0)).toList());
    }

    // ── Request DTOs (@Valid at the controller boundary) ─────────

    private static <T> List<String> violations(Class<T> type, String property, String value) {
        return VALIDATOR.validateValue(type, property, value).stream()
                .map(ConstraintViolation::getMessage).toList();
    }

    @ParameterizedTest
    @MethodSource("walletProperties")
    void requestDto_acceptsAccountAndRejectsEveryOtherAddressType(Class<?> type, String property) {
        assertEquals(List.of(), violations(type, property, ACCOUNT));
        // M… and W… (69 chars) also trip the DTOs' @Size(56); the format error is the one asserted.
        nonAccountAddresses().forEach(address ->
                assertTrue(violations(type, property, address).contains("Invalid Stellar public key"), address));
    }

    // ── ResellerService ──────────────────────────────────────────

    private ResellerService resellerService(EntryRepository entries, ResellerLinkRepository links) {
        return new ResellerService(entries, links, stellar);
    }

    @ParameterizedTest
    @MethodSource("nonAccountAddresses")
    void reseller_createLink_rejectsWithWalletFormat(String address) {
        EntryRepository entries = mock(EntryRepository.class);
        ResellerLinkRepository links = mock(ResellerLinkRepository.class);
        Entry entry = new Entry();
        entry.setUserId("creator");
        entry.setPaid(true);
        entry.setResellerEnabled(true);
        when(entries.findByTenantIdAndId(TENANT, "entry-1")).thenReturn(Optional.of(entry));

        ResellerException e = assertThrows(ResellerException.class,
                () -> resellerService(entries, links).getOrCreateLink(TENANT, USER, "entry-1", address));

        assertEquals(ResellerErrorCode.WALLET_FORMAT, e.getErrorCode());
        verify(stellar, never()).isAccountActive(any());
        verify(links, never()).save(any());
    }

    @ParameterizedTest
    @MethodSource("nonAccountAddresses")
    void reseller_updateWallet_rejectsWithWalletFormat(String address) {
        ResellerLinkRepository links = mock(ResellerLinkRepository.class);
        ResellerLink link = new ResellerLink();
        link.setResellerWallet(ACCOUNT);
        when(links.findByTenantIdAndIdAndResellerUserId(TENANT, "link-1", USER)).thenReturn(Optional.of(link));

        ResellerException e = assertThrows(ResellerException.class,
                () -> resellerService(mock(EntryRepository.class), links)
                        .updateLinkWallet(TENANT, USER, "link-1", address));

        assertEquals(ResellerErrorCode.WALLET_FORMAT, e.getErrorCode());
        assertEquals(ACCOUNT, link.getResellerWallet());
        verify(stellar, never()).isAccountActive(any());
        verify(links, never()).save(any());
    }

    @Test
    void reseller_updateWallet_acceptsAccount() {
        ResellerLinkRepository links = mock(ResellerLinkRepository.class);
        ResellerLink link = new ResellerLink();
        when(links.findByTenantIdAndIdAndResellerUserId(TENANT, "link-1", USER)).thenReturn(Optional.of(link));
        when(links.save(any(ResellerLink.class))).thenAnswer(inv -> inv.getArgument(0));

        resellerService(mock(EntryRepository.class), links).updateLinkWallet(TENANT, USER, "link-1", ACCOUNT);

        assertEquals(ACCOUNT, link.getResellerWallet());
        verify(links).save(link);
    }

    // ── FranchiseManagementService ───────────────────────────────

    private FranchiseManagementService franchiseService(FranchiseWriteRepository franchises) {
        TenantReadModel tenant = new TenantReadModel();
        tenant.setStatus("ACTIVE");
        tenant.setFranchisesEnabled(true);
        TenantReadRepository tenants = mock(TenantReadRepository.class);
        when(tenants.findBySubdomain(TENANT)).thenReturn(Optional.of(tenant));
        return new FranchiseManagementService(franchises, mock(FranchiseBanReadRepository.class), tenants,
                mock(R2PresignedUrlService.class), stellar);
    }

    @ParameterizedTest
    @MethodSource("nonAccountAddresses")
    void franchise_create_rejectsWithWalletFormat(String address) {
        FranchiseWriteRepository franchises = mock(FranchiseWriteRepository.class);

        FranchiseException e = assertThrows(FranchiseException.class,
                () -> franchiseService(franchises).createFranchise(TENANT, USER, "user", "User",
                        "my-shop", address, null, null, null, true));

        assertEquals(FranchiseErrorCode.WALLET_FORMAT, e.getErrorCode());
        verify(stellar, never()).isAccountActive(any());
        verify(franchises, never()).save(any());
    }

    @ParameterizedTest
    @MethodSource("nonAccountAddresses")
    void franchise_updatePayoutWallet_rejectsWithWalletFormat(String address) {
        FranchiseWriteRepository franchises = mock(FranchiseWriteRepository.class);
        FranchiseWriteModel franchise = new FranchiseWriteModel();
        franchise.setPayoutWallet(ACCOUNT);
        when(franchises.findByTenantIdAndIdAndOwnerOauthUserId(TENANT, "fr-1", USER))
                .thenReturn(Optional.of(franchise));

        FranchiseException e = assertThrows(FranchiseException.class,
                () -> franchiseService(franchises).updateOwnFranchise(TENANT, USER, "fr-1",
                        new FranchiseBrandingUpdate(null, null, null, null, null, address)));

        assertEquals(FranchiseErrorCode.WALLET_FORMAT, e.getErrorCode());
        assertEquals(ACCOUNT, franchise.getPayoutWallet());
        verify(stellar, never()).isAccountActive(any());
        verify(franchises, never()).save(any());
    }

    @Test
    void franchise_create_acceptsAccount() {
        FranchiseWriteRepository franchises = mock(FranchiseWriteRepository.class);
        when(franchises.save(any(FranchiseWriteModel.class))).thenAnswer(inv -> inv.getArgument(0));

        FranchiseWriteModel created = franchiseService(franchises).createFranchise(TENANT, USER, "user", "User",
                "my-shop", ACCOUNT, null, null, null, true);

        assertEquals(ACCOUNT, created.getPayoutWallet());
    }

    // ── PlatformConfig (startup sanity check, logs at ERROR) ─────

    private static long startupErrors(String wallet) {
        Logger logger = (Logger) LoggerFactory.getLogger(PlatformConfig.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            PlatformConfig config = new PlatformConfig();
            config.setWallet(wallet);
            ReflectionTestUtils.invokeMethod(config, "validateWallet");
            return appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).count();
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void platformWallet_flagsEveryNonAccountAddressAtStartup() {
        assertEquals(0, startupErrors(ACCOUNT));
        nonAccountAddresses().forEach(address -> assertEquals(1, startupErrors(address), address));
    }
}
