package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.request.CreateEntryRequest;
import org.earnlumens.mediastore.domain.media.dto.request.FinalizeUploadRequest;
import org.earnlumens.mediastore.domain.media.dto.request.InitUploadRequest;
import org.earnlumens.mediastore.domain.media.dto.request.UpdateEntryStatusRequest;
import org.earnlumens.mediastore.domain.media.dto.response.CreateEntryResponse;
import org.earnlumens.mediastore.domain.media.dto.response.FinalizeUploadResponse;
import org.earnlumens.mediastore.domain.media.dto.response.InitUploadResponse;
import org.earnlumens.mediastore.domain.media.model.Asset;
import org.earnlumens.mediastore.domain.media.model.AssetStatus;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.MediaVisibility;
import org.earnlumens.mediastore.domain.media.repository.AssetRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.user.repository.UserRepository;
import org.earnlumens.mediastore.infrastructure.r2.R2PresignedUrlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EntryUploadService}.
 */
class EntryUploadServiceTest {

    private static final String TENANT = "earnlumens";
    private static final String USER_ID = "user-123";
    private static final String OTHER_USER = "user-other";
    private static final String ENTRY_ID = "entry-abc";

    private EntryRepository entryRepository;
    private AssetRepository assetRepository;
    private UserRepository userRepository;
    private R2PresignedUrlService r2PresignedUrlService;
    private EntryUploadService service;

    @BeforeEach
    void setUp() {
        entryRepository = mock(EntryRepository.class);
        assetRepository = mock(AssetRepository.class);
        userRepository = mock(UserRepository.class);
        r2PresignedUrlService = mock(R2PresignedUrlService.class);
        service = new EntryUploadService(entryRepository, assetRepository, userRepository, r2PresignedUrlService);
        when(userRepository.findAllById(any())).thenReturn(java.util.List.of());
    }

    private Entry draftEntry() {
        Entry e = new Entry();
        e.setId(ENTRY_ID);
        e.setTenantId(TENANT);
        e.setUserId(USER_ID);
        e.setTitle("My Video");
        e.setType(EntryType.VIDEO);
        e.setStatus(EntryStatus.DRAFT);
        e.setVisibility(MediaVisibility.PRIVATE);
        return e;
    }

    // ─── createEntry ───────────────────────────────────────────

    @Test
    void createEntry_savesDraftEntry() {
        when(entryRepository.save(any(Entry.class))).thenAnswer(invocation -> {
            Entry e = invocation.getArgument(0);
            e.setId(ENTRY_ID);
            return e;
        });

        CreateEntryRequest request = new CreateEntryRequest(
                "My Video", "A description", "VIDEO", true, new BigDecimal("10.5"));

        CreateEntryResponse response = service.createEntry(TENANT, USER_ID, request);

        assertNotNull(response);
        assertEquals(ENTRY_ID, response.id());
        assertEquals("My Video", response.title());
        assertEquals("VIDEO", response.type());
        assertEquals("DRAFT", response.status());

        verify(entryRepository).save(any(Entry.class));
    }

    @Test
    void createEntry_setsCorrectDefaults() {
        when(entryRepository.save(any(Entry.class))).thenAnswer(invocation -> {
            Entry e = invocation.getArgument(0);
            e.setId(ENTRY_ID);
            // Verify defaults were set before save
            assertEquals(EntryStatus.DRAFT, e.getStatus());
            assertEquals(MediaVisibility.PRIVATE, e.getVisibility());
            assertEquals(TENANT, e.getTenantId());
            assertEquals(USER_ID, e.getUserId());
            return e;
        });

        CreateEntryRequest request = new CreateEntryRequest(
                "Track", null, "AUDIO", false, null);

        service.createEntry(TENANT, USER_ID, request);

        verify(entryRepository).save(any(Entry.class));
    }

    @Test
    void createEntry_invalidType_throwsException() {
        CreateEntryRequest request = new CreateEntryRequest(
                "Bad", null, "INVALID_TYPE", false, null);

        assertThrows(IllegalArgumentException.class,
                () -> service.createEntry(TENANT, USER_ID, request));

        verify(entryRepository, never()).save(any());
    }

    // ─── initUpload ────────────────────────────────────────────

    @Test
    void initUpload_ownerGetsPresignedUrl() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(draftEntry()));
        when(r2PresignedUrlService.generatePresignedPutUrl(anyString(), eq("video/mp4")))
                .thenReturn("https://r2.example.com/presigned");

        InitUploadRequest request = new InitUploadRequest(
                ENTRY_ID, "video.mp4", "video/mp4", "FULL", 1024L);

        Optional<InitUploadResponse> result = service.initUpload(TENANT, USER_ID, request);

        assertTrue(result.isPresent());
        InitUploadResponse resp = result.get();
        assertNotNull(resp.uploadId());
        assertEquals("https://r2.example.com/presigned", resp.presignedUrl());
        assertTrue(resp.r2Key().startsWith("private/media/" + ENTRY_ID + "/full/"));
        assertTrue(resp.r2Key().endsWith("-video.mp4"));
    }

    @Test
    void initUpload_nonOwner_returnsEmpty() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(draftEntry()));

        InitUploadRequest request = new InitUploadRequest(
                ENTRY_ID, "video.mp4", "video/mp4", "FULL", 1024L);

        Optional<InitUploadResponse> result = service.initUpload(TENANT, OTHER_USER, request);

        assertTrue(result.isEmpty());
        verifyNoInteractions(r2PresignedUrlService);
    }

    @Test
    void initUpload_entryNotFound_returnsEmpty() {
        when(entryRepository.findByTenantIdAndId(TENANT, "no-entry"))
                .thenReturn(Optional.empty());

        InitUploadRequest request = new InitUploadRequest(
                "no-entry", "file.pdf", "application/pdf", "FULL", 512L);

        Optional<InitUploadResponse> result = service.initUpload(TENANT, USER_ID, request);

        assertTrue(result.isEmpty());
        verifyNoInteractions(r2PresignedUrlService);
    }

    @Test
    void initUpload_thumbnailKind_usesCorrectPath() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(draftEntry()));
        when(r2PresignedUrlService.generatePresignedPutUrl(anyString(), eq("image/png")))
                .thenReturn("https://r2.example.com/presigned");

        InitUploadRequest request = new InitUploadRequest(
                ENTRY_ID, "thumb.png", "image/png", "THUMBNAIL", 256L);

        Optional<InitUploadResponse> result = service.initUpload(TENANT, USER_ID, request);

        assertTrue(result.isPresent());
        assertTrue(result.get().r2Key().contains("/thumbnail/"));
    }

    // ─── finalizeUpload ────────────────────────────────────────

    @Test
    void finalizeUpload_ownerPersistsAsset() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(draftEntry()));
        when(assetRepository.save(any(Asset.class))).thenAnswer(invocation -> {
            Asset a = invocation.getArgument(0);
            a.setId("asset-001");
            return a;
        });

        FinalizeUploadRequest request = new FinalizeUploadRequest(
                "upload-id", ENTRY_ID, "private/media/entry-abc/full/uuid-video.mp4",
                "video/mp4", "video.mp4", 1024L, "FULL");

        Optional<FinalizeUploadResponse> result = service.finalizeUpload(TENANT, USER_ID, request);

        assertTrue(result.isPresent());
        assertEquals("asset-001", result.get().assetId());
        assertEquals("UPLOADED", result.get().status());
        verify(assetRepository).save(any(Asset.class));
    }

    @Test
    void finalizeUpload_nonOwner_returnsEmpty() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(draftEntry()));

        FinalizeUploadRequest request = new FinalizeUploadRequest(
                "upload-id", ENTRY_ID, "r2key", "video/mp4", "v.mp4", 100L, "FULL");

        Optional<FinalizeUploadResponse> result = service.finalizeUpload(TENANT, OTHER_USER, request);

        assertTrue(result.isEmpty());
        verify(assetRepository, never()).save(any());
    }

    @Test
    void finalizeUpload_setsCorrectAssetFields() {
        Entry entry = draftEntry();
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(entry));
        when(entryRepository.save(any(Entry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(assetRepository.save(any(Asset.class))).thenAnswer(invocation -> {
            Asset a = invocation.getArgument(0);
            a.setId("asset-002");
            assertEquals(TENANT, a.getTenantId());
            assertEquals(ENTRY_ID, a.getEntryId());
            assertEquals("image/jpeg", a.getContentType());
            assertEquals("thumb.jpg", a.getFileName());
            assertEquals(AssetStatus.UPLOADED, a.getStatus());
            return a;
        });

        FinalizeUploadRequest request = new FinalizeUploadRequest(
                "uid", ENTRY_ID, "r2key", "image/jpeg", "thumb.jpg", 50L, "THUMBNAIL");

        service.finalizeUpload(TENANT, USER_ID, request);

        verify(assetRepository).save(any(Asset.class));
    }

    // ─── updateEntryStatus ─────────────────────────────────────

    @Test
    void updateStatus_draftToInReview_succeeds() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(draftEntry()));
        when(entryRepository.save(any(Entry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        boolean result = service.updateEntryStatus(
                TENANT, USER_ID, ENTRY_ID, new UpdateEntryStatusRequest("IN_REVIEW"));

        assertTrue(result);
        verify(entryRepository).save(any(Entry.class));
    }

    @Test
    void updateStatus_draftToPublished_fails() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(draftEntry()));

        boolean result = service.updateEntryStatus(
                TENANT, USER_ID, ENTRY_ID, new UpdateEntryStatusRequest("PUBLISHED"));

        assertFalse(result);
        verify(entryRepository, never()).save(any());
    }

    @Test
    void updateStatus_nonOwner_fails() {
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(draftEntry()));

        boolean result = service.updateEntryStatus(
                TENANT, OTHER_USER, ENTRY_ID, new UpdateEntryStatusRequest("IN_REVIEW"));

        assertFalse(result);
        verify(entryRepository, never()).save(any());
    }

    @Test
    void updateStatus_entryNotFound_fails() {
        when(entryRepository.findByTenantIdAndId(TENANT, "no-entry"))
                .thenReturn(Optional.empty());

        boolean result = service.updateEntryStatus(
                TENANT, USER_ID, "no-entry", new UpdateEntryStatusRequest("IN_REVIEW"));

        assertFalse(result);
    }

    @Test
    void updateStatus_inReviewToApproved_succeeds() {
        Entry inReview = draftEntry();
        inReview.setStatus(EntryStatus.IN_REVIEW);
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(inReview));
        when(entryRepository.save(any(Entry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        boolean result = service.updateEntryStatus(
                TENANT, USER_ID, ENTRY_ID, new UpdateEntryStatusRequest("APPROVED"));

        assertTrue(result);
    }

    @Test
    void updateStatus_publishedToAnything_fails() {
        Entry published = draftEntry();
        published.setStatus(EntryStatus.PUBLISHED);
        when(entryRepository.findByTenantIdAndId(TENANT, ENTRY_ID))
                .thenReturn(Optional.of(published));

        boolean result = service.updateEntryStatus(
                TENANT, USER_ID, ENTRY_ID, new UpdateEntryStatusRequest("DRAFT"));

        assertFalse(result);
        verify(entryRepository, never()).save(any());
    }
}
