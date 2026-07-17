# Original First — Automatic Content Attribution

Automatic attribution system that prioritizes the original creator in revenue and
impressions while preserving maximum freedom: nothing is blocked, no tickets, no
manual moderation. Deterministic, transparent, auditable.

## 1. Content fingerprint

Computed server-side on `finalizeUpload` of the FULL asset and stored in
`Entry.contentFingerprint`:

- SHA-256 over: file size (8-byte big-endian) + three 64 KiB samples (head, middle,
  tail) fetched from R2 with ranged GETs. Files ≤ 192 KiB are hashed whole.
- O(1) cost per file; best-effort — an R2 failure never blocks the upload.

Key classes: `ContentFingerprintService`, `EntryUploadService#finalizeUpload`.
Index: sparse `{tenantId, contentFingerprint}` created by
`OriginalFirstIndexMigration` (auto-index-creation is disabled project-wide).

## 2. Remix detection

On FULL finalize, entries in the same tenant with the same fingerprint (excluding
the uploader's own entries) trigger remix marking: `remix=true`,
`originalEntryId/UserId/AuthorUsername`, `remixDetectedAt`. The canonical entry is
the earliest non-remix entry (fallback: earliest). The post publishes normally and
the UI shows a visible Remix badge plus attribution.

Key method: `OriginalAttributionService#applyRemixDetection`.

## 3. Revenue split — `SplitRole.ORIGINAL`

When a remix entry is purchased, `PaymentService#prepare` resolves the original
entry and adds an ORIGINAL split:

- Royalty percent is set by the original owner on their entry
  (`remixRoyaltyPercent`, 5–50, default 20). It can never be zero.
- Carved from the seller (remixer) pool with priority over the RESELLER carve.
  Buyer price is unchanged. Split order: PLATFORM → TENANT → FRANCHISE →
  ORIGINAL → RESELLER → SELLER.
- Silently skipped if the original's wallet is missing/inactive or the royalty
  doesn't fit the pool — a sale is never blocked.
- `Order.originalCreatorId` snapshots the attribution.

Tests: `PaymentServiceSplitsTest#remixSale_*`.

## 4. Claim as Original

`POST /api/originals/claim/{entryId}` — fully automatic ownership review when the
real creator uploaded after a copycat. Deterministic scoring:

| Signal | Points |
|---|---|
| Upload priority (earlier upload) | 50 |
| Creator trust (share of non-remix entries) | 30 |
| Account seniority (older account) | 20 |

The claimant wins only with a ≥ 15-point margin. On grant, the claimant's entry
becomes canonical, the whole fingerprint group is rebased
(`EntryRepository#rebaseRemixGroup`) and future royalties are redirected. The full
score breakdown is returned to the user and persisted in `original_claims`.

Abuse limits: one claim per user per fingerprint group (unique index) and 5 claims
per user per day. Errors: `ENTRY_NOT_FOUND` / `NO_FINGERPRINT` /
`NO_MATCHING_ENTRY` / `ALREADY_ORIGINAL` (400), `ALREADY_CLAIMED` (409),
`DAILY_CLAIM_LIMIT_REACHED` (429).

Key classes: `OriginalsController`, `OriginalAttributionService#claimAsOriginal`,
`OriginalClaimDocument`, `OriginalClaimRepository`.

## 5. Algorithmic demotion (never a ban)

Accounts whose active entries are ≥ 70 % remixes (minimum 5 entries;
DELETED/REJECTED excluded) get `visibilityDemoted=true` on their remix entries.
The explore feed's default sort becomes `{visibilityDemoted: 1, sortDate: -1}`,
pushing that content down. Search, profiles, purchases, and sales are unaffected.
Recomputed (both directions) on every detection and every claim.

Key method: `OriginalAttributionService#recomputeVisibilityDemotion`.

## 6. Known v1 limitations

- Re-encodes produce different bytes → different fingerprint; the Claim flow is
  the defense. Future: perceptual hashing as an extra signal.
- Collections and text-only RESOURCE entries (no FULL asset) have no fingerprint.
- Watermarks / EXIF / external-platform links are deliberately excluded from the
  automatic score (trivially forgeable); possible future manual evidence.

## 7. Collateral fix

`EntryEntity` was missing accessors for `resellerEnabled` /
`resellerCommissionPercent`, so MapStruct silently dropped both fields on every
entity↔model mapping (creator-chosen reseller commission was lost). Fixed.
