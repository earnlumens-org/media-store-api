# EarnLumens — MongoDB Data-Model & Access-Rules Specification

> **Audience:** Engineering team.
> **Scope:** The commerce core of `media-store-api` — `entries`, `assets`,
> `collections`, `orders`, `entitlements`, `favorites` — plus the franchise
> read-models `franchises` / `franchise_user_bans` (written by admin-api) and
> the two moderation collections designed by the resilience plan
> (`moderation_cases`, `disputes`). Every other collection is inventoried in
> §1.2 with a pointer to its owner.
> **Constraints:** Shared multi-tenant documents (every document carries
> `tenantId`, which is **the tenant's subdomain**, never the Mongo `_id` of
> `tenants`); Stellar payments (XLM native, USD converted at purchase time);
> purchases target entries OR collections; no relational joins — minimize
> round-trips.
> **Companion plan:** [`MYCELIUM-RESILIENCE.md`](../../MYCELIUM-RESILIENCE.md)
> (repo root) holds the findings (H1…H11, N1…N10), decisions (D-01…D-20) and
> implementation order referenced below. This document is the **schema and
> access-rule reference**; the plan is the **change log and rationale**.

---

## 0. How to read this document

### 0.1 Status markers

Every rule, field and index that is not plain current behaviour carries one of
these markers. **Anything unmarked describes the code as it is today**
(verified 2026-10-16 against `media-store-api` `main`).

| Marker | Meaning |
|---|---|
| ✅ | Implemented and verified in code |
| 🔜 **Planned (Hx / Nx / Dx)** | Approved design, not yet in code. The reference points to the finding/decision in `MYCELIUM-RESILIENCE.md`. Will be implemented together with its code; until then **the code behaves as the unmarked text says** |
| ⏸ **Deferred (Dx)** | Exists in the schema or enum but is **intentionally not implemented**. Do not build on it without a new product decision |
| ⚠️ | Known gap between the documented intent and the code (listed in §10). Fixing it is planned work |

### 0.2 Storage conventions

- **Enums** are stored as `String` (the `*Entity` classes map `enum ↔ name()`).
- **Amounts** (`BigDecimal` in Java) are stored as **Decimal128** (Spring
  Boot 4 / Spring Data MongoDB 5 default). Never store money as `double`.
- **Dates** are `LocalDateTime` in Java, stored as BSON `Date` (UTC).
- **Indexes:** `spring.data.mongodb.auto-index-creation` is **disabled**.
  `@CompoundIndex` annotations are documentation only; an index exists in
  Atlas only if an `ApplicationRunner` migration creates it (pattern:
  `PlanOrderIndexMigration`). Each index below says who creates it. The core
  indexes of the six commerce collections are created by `CoreIndexMigration`
  (H9), which — unlike the other migrations — **fails startup** if the
  database already holds an index with the same name/keys but a different
  definition (replacing it is a manual, conscious action). Verified with a
  real mongod in `CoreIndexMigrationIntegrationTest`.
- **Null handling:** Spring Data omits `null` properties when writing. A
  unique compound index still treats a missing field as `null`, so a unique
  index over a field that is `null` for a whole class of documents **collides
  within that class** — this is why the entitlement unique indexes must be
  partial (§6).

---

## 1. MongoDB Collections Overview

### 1.1 Core collections (fully specified here)

| Collection     | Purpose                                    | Cardinality                          | Written by |
|----------------|--------------------------------------------|--------------------------------------|---|
| `entries`      | Content listings (the "product")           | 1 per publishable item               | media-store-api (status also by admin-api moderation) |
| `assets`       | Files stored in R2                         | N per entry (append-only)            | media-store-api |
| `collections`  | Curated groups of entries (album, course…) | 1 per group                          | media-store-api (takedown by admin-api) |
| `orders`       | **All Stellar payments** — purchases, tips, publishing fees | N per user × target (retries, tips) | media-store-api |
| `entitlements` | Granted access rights                      | **1 per user × target** (entry or collection) | media-store-api |
| `favorites`    | User-saved items (entries or collections)  | 1 per user × item                    | media-store-api |
| `franchises`   | Franchise ("beta") storefronts under a tenant | 1 per franchise                   | admin-api (read here) |
| `franchise_user_bans` | Users barred from creating a franchise under a tenant | 1 per tenant × user | admin-api (read here) |
| `moderation_cases` 🔜 (H3/D-15) | One document per moderation action that blocks content | 1 per action | media-store-api / admin-api |
| `disputes` 🔜 (H3/D-09/D-15) | One document per **purchase** affected by a content takedown | 1 per case × entitlement | media-store-api |
| `version_choices` 🔜 (N8/D-14) | A buyer's chosen version of one entry — exists **only** if the buyer ever updated | 1 per user × entry (sparse) | media-store-api |

### 1.2 Other collections (owned elsewhere — listed so nothing is "unknown")

| Collection | Purpose | Spec / owner |
|---|---|---|
| `reports` | User reports on entries (`ReportReason`, `ReportSeverity`, `ReportResolution`, priority score) | `ReportService`; drives the `PUBLISHED → IN_REVIEW` escalation (§2) |
| `moderation_jobs` | AI moderation pipeline jobs for entries **and collections** (`entryType = COLLECTION` is the discriminator) | `ModerationJobService` |
| `transcoding_jobs` | HLS transcoding jobs (unique per `tenantId + assetId`) | `TranscodingJobService` |
| `thumbnail_jobs` | Thumbnail / preview / cover variant jobs | `ThumbnailJobService` |
| `ratings`, `rating_aggregates` | Likes/dislikes with purchase proof (`RatingProofType`) | `RatingService` |
| `reseller_links` | Reseller referral codes per entry (`RESELLER` split) | `RESELLERS-I18N.md` |
| `original_claims` | Original-first ownership claims (`ORIGINAL` split) | `ORIGINAL-FIRST.md` |
| `publishing_blocks`, `publishing_queue_items` | Space publishing queue (priority fee / FastPass) | `PublishingQueueService`, `PublishingIndexMigration` |
| `plan_orders` | Pro-plan purchases (admin-api applies them) | `custom-domain-upgrade.md` |
| `rate_limit_counters` | Distributed rate-limit counters (TTL index) | `MongoDistributedCounter` |
| `moderationConfigs` | Per-tenant moderation thresholds | `ModerationConfigService` |
| `tenants`, `custom_domain_audit`, `ops_leases`, … | Platform governance | admin-api (`DEPLOY.md`, `custom-domain-upgrade.md`) |

---

## 2. `entries`

A publishable content item (video, audio, image, or resource/article).

### Fields

| Field              | Type       | Required | Default      | Notes                                                         |
|--------------------|------------|----------|--------------|---------------------------------------------------------------|
| `_id`              | ObjectId   | auto     |              | Exposed as `String id` in Java                                |
| `tenantId`         | String     | ✔        |              | Tenant subdomain                                              |
| `userId`           | String     | ✔        |              | Creator's OAuth user ID                                       |
| `authorUsername`   | String     |          | `null`       | **Denormalized** from user profile for feed perf. Entity also stores `authorUsernameLower` (hot-feed index) |
| `authorAvatarUrl`  | String     |          | `null`       | **Denormalized** from user profile                            |
| `authorBadge`      | String     |          | `null`       | Creator's active badge key, stamped on publish                |
| `title`            | String     | ✔        |              | Max 200 chars. Entity also stores `titleLower` (search index) |
| `description`      | String     |          | `null`       | Max 2 000 chars                                               |
| `resourceContent`  | String     |          | `null`       | Rich-text body for `RESOURCE` entries. Max 50 000 chars       |
| `type`             | String     | ✔        |              | Enum `EntryType`: `VIDEO \| AUDIO \| IMAGE \| RESOURCE` (`COLLECTION` exists in the enum only as the `moderation_jobs` discriminator — never on an entry) |
| `status`           | String     | ✔        | `DRAFT`      | Enum `EntryStatus` — see state machine below                  |
| `previousStatus`   | String     |          | `null`       | Status before `ARCHIVED` / `DELETED` / `SUSPENDED` — restored on unarchive / restore / reinstate |
| `visibility`       | String     | ✔        | `PUBLIC`     | Enum `MediaVisibility`: `PUBLIC \| PRIVATE`                   |
| `isPaid`           | Boolean    | ✔        | `false`      |                                                               |
| `priceXlm`         | Decimal128 |          | `null`       | `null` or `0` → free                                          |
| `priceUsd`         | Decimal128 |          | `null`       | USD price (converted to XLM at purchase time)                 |
| `priceCurrency`    | String     |          | `null`       | Enum `PriceCurrency`: `XLM \| USD` — currency the creator priced in |
| `sellerWallet`     | String     |          | `null`       | Stellar public key (`G…`). Required when `isPaid = true`      |
| `paymentSplits`    | [Object]   |          | `[]`         | Creator-defined `SELLER` / `COLLABORATOR` splits (§2.1). Platform / tenant / franchise / original / reseller shares are added at payment time (§5.1) |
| `pricingMode`      | String     |          | `INDIVIDUAL` | ⏸ **Deferred (D-17).** Stored, never read. See §2.2           |
| `resellerEnabled`  | Boolean    | ✔        | `true`       | Whether reseller links may attribute sales of this entry      |
| `resellerCommissionPercent` | Decimal128 | ✔ | `10`       | Reseller share, carved out of the seller pool (§5.1)          |
| `tags`             | [String]   |          | `[]`         | Lowercase, max 10 items                                       |
| `spaceIds`         | [String]   |          | `[]`         | Spaces the entry is published into                            |
| `spacePublishedAt` | Map<String, DateTime> |  | `{}`     | Per-space publication timestamp                               |
| `contentLanguage`  | String     |          | `null`       | ISO 639-1 code (`"es"`, `"en"`)                               |
| `thumbnailR2Key`   | String     |          | `null`       | **Denormalized** from the latest THUMBNAIL asset              |
| `previewR2Key`     | String     |          | `null`       | **Denormalized** from the latest PREVIEW asset                |
| `thumbnailVariantsPrefix` | String |        | `null`       | R2 prefix of generated thumbnail variants                     |
| `previewVariantsPrefix`   | String |        | `null`       | R2 prefix of generated preview variants                       |
| `durationSec`      | Integer    |          | `null`       | Denormalized from the FULL asset (video / audio)              |
| `hlsReady`         | Boolean    | ✔        | `false`      | `true` when HLS transcoding completed                         |
| `hlsR2Prefix`      | String     |          | `null`       | R2 key prefix where HLS segments live                         |
| `viewCount`        | Long       | ✔        | `0`          | Total view count                                              |
| `contentFingerprint` | String   |          | `null`       | Server-side fingerprint of the FULL asset (Original First)    |
| `remix`            | Boolean    | ✔        | `false`      | Detected as a remix of an existing original                   |
| `originalEntryId`  | String     |          | `null`       | Original entry (when `remix = true`)                          |
| `originalUserId`   | String     |          | `null`       | Original creator                                              |
| `originalAuthorUsername` | String |        | `null`       | Denormalized for display                                      |
| `remixDetectedAt`  | DateTime   |          | `null`       |                                                               |
| `remixRoyaltyPercent` | Decimal128 | ✔     | `20`         | Royalty the original creator receives on sales of **this** entry's remixes (read live from the original at payment time, §5.1) |
| `visibilityDemoted` | Boolean   | ✔        | `false`      | Demoted in feeds (Original First)                             |
| `moderationFeedback` | String   |          | `null`       | Last rejection / suspension justification shown to the creator |
| `statusHistory`    | [Object]   |          | `[]`         | `StatusChangeRecord { fromStatus, toStatus, actor, reason, timestamp }` — audit trail of status transitions |
| `createdAt`        | DateTime   | auto     |              | `@CreatedDate`                                                |
| `updatedAt`        | DateTime   | auto     |              | `@LastModifiedDate`                                           |
| `publishedAt`      | DateTime   |          | `null`       | Set when status → `PUBLISHED`                                 |
| `moderationBlock`  | Object     |          | `null`       | 🔜 **Planned (H3 / S-03).** `{ caseId, reason, by, at }`. Non-null ⇒ blocked for **everyone** (§8.8). Set only by explicit moderation actions, cleared on reinstatement |
| `currentVersion`   | Integer    |          | `1`          | 🔜 **Planned (N8 / D-14).** Absent ⇒ `1` (S-01: no backfill)   |
| `versions[]`       | [Object]   |          | `[]`         | 🔜 **Planned (N8 / D-14).** One entry per **approved** version (§2.3). Absent ⇒ the entry's current fields are version 1 |
| `pendingVersion`   | Object     |          | `null`       | 🔜 **Planned (N9).** Single staging slot for an edit awaiting moderation (§2.3). The live fields are never touched until approval |

#### 2.1 `paymentSplits[]` sub-document

| Field     | Type       | Required | Notes                                                      |
|-----------|------------|----------|------------------------------------------------------------|
| `wallet`  | String     | ✔        | Stellar public key (`G…`)                                  |
| `role`    | String     | ✔        | Enum `SplitRole`: `PLATFORM \| TENANT \| SELLER \| COLLABORATOR \| FRANCHISE \| RESELLER \| ORIGINAL` |
| `percent` | Decimal128 | ✔        | Percentage of total (e.g. `10.00` = 10%). Sum must = 100   |

> On an **entry or collection**, `paymentSplits` holds only the creator-side
> roles (`SELLER`, optionally `COLLABORATOR`). The full split actually paid is
> computed at `prepare` time by `PaymentService.buildFullSplits` (§5.1) and
> **snapshotted on the order** — the order's `paymentSplits` is the only
> authoritative record of who was paid what.

#### 2.2 `pricingMode` — ⏸ Deferred (D-17)

`PricingMode { INDIVIDUAL, COLLECTION_ONLY, BOTH }` exists as an enum and as a
stored field, but **no service reads or writes it, nothing enforces it and the
UI does not expose it**. Every paid entry is purchasable individually today.

> **Decision D-17 (2026-10-19):** keep the field as is, do **not** implement
> enforcement, "Available in…" pages or Studio toggles until there is a real
> product need. If reactivated, the minimum is a check in
> `PaymentService.prepare` rejecting `COLLECTION_ONLY` entries plus a Studio
> toggle; the query of §9.9 already exists (`getCollectionsContainingEntry`).

#### 2.3 Versions — 🔜 Planned (N8 / N9 / D-14)

```jsonc
// versions[] element — written ONLY when a version is approved
{ "version": 2, "assetId": "…", "hlsR2Prefix": "…",
  "title": "…", "description": "…", "thumbnailR2Key": "…", "previewR2Key": "…",
  "resourceContent": "…", "approvedAt": "…",
  "changeSummary": { "parts": ["FILE","TITLE"], "fileSizeBytes": 123, "durationSec": 45 },
  "creatorNote": "…" }

// pendingVersion — same shape minus approvedAt, plus submittedAt + moderationJobId
```

Rules (D-14, S-01, S-02, N9):

- **Metadata only.** A version points to `Asset` documents that already exist
  (assets are append-only, §3). No file is ever copied.
- **The entry's denormalized fields are the projection of `currentVersion`.**
  Approving a pending version = append it to `versions[]` as
  `currentVersion + 1`, project its fields onto the entry, clear the slot.
- **"Absent = v1".** An entry without `versions[]` *is* version 1, built from
  its current fields. `versions[]` is first written when the **second**
  version is approved (and then includes the reconstructed v1). No backfill.
- **One pending slot.** A newer edit overwrites `pendingVersion` and cancels
  the previous moderation job. Edits on `DRAFT` / `REJECTED` entries (never
  sold) keep writing the live document directly, as today.
- **Rejecting a pending version changes nothing visible**: the slot is
  cleared, the justification goes to `moderationFeedback`, `status` is
  untouched. This is what makes D-13 implementable (§8.8).
- **Retention is infinite by default** (S-02): no job deletes version files.
  A reference-based GC is an optional P2 item, never part of the core design.

### Status State Machine (as implemented)

```
                 creator submits          AI / moderator
DRAFT ─────────────────────→ IN_REVIEW ──────────────→ APPROVED ──creator──→ PUBLISHED ⇄ UNLISTED
  ↑                            │   ↑                      │                      │
  │        creator re-edits    ↓   │ edit / report        │ edit                 │ admin
  ←──────────────────────── REJECTED                      └──────────────────────┤
  ↑                                                                              ↓
  │    reinstate (→ previousStatus) / to draft                              SUSPENDED
  └──────────────────────────────────────────────────────────────────────────────┘

ARCHIVED  ←── creator, from ANY status ──→ unarchive restores previousStatus (fallback DRAFT)
DELETED   ←── creator, from ANY status ──→ restore   restores previousStatus (fallback DRAFT)
```

| Transition                      | Actor / trigger                          | Side-effects                                                        |
|---------------------------------|------------------------------------------|---------------------------------------------------------------------|
| `DRAFT → IN_REVIEW`             | Creator submits                          | Creates a `moderation_jobs` document; blocked while transcoding is in progress; burst limit on concurrent reviews |
| `IN_REVIEW → APPROVED`          | AI moderation (`ModerationJobService`) or admin-api moderator | Post-approval media pipeline (thumbnail variants, HLS transcode for VIDEO) |
| `IN_REVIEW → REJECTED`          | AI moderation or admin-api moderator     | `moderationFeedback` = justification. ⚠️ Today this also happens for **re-reviews of an already-sold entry** (edit or report) — see §8.8 and N9 |
| `APPROVED → PUBLISHED`          | Creator publishes                        | `publishedAt = now()`, `authorBadge` stamped                        |
| `APPROVED → IN_REVIEW`          | Creator edits before publishing          | New moderation job                                                  |
| `REJECTED → DRAFT \| IN_REVIEW` | Creator re-edits / resubmits             |                                                                     |
| `PUBLISHED → UNLISTED`          | Creator unlists                          | Reachable by direct link, hidden from feeds                         |
| `UNLISTED → PUBLISHED`          | Creator re-lists                         |                                                                     |
| `PUBLISHED → IN_REVIEW`         | Creator edits metadata / thumbnail / preview (`EntryUploadService`), **or** report escalation (`ReportService.escalateToReview`) | New moderation job; the previous active job is cancelled. ⚠️ Edits are applied to the **live document** before review — 🔜 N9 moves them to `pendingVersion` and the entry **stays `PUBLISHED`** |
| `PUBLISHED \| APPROVED → SUSPENDED` | **admin-api** moderator (`ModerationService.suspendEntry`, justification required) | Saves `previousStatus`, `moderationFeedback`. 🔜 H3: also sets `moderationBlock` and opens disputes (§8.8) |
| `SUSPENDED → PUBLISHED \| DRAFT` | Moderator reinstates                    | 🔜 H3: clears `moderationBlock`; access returns automatically       |
| `Any → ARCHIVED`                | Creator                                  | Saves `previousStatus`. Purchasers keep access (§8)                 |
| `Any → DELETED`                 | Creator (soft delete)                    | Saves `previousStatus`. Purchasers keep access (§8). No hard delete of entries exists outside `DraftCleanupService` (DRAFT without assets) |
| `ARCHIVED \| DELETED → previousStatus` | Creator unarchives / restores     | Falls back to `DRAFT` if `previousStatus` is null                   |

> There is **no `PUBLISHED → DRAFT` ("unpublish") transition**. To hide a
> published entry the creator uses `UNLISTED` (direct link still works) or
> `ARCHIVED` / `DELETED` (hidden; purchasers keep access).

### Indexes

| Index | Definition | Created by |
|---|---|---|
| `idx_tenant_id` | `{tenantId:1, _id:1}` unique | ✅ `CoreIndexMigration` (H9) |
| `idx_tenant_user` | `{tenantId:1, userId:1}` | ✅ `CoreIndexMigration` |
| `idx_tenant_status_published` | `{tenantId:1, status:1, publishedAt:-1}` | ✅ `CoreIndexMigration` |
| `idx_tenant_status_type_published` | `{tenantId:1, status:1, type:1, publishedAt:-1}` | ✅ `CoreIndexMigration` |
| `idx_tenant_space_status_published` | `{tenantId:1, spaceIds:1, status:1, publishedAt:-1}` | ✅ `CoreIndexMigration` |
| `idx_tenant_status_badge_published` | `{tenantId:1, status:1, authorBadge:1, publishedAt:-1}` | ✅ `HotFeedIndexMigration` |
| `idx_tenant_status_authorlower_published` | `{tenantId:1, status:1, authorUsernameLower:1, publishedAt:-1}` | ✅ `HotFeedIndexMigration` |
| `idx_tenant_status_titlelower` | `{tenantId:1, status:1, titleLower:1}` | ✅ `SearchTextIndexMigration` |
| `idx_text_search` | text index on `title` (10), `tags` (8), `authorUsername` (5), `description` (1), prefixed by `tenantId, status` | ✅ `SearchTextIndexMigration` |
| `idx_tenant_fingerprint` | `{tenantId:1, contentFingerprint:1}` sparse | ✅ `OriginalFirstIndexMigration` |

> The first five indexes are what an earlier revision of this document called
> "existing — keep" although no code created them. Atlas was measured on
> 2026-10-19 (R-28, plan §10.0): **none of them existed** — only the
> `_id_` index and the ones created by the other migrations. H9 added
> `CoreIndexMigration`; the first deploy that runs it creates them.

---

## 3. `assets`

An individual file (thumbnail, preview, or full content) stored in Cloudflare
R2. **Assets are append-only**: every upload creates a new document with a
fresh `r2Key`; nothing deletes or overwrites a previous asset of the same
kind (this is what makes versions free of file copies, §2.3).

### Fields

| Field           | Type     | Required | Default    | Notes                                  |
|-----------------|----------|----------|------------|----------------------------------------|
| `_id`           | ObjectId | auto     |            |                                        |
| `tenantId`      | String   | ✔        |            |                                        |
| `entryId`       | String   | ✔        |            | Parent entry reference                 |
| `r2Key`         | String   | ✔        |            | `{public\|private}/media/{entryId}/{kind}/{uuid}-{fileName}` — unique per tenant. `THUMBNAIL`/`PREVIEW` live under `public/`, `FULL` under `private/` |
| `contentType`   | String   | ✔        |            | MIME type (`video/mp4`, `image/webp`)  |
| `fileName`      | String   | ✔        |            | Sanitized upload filename              |
| `fileSizeBytes` | Long     |          | `null`     |                                        |
| `kind`          | String   | ✔        |            | Enum `MediaKind`: `THUMBNAIL \| PREVIEW \| FULL` |
| `status`        | String   | ✔        |            | Enum `AssetStatus`: `UPLOADED \| PROCESSING \| READY \| FAILED` |
| `widthPx`, `heightPx` | Integer |     | `null`     | Client-reported dimensions             |
| `durationSec`   | Integer  |          | `null`     | Client-reported duration               |
| `codecVideo`, `codecAudio` | String |  | `null`     |                                        |
| `bitrateBps`    | Long     |          | `null`     |                                        |
| `scannedAt`     | DateTime |          | `null`     | Malware scan timestamp (moderation worker) |
| `createdAt`     | DateTime | auto     |            | `@CreatedDate`                         |

### Status State Machine

```
UPLOADED ──→ PROCESSING ──→ READY
                  │
                  ↓
                FAILED
```

| Transition             | Trigger                | Notes                             |
|------------------------|------------------------|-----------------------------------|
| finalize → `UPLOADED`  | `FULL` asset of a `VIDEO` entry | Needs HLS transcoding — which is **deferred until moderation approval** (`ModerationJobService.createTranscodingJobForEntry`) |
| finalize → `READY`     | Every other asset (`THUMBNAIL`, `PREVIEW`, non-video `FULL`) | Immediately usable |
| `UPLOADED → PROCESSING → READY` | Transcoding job     | Sets `entry.hlsReady` / `hlsR2Prefix` on completion |
| `PROCESSING → FAILED`  | Job errors             |                                   |

### Indexes

| Index | Definition | Created by |
|---|---|---|
| `idx_tenant_entry_kind` | `{tenantId:1, entryId:1, kind:1}` | ✅ `CoreIndexMigration` (H9) |
| `idx_tenant_r2key` | `{tenantId:1, r2Key:1}` unique | ✅ `CoreIndexMigration` (H9) |

### Relationship to Entry

- Entry **references** Assets by `entryId` (Assets store the FK).
- Entry **denormalizes** `thumbnailR2Key` and `previewR2Key` on finalize of a
  THUMBNAIL / PREVIEW upload (not on READY — they are READY immediately).
- **Resolving the FULL asset.** ⚠️ Today six call-sites
  (`MediaEntitlementService`, `PublicEntryService`, `ModerationJobService`,
  `TranscodingJobService` ×2, `EntryUploadService`) use a Spring Data
  *derived query* returning `Optional` for `(entryId, kind=FULL, status=READY)`.
  Because assets are append-only, a second `FULL` upload on a published entry
  makes that query **throw** (`IncorrectResultSizeDataAccessException` ⇒ 5xx
  for everyone, including buyers). 🔜 **H8** replaces the repository method
  with "latest READY FULL by `createdAt`" and, with N8, resolves by version
  pointer (`versions[n].assetId`); the "latest" query remains only as the
  fallback for entries without `versions[]`.
- 🔜 **H8 gate:** `initUpload`/`finalizeUpload` with `kind = FULL` on an entry
  that is not `DRAFT` / `REJECTED` is only accepted through the versioned
  path (N9 `pendingVersion`), otherwise **409**. Today the API accepts it
  and does **not** re-moderate (only THUMBNAIL/PREVIEW changes trigger
  re-review) — a moderation bypass closed by H8.
- Worker flow (unchanged):
  ```
  Worker →  GET /api/media/entitlements/{entryId}
  Backend → ContentAccessPolicy (§8.3) → Asset for the resolved version → r2Key (+ hlsPrefix)
  ```

---

## 4. `collections`

A curated group of entries (series, course, album, bundle, etc.). Can be free
or paid. **Collections are moderated** like entries (publishing sends them to
`IN_REVIEW`). Purchasing a paid collection grants access under the **union
model** (§8.6, D-11 / D-20).

### Fields

| Field              | Type       | Required | Default      | Notes                                                    |
|--------------------|------------|----------|--------------|----------------------------------------------------------|
| `_id`              | ObjectId   | auto     |              |                                                          |
| `tenantId`         | String     | ✔        |              |                                                          |
| `userId`           | String     | ✔        |              | Creator / curator                                        |
| `authorUsername`   | String     |          | `null`       | **Denormalized**; entity also stores `authorUsernameLower` |
| `authorAvatarUrl`  | String     |          | `null`       | **Denormalized**                                         |
| `authorBadge`      | String     |          | `null`       | Stamped on publish                                       |
| `title`            | String     | ✔        |              | Max 200 chars                                            |
| `description`      | String     |          | `null`       | Max 2 000 chars                                          |
| `collectionType`   | String     | ✔        |              | Enum `CollectionType`: `SERIES \| COURSE \| ALBUM \| BUNDLE \| LIST \| LIBRARY \| CATALOG \| VOLUME \| ARCHIVE` |
| `coverR2Key`       | String     |          | `null`       | R2 key for cover image                                   |
| `coverVariantsPrefix` | String  |          | `null`       | R2 prefix of generated cover variants                    |
| `status`           | String     | ✔        | `DRAFT`      | Enum `CollectionStatus`: `DRAFT \| IN_REVIEW \| PUBLISHED \| REJECTED \| ARCHIVED \| DELETED` |
| `visibility`       | String     | ✔        | `PUBLIC`     | Enum `MediaVisibility`                                   |
| `isPaid`           | Boolean    | ✔        | `false`      | Whether the collection requires purchase                 |
| `priceXlm`         | Decimal128 |          | `null`       | `null`/`0` → free                                        |
| `priceUsd`         | Decimal128 |          | `null`       | USD price (converted to XLM at purchase time)            |
| `priceCurrency`    | String     |          | `null`       | Enum `PriceCurrency`                                     |
| `sellerWallet`     | String     |          | `null`       | Required when `isPaid = true` (validated on publish)     |
| `paymentSplits`    | [Object]   |          | `[]`         | Same semantics as Entry (§2.1)                           |
| `items`            | [Object]   |          | `[]`         | Embedded array, **max 1 000 active items** (`Collection.MAX_ITEMS`). 🔜 H2: removed items **stay** in the array with `removedAt` (membership history, §4.1) |
| `contentLanguage`  | String     |          | `null`       | ISO 639-1                                                |
| `moderationFeedback` | String   |          | `null`       | Last rejection / takedown justification                  |
| `createdAt`        | DateTime   | auto     |              |                                                          |
| `updatedAt`        | DateTime   | auto     |              |                                                          |
| `publishedAt`      | DateTime   |          | `null`       | Set when status → `PUBLISHED`                            |
| `moderationBlock`  | Object     |          | `null`       | 🔜 **Planned (H3 / S-03).** Same semantics as on Entry   |
| `pendingVersion`   | Object     |          | `null`       | 🔜 **Planned (N9).** Staged edit (title, description, cover, items) awaiting moderation; `changeSummary` holds `added[]` / `removed[]` entry ids (D-14) |

> ⚠️ admin-api's `CollectionAdminDocument` writes a `previousStatus` field on
> collection takedown that the media-store-api model does not read. Harmless
> today; 🔜 H3 replaces the takedown-by-`ARCHIVED` workaround with
> `moderationBlock`, after which the field can be dropped.

#### 4.1 `items[]` sub-document — membership history

| Field      | Type     | Required | Notes                        |
|------------|----------|----------|------------------------------|
| `entryId`  | String   | ✔        | Reference to `entries._id`   |
| `position` | Integer  | ✔ (active) | 0-based sort order. 🔜 `null` once removed |
| `addedAt`  | DateTime |          | 🔜 **Planned (H2 / D-20).** When the entry entered the collection. **Absent (historical data) = "since forever"** — no backfill |
| `removedAt`| DateTime |          | 🔜 **Planned (H2 / D-20).** Set when the creator removes the item; the record is **kept**. Re-adding appends a **new** record |

> ⚠️ Today `addItem` / `removeItem` / `reorderItems` overwrite `items[]` with
> no history. 🔜 With H2 they become append / mark / reorder-active-only.
>
> **Why history instead of a per-buyer snapshot (S-04, rev. 10).** A snapshot
> of the items at purchase time cannot express D-20: an entry **added after
> the purchase and removed later** is in neither the snapshot nor the current
> list. The membership history, compared with the entitlement's `grantedAt`,
> covers every case with one rule (§8.6) and needs no new field on the
> entitlement, no new index, no migration and no write to entitlements when
> the creator edits the collection.

**Public listing** = records with `removedAt == null`, sorted by `position`.
**Buyer listing** (§9.8) = those plus the removed records the buyer still has
a right to, flagged "no longer in this collection".

### Status State Machine (as implemented)

```
DRAFT ──publish──→ IN_REVIEW ──→ PUBLISHED
  ↑                   │
  │                   ↓
  ←──────────────── REJECTED ──(hard delete allowed)──→ ∅
  ↑
  ├── ARCHIVED  (creator archive, from any status; admin-api takedown also uses ARCHIVED today)
  └── DELETED   (creator soft delete)       — unarchive / restore → DRAFT
```

| Transition | Actor | Notes |
|---|---|---|
| `DRAFT \| ARCHIVED \| DELETED \| REJECTED → IN_REVIEW` | Creator publishes | Requires `sellerWallet` + valid price when paid, and **every item `PUBLISHED`**; creates a `moderation_jobs` document with `entryType = COLLECTION` |
| `IN_REVIEW → PUBLISHED \| REJECTED` | AI moderation / moderator | `publishedAt` set on `PUBLISHED` |
| `Any → ARCHIVED` | Creator — **or admin-api takedown** (`ModerationService.archiveCollection`, justification required) | ⚠️ Today a takedown is indistinguishable from a creator archive at the data level. 🔜 H3: takedown sets `moderationBlock` instead |
| `Any → DELETED` | Creator (soft delete) | |
| `ARCHIVED \| DELETED → DRAFT` | Creator unarchives / restores | Must be re-published (re-moderated) |
| `DRAFT \| REJECTED → ∅` | Creator hard-deletes | ⚠️ Allowed even when entitlements reference the collection ⇒ irreversible loss. 🔜 **H2**: 409 when `entitlements.count(collectionId) > 0` |
| `addItem` / `removeItem` / `reorderItems` | Creator | ⚠️ Overwrite `items[]` with no history. 🔜 H2: `addItem` appends `{entryId, position, addedAt}`; `removeItem` sets `removedAt` (+ `position = null`) and keeps the record; `reorderItems` touches active records only. 🔜 N9/D-14: on a published collection the change is staged in `pendingVersion` with `added[]`/`removed[]` and applied on approval |

### Indexes

| Index | Definition | Created by |
|---|---|---|
| `idx_coll_tenant_id` | `{tenantId:1, _id:1}` unique | ✅ `CoreIndexMigration` (H9) |
| `idx_coll_tenant_user` | `{tenantId:1, userId:1}` | ✅ `CoreIndexMigration` |
| `idx_coll_tenant_status_published` | `{tenantId:1, status:1, publishedAt:-1}` | ✅ `CoreIndexMigration` |
| `idx_coll_tenant_items_entry` | `{tenantId:1, 'items.entryId':1}` | ✅ `CoreIndexMigration` ("collections containing entry X") |
| `idx_coll_tenant_status_badge_published` | `{tenantId:1, status:1, authorBadge:1, publishedAt:-1}` | ✅ `HotFeedIndexMigration` |
| `idx_coll_tenant_status_authorlower_published` | `{tenantId:1, status:1, authorUsernameLower:1, publishedAt:-1}` | ✅ `HotFeedIndexMigration` |
| `idx_coll_text_search` | text index on `title` (10), `authorUsername` (5), `description` (1) | ✅ `SearchTextIndexMigration` |

---

## 5. `orders`

**The Stellar payment ledger.** One document per payment attempt, for five
kinds of payment (`targetType`). Only `ENTRY` and `COLLECTION` orders grant an
entitlement; `TIP`, `PUBLISH_FEE` and `PUBLISH_FAST_PASS` orders flow through
the same audited pipeline (prepare → sign → submit → confirm → reconcile) but
unlock nothing. A user may legitimately have **several** orders for the same
target (expired / failed attempts, repeated tips) — orders are **not** unique
per target.

### Fields

| Field              | Type       | Required | Default     | Notes                                                        |
|--------------------|------------|----------|-------------|--------------------------------------------------------------|
| `_id`              | ObjectId   | auto     |             |                                                              |
| `tenantId`         | String     | ✔        |             |                                                              |
| `userId`           | String     | ✔        |             | Buyer's user ID                                              |
| `targetType`       | String     | ✔        |             | Enum `TargetType`: `ENTRY \| COLLECTION \| TIP \| PUBLISH_FEE \| PUBLISH_FAST_PASS` |
| `entryId`          | String     |          | `null`      | FK to `entries._id`. Set for `ENTRY` purchases and **entry tips** (context only) |
| `collectionId`     | String     |          | `null`      | FK to `collections._id`. Set for `COLLECTION` purchases and **collection tips** |
| `sellerId`         | String     | ✔        |             | Creator's user ID (denormalized)                             |
| `franchiseId`      | String     |          | `null`      | FK to `franchises._id` when the sale/tip came through `/f/<slug>` |
| `resellerId`       | String     |          | `null`      | Reseller user attributed via `?r=<code>` (`reseller_links`)  |
| `originalCreatorId`| String     |          | `null`      | Original creator paid a royalty when the entry is a remix    |
| `amountXlm`        | Decimal128 | ✔        |             | Final XLM amount charged                                     |
| `originalAmountUsd`| Decimal128 |          | `null`      | Original USD amount (USD-priced items and all tips)          |
| `xlmUsdRate`       | Decimal128 |          | `null`      | XLM/USD rate used for conversion                             |
| `priceCurrency`    | String     |          | `null`      | `"XLM"` or `"USD"` (stored as plain String, not the enum)    |
| `status`           | String     | ✔        | `PENDING`   | Enum `OrderStatus`: `PENDING \| PROCESSING \| COMPLETED \| FAILED \| EXPIRED \| REFUNDED` |
| `stellarTxHash`    | String     | ✔        |             | **Computed at `prepare`** (hash of the unsigned envelope) — the order is persisted with its definitive hash before the user signs |
| `buyerWallet`      | String     | ✔        |             | Buyer's Stellar public key (`G…`)                            |
| `memo`             | String     | ✔        |             | `"TOTAL: x XLM"` / `"TIP: x XLM"` — carries no order reference (D-03: on-chain format is frozen) |
| `unsignedXdr`      | String     | ✔        |             | Base-64 XDR of unsigned tx envelope                          |
| `signedXdr`        | String     |          | `null`      | Base-64 XDR of signed tx envelope (set on submit)            |
| `integrityHash`    | String     | ✔        |             | SHA-256 hex of unsigned XDR — tamper detection               |
| `paymentSplits`    | [Object]   | ✔        |             | **Snapshot of the full split** actually paid (§5.1). Authoritative; never recomputed |
| `publishQueueItemId`, `publishSpaceId`, `publishEntityType`, `publishEntityId` | String | | `null` | Publishing-order context (`PUBLISH_FEE` / `PUBLISH_FAST_PASS`) |
| `createdAt`        | DateTime   | auto     |             |                                                              |
| `completedAt`      | DateTime   |          | `null`      | Set when status → `COMPLETED`                                |
| `expiresAt`        | DateTime   | ✔        |             | Tx timeBounds maxTime                                        |
| `ledgerPreparedAt`, `ledgerCompletedAt`, `ledgerAttempts`, `ledgerNextAttemptAt`, `ledgerLastError` | — | | | 🔜 **Planned (N2 / D-12).** Outbox marks of the external rights ledger; "pending" = mark absent. Sparse index via migration |

### Status State Machine

```
PENDING ──→ PROCESSING ──→ COMPLETED ──→ REFUNDED
   │             │
   ↓             ↓
 EXPIRED       FAILED ──→ EXPIRED
```

| Transition              | Trigger                    | Side-effects                                          |
|-------------------------|----------------------------|-------------------------------------------------------|
| `prepare` → `PENDING`  | Buyer requests a payment   | Order saved **with `stellarTxHash`**, `paymentSplits` snapshot, `expiresAt`. Existing orders for the same buyer × target × **`targetType`** are reconciled first (§5.3) |
| `PENDING → PROCESSING` | Buyer signed; atomic CAS (`lockAndVerify`) | `signedXdr` stored after strict XDR verification + anti-replay by hash |
| `PROCESSING → COMPLETED` | On-chain confirmation (async by default) | `completedAt`; then `createEntitlement` (§6) / tip no-op / publishing effect. Watchdog repairs crashes between the two |
| `PENDING → EXPIRED`    | `expiresAt` reached        | Recycled at next `prepare`                             |
| `PROCESSING \| FAILED → EXPIRED` | Reconciled against Horizon: tx window closed, not on chain | A new purchase may proceed |
| `PROCESSING → FAILED`  | Horizon rejected submission | Reconciled later; a late-landing tx is promoted to `COMPLETED` |
| `COMPLETED → REFUNDED` | **Admin action** (manual; not implemented as an endpoint) | Compensating payment is off-platform (Stellar cannot reverse). Revokes the entitlement (§6). Design preserved by D-09 |

### Indexes

| Index | Definition | Created by / note |
|---|---|---|
| `idx_order_tenant_user_target_entry` | `{tenantId:1, userId:1, targetType:1, entryId:1}` **non-unique** | ✅ `CoreIndexMigration` (H9). Was annotated **unique** before H9, which contradicted the retry flow, repeatable tips and the `null` collision (§0.2); the annotation now matches |
| `idx_order_tenant_user_target_coll` | `{tenantId:1, userId:1, targetType:1, collectionId:1}` **non-unique** | ✅ `CoreIndexMigration` (H9), same reasoning |
| `idx_order_tenant_entry_status` | `{tenantId:1, entryId:1, status:1}` | ✅ `CoreIndexMigration` |
| `idx_order_tenant_coll_status` | `{tenantId:1, collectionId:1, status:1}` | ✅ `CoreIndexMigration` |
| `idx_order_tenant_user_status_created` | `{tenantId:1, userId:1, status:1, createdAt:-1}` | ✅ `CoreIndexMigration` |
| `idx_order_tenant_seller_status_created` | `{tenantId:1, sellerId:1, status:1, createdAt:-1}` | ✅ `CoreIndexMigration` |
| `idx_order_tenant_franchise_status_created` | `{tenantId:1, franchiseId:1, status:1, createdAt:-1}` | ✅ `CoreIndexMigration` |
| `idx_order_stellar_tx` | `{stellarTxHash:1}` | ✅ `CoreIndexMigration` (hash is always present; no need for sparse) |
| `idx_order_status_expires` | `{status:1, expiresAt:1}` | ✅ `CoreIndexMigration` |

> Purchase idempotency is **not** provided by a unique index: it is provided
> by the CAS transitions on `status` and by `processExistingOrders` (§5.3).

### 5.1 Split resolution (`PaymentService.buildFullSplits`)

The order's `paymentSplits` is built at `prepare` time from the following
pools, in this order. Every percentage is of the **total**; the last split
receives the exact remainder so the sum is always `100.00`.

| # | Role | Source | Rule |
|---|---|---|---|
| 1 | `PLATFORM` | `TenantReadModel.platformFeePercent` (fallback `PlatformConfig`) | Always present |
| 2 | `TENANT` | `tenantFeePercent` + `tenantWallet` of the tenant | Only if `tenantFeePercent > 0`. ⚠️ If the wallet is missing the sale **proceeds without it** (fail-open, logged) — 🔜 **H7** makes this fail-closed (`TENANT_PAYOUT_UNCONFIGURED`, 4xx, no order) |
| 3 | `FRANCHISE` | `franchises.commissionPercent` (frozen) | `franchisePercent = tenantPercent × commissionPercent / 100`, **carved out of the `TENANT` share** (§5.2). Final price unchanged |
| 4 | `ORIGINAL` | `original.remixRoyaltyPercent` (read live from the original entry; default 20) | Only when the entry is a remix. Carved out of the seller pool; **skipped** (sale proceeds) if it would exceed the pool or the original cannot be resolved (D-05) |
| 5 | `RESELLER` | `entry.resellerCommissionPercent` (default 10) + `reseller_links` | Only for entry purchases with a valid `?r=` code, `resellerEnabled`, non-self referral and an active wallet. Carved out of the remaining seller pool; **skipped** on any failure (D-05) |
| 6 | `SELLER` / `COLLABORATOR` | Entry's or collection's own `paymentSplits` | Rescaled into `100 − platform − tenant − original − reseller` |

Before signing, every destination wallet is re-validated as an active Stellar
account (`SPLIT_WALLET_NOT_ACTIVE`, 400 — fail-closed). Horizon outages are
fail-open on that check by explicit decision (D-06): a dead destination then
fails on-chain and the order ends `FAILED` without charging.

**Tips** (`prepareTip`): target split = `SELLER 100 %` of the creator's tip
wallet, then the same `buildFullSplits` (platform + tenant + franchise).
Range `0.25 ≤ USD ≤ 100`, memo `"TIP: x XLM"`, never an entitlement.

**Publishing orders** (`buildPublishingSplits`): `PLATFORM + TENANT`, or
`PLATFORM 100 %` when the tenant has no wallet. ⚠️ Same fail-open as row 2 —
🔜 H7 applies the same fail-closed rule.

### 5.2 Franchise split rule

When `franchiseId` is set, the franchise commission (a frozen percentage of the
franchisor's own profit, snapshotted on the `franchises` doc) is taken **out of
the `TENANT` split only**:

```
franchisePercent      = tenantPercent × franchise.commissionPercent / 100
effectiveTenantPercent = tenantPercent − franchisePercent
```

The `PLATFORM` and `SELLER`/`COLLABORATOR` splits are untouched, so the final
price is identical whether the buyer purchases on the tenant storefront or on a
franchise storefront. A franchise therefore only earns when the tenant has a
non-zero `tenantFeePercent`. A purchase that references an unknown or DISABLED
franchise is rejected; a franchise owner cannot buy through their own
franchise (`FRANCHISE_SELF_PURCHASE`).

### 5.3 Existing-order reconciliation at `prepare`

Before creating a new order, `prepare` loads the buyer's previous orders for
the same target and handles each by status: `COMPLETED` ⇒ self-heal a missing
entitlement, then reject "already purchased"; live `PENDING` ⇒ **reuse** (same
XDR); `PROCESSING`/`FAILED` ⇒ reconcile against Horizon (complete, block as
in-flight, or expire); `EXPIRED`/`REFUNDED` ⇒ ignore.

> ⚠️ **H10 — the lookup does not filter by `targetType`.** A `TIP` order on
> the same entry/collection is therefore treated as a purchase: a completed
> tip blocks the purchase ("already purchased" with nothing purchased) and an
> abandoned pending tip is returned as the reusable order (the buyer signs a
> tip believing it is a purchase). 🔜 H10 adds `targetType` to the two
> repository queries. The pseudocode in §9.11/§9.12 is the intended
> behaviour.

---

## 6. `entitlements`

Access right connecting a user to a target (entry or collection). **Exactly
one document per user × target**, regardless of how many orders exist.

### Fields

| Field          | Type     | Required | Default  | Notes                                                        |
|----------------|----------|----------|----------|--------------------------------------------------------------|
| `_id`          | ObjectId | auto     |          |                                                              |
| `tenantId`     | String   | ✔        |          |                                                              |
| `userId`       | String   | ✔        |          | Buyer's OAuth user ID (see N1 in the plan: this is an X/Twitter id) |
| `targetType`   | String   | ✔        |          | `ENTRY \| COLLECTION` (the other `TargetType` values never produce entitlements) |
| `entryId`      | String   |          | `null`   | FK to `entries._id`. Set when `targetType = ENTRY`           |
| `collectionId` | String   |          | `null`   | FK to `collections._id`. Set when `targetType = COLLECTION`  |
| `franchiseId`  | String   |          | `null`   | Copied from the granting order. **Analytics only** — never consulted by access checks |
| `grantType`    | String   | ✔        | `PURCHASE` | Enum `GrantType`. Only `PURCHASE` is ever written. `GIFT \| PROMO \| CREATOR` ⏸ **Deferred (D-18)** |
| `orderId`      | String   | ✔        |          | FK to `orders._id` (the **latest** completing order, see re-purchase rule) |
| `status`       | String   | ✔        | `ACTIVE` | Enum `EntitlementStatus`: `ACTIVE \| REVOKED \| EXPIRED`. Only `ACTIVE` is ever written today; `REVOKED` is reserved for admin refunds |
| `grantedAt`    | DateTime | auto     |          | **The date the right started.** 🔜 Drives both the collection union (§8.6: items with `removedAt > grantedAt`) and the purchased version (§6a) |
| `expiresAt`    | DateTime |          | `null`   | ⏸ **Deferred (D-18).** Never set and **not evaluated** by access checks |

> 🔜 **No new fields are added to `entitlements` by H2 or N8** (rev. 10). The
> collection snapshot (`snapshotEntryIds[]`) and the three version pointers
> proposed in earlier revisions were dropped: the first could not express
> D-20, the second could not express per-entry versions inside a collection.
> Both are now derived from `grantedAt` plus data that lives where the change
> happens (`collections.items[]`, `entries.versions[]`, `version_choices`).

### Creation rules

- Written only by `PaymentService.createEntitlement` and only when the order is
  `COMPLETED` **and** has a `stellarTxHash` (hard precondition). `TIP` and
  `PUBLISH_*` orders return without writing.
- `MediaEntitlementService` re-verifies on every CDN check that a `PURCHASE`
  entitlement is backed by a `COMPLETED` order with a tx hash
  (`isBackedByCompletedOrder`). 🔜 H3 applies the same check in the public
  entry view via `ContentAccessPolicy`.
- ⚠️ Today creation is an **insert** that treats `DuplicateKeyException` as
  success. With a `REVOKED` entitlement in place (after a refund) a re-purchase
  would pay and silently get nothing (**H11**). 🔜 **H11 / S-06**: creation
  becomes an **upsert** on `(tenantId, userId, targetType, targetId)` —
  `status = ACTIVE`, `orderId = new order`, `grantedAt = now`; an already
  `ACTIVE` entitlement with the same `orderId` is a no-op (R-30). A new
  `grantedAt` means collection rights and purchased versions are evaluated
  from the new purchase — correct, the buyer held no right in between.

### Indexes

| Index | Definition | Created by / note |
|---|---|---|
| `idx_ent_tenant_user_entry` | `{tenantId:1, userId:1, entryId:1}` **unique, partial `{targetType:"ENTRY"}`** | ✅ `CoreIndexMigration` (H9). Replaces the pre-H9 annotation `idx_ent_tenant_user_target_entry` (`{tenantId,userId,targetType,entryId}` unique, no partial filter), under which every `COLLECTION` entitlement has `entryId = null` and two collection purchases by one user would have collided. Uniqueness is independent of `status` (one document per user × target; H11 upserts it) |
| `idx_ent_tenant_user_coll` | `{tenantId:1, userId:1, collectionId:1}` **unique, partial `{targetType:"COLLECTION"}`** | ✅ `CoreIndexMigration` (H9). Same reasoning, mirrored |
| `idx_tenant_entry_status` | `{tenantId:1, entryId:1, status:1}` | ✅ `CoreIndexMigration` |
| `idx_ent_tenant_coll_status` | `{tenantId:1, collectionId:1, status:1}` | ✅ `CoreIndexMigration` |
| `idx_tenant_user_status_granted` | `{tenantId:1, userId:1, status:1, grantedAt:-1}` | ✅ `CoreIndexMigration` ("My Purchases") |

---

## 6a. `version_choices` — 🔜 Planned (N8 / D-14 / S-07)

The buyer's **chosen version of one entry**, independent of how the right was
obtained (direct purchase, one or several collections). A document exists
**only if the buyer ever updated**; otherwise the buyer uses their
`purchasedVersion`, which is **computed, never stored**.

| Field | Type | Required | Notes |
|---|---|---|---|
| `_id` | ObjectId | auto | |
| `tenantId` | String | ✔ | |
| `userId` | String | ✔ | |
| `entryId` | String | ✔ | |
| `activeVersion` | Integer | ✔ | The version the buyer is using |
| `previousVersion` | Integer | | Value of `activeVersion` before the last update — enables **one** undo |
| `updatedAt` | DateTime | auto | |

Index (migration): `{tenantId:1, userId:1, entryId:1}` **unique**.

```python
# Version the buyer acquired — immutable by construction (depends only on
# immutable dates). Right start = earliest moment the user had any right.
def right_started_at(user, entry):
    starts = []
    e = entitlements.find_one(userId=user, targetType=ENTRY, entryId=entry.id, status=ACTIVE)
    if e: starts.append(e.grantedAt)
    for coll, rec in collections_covering(entry.id):            # §8.6 rule, incl. removed records
        ce = entitlements.find_one(userId=user, targetType=COLLECTION, collectionId=coll.id, status=ACTIVE)
        if ce and (rec.removedAt is None or rec.removedAt > ce.grantedAt):
            starts.append(max(ce.grantedAt, rec.addedAt or EPOCH))
    return min(starts) if starts else None

def purchased_version(user, entry):
    if not entry.versions: return 1                             # S-01: absent = v1
    t = right_started_at(user, entry)
    return max(v.version for v in entry.versions if v.approvedAt <= t) or 1

def effective_version(user, entry):
    c = version_choices.find_one(userId=user, entryId=entry.id)
    return c.activeVersion if c else purchased_version(user, entry)
```

Buyer actions (per entry, idempotent, free, never automatic):

- **Update:** `previousVersion ← effective_version`, `activeVersion ← entry.currentVersion` (upsert).
- **Undo:** `activeVersion ← previousVersion`, `previousVersion ← null` (only while `previousVersion != null`).
- Updating one entry of a collection never touches the others. No job,
  moderation transition or creator action writes to this collection.

---

## 7. `favorites`

A user-saved bookmark for an entry or collection. Enables cross-device "save for later" (like YouTube / Patreon).

### Fields

| Field      | Type     | Required | Default | Notes                                        |
|------------|----------|----------|---------|----------------------------------------------|
| `_id`      | ObjectId | auto     |         |                                              |
| `tenantId` | String   | ✔        |         |                                              |
| `userId`   | String   | ✔        |         | OAuth2 user id                               |
| `itemId`   | String   | ✔        |         | FK to `entries._id` or `collections._id`     |
| `itemType` | String   | ✔        |         | Enum: `ENTRY \| COLLECTION`                  |
| `addedAt`  | DateTime | auto     |         | `@CreatedDate`                               |

### State Machine

No state transitions — insert or delete only (toggle). Favorites whose target
no longer exists are deleted lazily when listed.

### Indexes

| Index | Definition | Created by |
|---|---|---|
| `idx_tenant_user_item` | `{tenantId:1, userId:1, itemId:1}` unique | ✅ `CoreIndexMigration` (H9) |
| `idx_tenant_user_added` | `{tenantId:1, userId:1, addedAt:-1}` | ✅ `CoreIndexMigration` (H9) |

`FavoriteService.listFavorites` computes `locked` / `unlocked` for both entries
and collections (§8.5) ✅.

---

## 7b. `franchises`

A franchise ("beta") is a commercial sub-tenant operating under a franchisor
tenant ("alfa") at `<franchisor-subdomain>.earnlumens.org/f/<slug>`. It resells
the franchisor's already-approved content for a frozen commission. It is **not**
a full tenant — it has no row in `tenants`, cannot upload content, approve
creators or change editorial rules.

> **Ownership:** this collection is written exclusively by **admin-api** (the
> governance home). media-store-api reads it (`FranchiseReadModel`) to resolve
> franchise storefronts, apply the franchise split at purchase time, and render
> franchise branding. The fields below mirror the admin-api `Franchise` entity.

### Fields

| Field               | Type       | Required | Default  | Notes                                                        |
|---------------------|------------|----------|----------|--------------------------------------------------------------|
| `_id`               | ObjectId   | auto     |          | Stored as `franchiseId` on orders/entitlements               |
| `tenantId`          | String     | ✔        |          | Franchisor **subdomain** (matches the runtime `tenantId`). Immutable |
| `slug`              | String     | ✔        |          | URL slug under `/f/<slug>`. Lowercase, RFC-1123-safe, unique within the franchisor. Immutable |
| `ownerOauthUserId`  | String     | ✔        |          | Global user who owns the franchise. Immutable                |
| `ownerUsername`     | String     |          | `null`   | Denormalized for display                                     |
| `ownerDisplayName`  | String     |          | `null`   | Denormalized for display                                     |
| `commissionPercent` | Decimal128 | ✔        |          | **Frozen** at creation from the franchisor's `defaultFranchiseCommissionPercent`. % of the franchisor's own profit share. Immutable |
| `payoutWallet`      | String     | ✔        |          | Stellar public key (`G…`) receiving the franchise commission |
| `title`             | String     |          | `null`   | Branding override — `null` inherits the franchisor's         |
| `description`       | String     |          | `null`   | Commercial description shown on the franchise storefront      |
| `logoR2Key`         | String     |          | `null`   | Branding override — `null` inherits                           |
| `coverR2Key`        | String     |          | `null`   | Storefront cover/hero image                                   |
| `accentColor`       | String     |          | `null`   | Secondary/accent colour (`#RRGGBB[AA]`) — `null` inherits     |
| `status`            | String     | ✔        | `ACTIVE` | Enum: `ACTIVE \| DISABLED`. DISABLED hides the storefront and blocks new sales; history is never deleted |
| `disabledReason`    | String     |          | `null`   | Required justification when `DISABLED`                        |
| `disabledBy`        | String     |          | `null`   | Actor who disabled it                                         |
| `disabledAt`        | DateTime   |          | `null`   |                                                              |
| `acceptedTermsAt`   | DateTime   | ✔        |          | When the owner accepted the (frozen) terms — for audit       |
| `createdAt`         | DateTime   | auto     |          | `@CreatedDate`                                                |
| `updatedAt`         | DateTime   | auto     |          | `@LastModifiedDate`                                           |

### Status State Machine

```
ACTIVE ──→ DISABLED        (franchisor takedown for misuse — never deleted)
```

### Indexes

```js
{ tenantId: 1, slug: 1 }     // unique — franchise URL path is unique within the franchisor
{ tenantId: 1, status: 1 }   // franchisor dashboard + storefront ACTIVE listing
{ ownerOauthUserId: 1 }      // "my franchises" for a global user
```

> ⚠️ Annotated in admin-api (`Franchise`), not created by any migration
> (admin-api has no auto-index-creation either). Same remediation path as H9
> on the admin-api side.

---

## 7c. `franchise_user_bans`

Record of a user barred by a franchisor from creating **new** franchises under
it. A ban does not touch the user's global account nor any franchise they
already own elsewhere. Written by admin-api; read at franchise-creation time.

### Fields

| Field      | Type     | Required | Default | Notes                                          |
|------------|----------|----------|---------|------------------------------------------------|
| `_id`      | ObjectId | auto     |         |                                                |
| `tenantId` | String   | ✔        |         | Franchisor subdomain that issued the ban       |
| `userId`   | String   | ✔        |         | OAuth user-id of the banned user               |
| `reason`   | String   | ✔        |         | Justification                                  |
| `bannedBy` | String   | ✔        |         | Actor who issued the ban                       |
| `bannedAt` | DateTime | auto     |         | `@CreatedDate`                                 |

### Indexes

```js
{ tenantId: 1, userId: 1 }   // unique — one ban per tenant × user; checked on franchise creation
```

> ⚠️ Annotated in admin-api, not created by any migration.

---

## 7d. `moderation_cases` — 🔜 Planned (H3 / D-15)

One document per **moderation action that blocks content**. It is the home of
`moderationCaseId`: the grouping key the administrator resolves in bulk, the
reference stored in `entry.moderationBlock.caseId`, and the parent of every
`disputes` document.

| Field | Type | Required | Notes |
|---|---|---|---|
| `_id` | ObjectId | auto | = `moderationCaseId` |
| `tenantId` | String | ✔ | |
| `kind` | String | ✔ | `TAKEDOWN` (content is illegal / stolen / violates policy — D-13 `CONTENT_TAKEDOWN`) \| `PREVENTIVE` (high-severity reports or an illegal pending edit; awaiting human review) |
| `entryIds[]` | [String] | | Entries blocked by this action |
| `collectionIds[]` | [String] | | Collections blocked by this action |
| `reason` | String | ✔ | Justification (also copied to `moderationFeedback`) |
| `actor` | String | ✔ | Moderator id or `"EarnLumens"` (AI) |
| `createdAt` | DateTime | auto | |
| `resolvedAt` | DateTime | | Set on reinstatement or dismissal |
| `resolution` | String | | `REINSTATED` \| `UPHELD` |

Indexes (migration): `{tenantId:1, createdAt:-1}`, `{tenantId:1, entryIds:1}`.

---

## 7e. `disputes` — 🔜 Planned (H3 / D-09 / D-15)

One document per **purchase affected** by a takedown (not one per file). It
is informational: the system opens it automatically; **only an administrator
resolves it**. The entitlement is never revoked by the dispute.

| Field | Type | Required | Notes |
|---|---|---|---|
| `_id` | ObjectId | auto | |
| `tenantId` | String | ✔ | |
| `moderationCaseId` | String | ✔ | FK to `moderation_cases._id` |
| `entitlementId` | String | ✔ | The affected purchase |
| `orderId` | String | ✔ | Copied from the entitlement |
| `userId` | String | ✔ | Buyer |
| `entryId` | String | ✔ | The entry that triggered this dispute |
| `affectedEntryIds[]` | [String] | ✔ | All entries of the case this purchase gives access to (≥ 1) |
| `collectionId` | String | | Set when the access path is a collection entitlement |
| `reason` | String | ✔ | Copied from the case |
| `status` | String | ✔ | `OPEN` → `REFUNDED` \| `RESOLVED` \| `DISMISSED` |
| `createdAt`, `resolvedAt`, `resolvedBy` | | | |

Indexes (migration): `{tenantId:1, moderationCaseId:1, entitlementId:1}`
**unique** (idempotency), `{tenantId:1, userId:1, status:1}`,
`{tenantId:1, status:1, createdAt:-1}`.

| Status      | Meaning                                                           |
|-------------|-------------------------------------------------------------------|
| `OPEN`      | Buyer was affected by a takedown. Awaiting administrator.         |
| `REFUNDED`  | Admin issued a (partial) compensating payment. Order → `REFUNDED`, entitlement → `REVOKED` |
| `RESOLVED`  | Content was reinstated. Access returned automatically when `moderationBlock` was cleared |
| `DISMISSED` | Admin reviewed and dismissed (e.g. content violated ToS, no refund owed) |

---

## 8. Access Model — Acquired Rights, Union Access, Moderation Blocks

This section defines **how `locked` / `unlocked` are computed** at runtime.
They are never stored — they are derived from entitlements, ownership,
collection membership and the moderation block.

> **Governing principle (Mycelium §1.2).** A purchase confirmed on-chain keeps
> its rights even if the creator, collection, tenant, domain, wallet or any
> other node later disappears or changes. The **only** thing that removes
> access is a justified content takedown by moderation or law — and even then
> the entitlement is never revoked; access is blocked and a dispute is opened
> for the administrator.
>
> **Implementation state.** Today the two decision points disagree:
> `MediaEntitlementService.checkEntitlement` (CDN) **never looks at
> `entry.status`**, while `PublicEntryService.isVisibleToViewer` (UI) hides
> `IN_REVIEW` / `REJECTED` / `SUSPENDED` from everyone and only preserves
> buyers in `ARCHIVED` / `DELETED`. The CDN also serves free content in any
> status. 🔜 **H3** unifies both behind one `ContentAccessPolicy` implementing
> §8.3 as written here. Everything in §8.3–§8.9 is the **target rule**; where
> the code differs today it is marked ⚠️.

### 8.1 Terminology (matches UI code)

| UI Field   | Meaning                                                              |
|------------|----------------------------------------------------------------------|
| `locked`   | Content requires payment and user does NOT have access               |
| `unlocked` | Content requires payment and user DOES have access (purchased)       |
| (neither)  | Content is free — accessible when publicly visible (§8.9)            |

### 8.2 What decides access: status vs. moderation block

Two orthogonal facts decide access (D-01, S-03):

| Fact | Decides | Who sets it |
|---|---|---|
| `entry.status` | **Public visibility** — who can *discover* the entry and whether a non-buyer can open it | Creator (publish / unlist / archive / delete) and the moderation workflow |
| `entry.moderationBlock` 🔜 (H3) | **Blocked for everyone** — buyers, visitors and the creator alike | Moderation only, by an explicit takedown or preventive block; cleared on reinstatement |

Consequences (the conceptual matrix of D-01 — `NONE / BUYERS_RETAIN /
BLOCKED_ALL` — collapses to these two facts, nothing else is persisted):

| Situation | Visitors (no purchase) | Buyers (entry or collection entitlement) | Creator |
|---|---|---|---|
| `PUBLISHED` | ✔ | ✔ | ✔ |
| `UNLISTED` | ✔ by direct link | ✔ | ✔ |
| `DRAFT`, `ARCHIVED`, `DELETED`, `APPROVED` (creator-controlled, not public) | ✘ | ✔ **retain** | ✔ |
| `IN_REVIEW` — first review (nothing sold yet) | ✘ | — (no buyers exist) | ✔ |
| `IN_REVIEW` — re-review by report escalation | ✘ | ✔ **retain** (⚠️ today: 404 in UI, served by CDN) | ✔ |
| `REJECTED` — of a **pending edit** 🔜 (N9, D-13 `CHANGES_REJECTED`) | unchanged — the entry stays `PUBLISHED` | ✔ unchanged | ✔ |
| `REJECTED` — first submission (nothing sold) | ✘ | — | ✔ |
| **`moderationBlock != null`** (D-13 `CONTENT_TAKEDOWN` or preventive) — any status, **all versions** (D-16) | ✘ | ✘ **blocked** (disputes opened, §8.8) | ✘ |

> **Principle:** A creator cannot retroactively remove access from paying
> users by any ordinary action — status change, price change, collection
> edit, new version. A moderation takedown overrides everything, for everyone.

#### Status classification helper (target)

```java
// Public, discoverable / reachable without purchase
Set<EntryStatus> PUBLIC_VISIBLE  = Set.of(PUBLISHED, UNLISTED);

// Hidden from non-buyers; buyers retain access (creator-controlled or in re-review)
Set<EntryStatus> BUYERS_RETAIN   = Set.of(DRAFT, APPROVED, IN_REVIEW, ARCHIVED, DELETED, REJECTED);

// Blocked for everyone — NOT a status: entry.moderationBlock != null
```

⚠️ Today the code has no such helper; `SUSPENDED` and `REJECTED` act as the
"blocked" signal in the UI only (`ADMIN_BLOCKED` of the previous revision),
and the CDN ignores status entirely. After H3, `SUSPENDED` remains a status
(set by admin-api together with `moderationBlock`) but the **block field, not
the status, is what denies access**.

### 8.3 Entry Access Check (target — `ContentAccessPolicy.canAccess`)

```python
def has_entry_access(user_id, entry):
    # 0. Moderation block → NO ONE has access, not even the owner,
    #    and regardless of which version the viewer uses (D-16)
    if entry.moderationBlock is not None:
        return DENIED_BLOCKED            # UI: "removed for policy reasons" + dispute ref

    # 1. Owner always has access to their own content
    if entry.userId == user_id:
        return ALLOWED

    # 2. Free content → accessible only when publicly visible (D-19)
    if not entry.isPaid:
        return ALLOWED if entry.status in PUBLIC_VISIBLE else DENIED_HIDDEN

    # 3. Direct entry entitlement (purchased individually)
    #    Survives every creator-initiated status (DRAFT, UNLISTED, ARCHIVED,
    #    DELETED), re-review (IN_REVIEW) and a rejected pending edit.
    e = entitlements.find_one(userId=user_id, targetType=ENTRY,
                              entryId=entry.id, status=ACTIVE)
    if e and backed_by_completed_order(e):
        return ALLOWED

    # 4. Collection entitlement — UNION BY RIGHT DATE (D-11 / D-20)
    #    The buyer has the entry via collection C iff C has a membership record
    #    for the entry that is still active (removedAt == null) OR was removed
    #    AFTER the buyer's right started (removedAt > grantedAt). Covers items
    #    present at purchase, items added later, and items later removed.
    #    Collections are matched in ANY status (⚠️ today: PUBLISHED only).
    for coll in collections.find("items.entryId" = entry.id):          # includes removed records
        e = entitlements.find_one(userId=user_id, targetType=COLLECTION,
                                  collectionId=coll.id, status=ACTIVE)
        if not e or not backed_by_completed_order(e): continue
        if any(r.entryId == entry.id and (r.removedAt is None or r.removedAt > e.grantedAt)
               for r in coll.items):
            return ALLOWED

    # 5. Paid, not purchased
    return DENIED_LOCKED if entry.status in PUBLIC_VISIBLE else DENIED_HIDDEN
```

Three distinct denials, so the UI never shows a generic 404 to a buyer:
`DENIED_BLOCKED` (takedown; buyers see their dispute), `DENIED_HIDDEN`
(not public and no right), `DENIED_LOCKED` (public, paywalled).

⚠️ **Code today** (`MediaEntitlementService.checkEntitlement`): no step 0, step 2
is "free ⇒ allowed in any status", step 4 uses only live membership of
`PUBLISHED` collections (no `removedAt` records exist yet), and the three
denials are one 403.
`PublicEntryService.isVisibleToViewer` additionally denies buyers in
`IN_REVIEW` / `REJECTED` / `SUSPENDED` and does not check
`backed_by_completed_order`.

### 8.4 Collection Access Check

```python
def has_collection_access(user_id, collection):
    if collection.moderationBlock is not None:      # 🔜 H3
        return DENIED_BLOCKED
    if collection.userId == user_id:
        return ALLOWED
    if not collection.isPaid:
        return ALLOWED if collection.status in {PUBLISHED} else DENIED_HIDDEN
    e = entitlements.find_one(userId=user_id, targetType=COLLECTION,
                              collectionId=collection.id, status=ACTIVE)
    if e and backed_by_completed_order(e):
        return ALLOWED                               # in ANY collection status (⚠️ today: PUBLISHED only, H2)
    return DENIED_LOCKED if collection.status == PUBLISHED else DENIED_HIDDEN
```

A buyer can always open the detail page of a collection they bought, even if
it is `ARCHIVED`, `DELETED`, `IN_REVIEW` or `REJECTED` (⚠️ today blocked for
non-owners, H2). The page lists the active items plus the removed records the
buyer still has a right to (`removedAt > grantedAt`), flagged; items under
`moderationBlock` are shown as blocked.

### 8.5 Computing `locked` / `unlocked`

```python
# For an entry:
locked   = entry.isPaid and not has_entry_access(user_id, entry)
unlocked = entry.isPaid and has_entry_access(user_id, entry) and not is_owner

# For a collection:
locked   = collection.isPaid and not has_collection_access(user_id, collection)
unlocked = collection.isPaid and has_collection_access(user_id, collection) and not is_owner
```

`FavoriteService.listFavorites` ✅ already computes both for entries (direct
entitlement ∪ entries of any entitled collection) and collections.

### 8.6 Access Rules Summary — Union by Right Date (D-11 / D-20)

> **Decision D-20 (2026-10-19).** A collection buyer keeps
> every entry the collection contained **when they bought it** and gains
> every entry **added afterwards** — even if any of them is later removed.
> Buyers who purchase **after** an entry was removed do **not** acquire it.
>
> **One rule (S-04, rev. 10):** the buyer with `grantedAt = G` has entry `X`
> via collection `C` iff `C.items[]` has a record for `X` with
> `removedAt == null` **or** `removedAt > G`. Nothing is stored per buyer.
> This replaces both the former "Live Access, No Snapshot" (Patreon) model
> and the interim "snapshot at purchase" model, which could not express an
> entry added after purchase and removed later.

#### Collection purchase rules

| Scenario                                                  | Record for X        | Buyer `G` | Result (target) | Today |
|-----------------------------------------------------------|---------------------|-----------|-----------------|-------|
| X present at purchase, still present                      | `removedAt = null`  | any       | ✔ access | ✔ |
| X present at purchase, removed at `R > G`                 | `removedAt = R`     | `G < R`   | ✔ **retained** | ⚠️ lost |
| X **added** at `T > G`                                     | `removedAt = null`  | `G < T`   | ✔ gained (live membership) | ✔ |
| X added at `T > G`, then **removed** at `R > T`           | `removedAt = R`     | `G < R`   | ✔ **retained** | ⚠️ lost |
| X removed at `R`, buyer purchases at `G₂ > R`              | `removedAt = R`     | `G₂ > R`  | ✘ **not acquired** | ✘ |
| X removed, then **re-added** at `T₂` (new record)          | new `removedAt = null` | any    | ✔ for every buyer | ✔ |
| Creator archives / soft-deletes / unpublishes the collection, or it enters re-review / is rejected | — | any | ✔ buyer keeps everything and can open the collection page | ⚠️ buyer loses everything (only `PUBLISHED` consulted) — H2 |
| Creator hard-deletes a collection with buyers             | — | — | ✘ **409** — not allowed | ⚠️ allowed from `DRAFT`/`REJECTED` — H2 |
| Creator changes free entry to paid inside collection      | — | any | ✔ still accessible via collection | ✔ |
| Creator changes paid entry to free                        | — | any | ✔ accessible to everyone while `PUBLISHED`/`UNLISTED` | ✔ |
| An entry of the collection is **taken down** (`moderationBlock`) | — | any | ✘ that entry only, for everyone; a dispute opens for each buyer whose right covers it (§8.8). Rest unaffected | ⚠️ CDN still serves it — H3 |
| Collection refunded, then re-purchased at `G₂`             | — | `G₂`      | rights evaluated from `G₂` (items removed in between are not recovered) | design only |

> **Key principle:** an entitlement is a right to access. For
> `targetType = COLLECTION` the right means *"everything this collection has
> contained at any moment since I bought it."* One entitlement document; no
> per-entry entitlements, no per-buyer snapshot; the history lives in the
> collection, where the change happens.

#### Individual purchase vs entry status rules

| Scenario                                                    | Has individual purchase? | Result (target) | Today |
|-------------------------------------------------------------|--------------------------|-----------------|-------|
| Creator unlists (→ `UNLISTED`)                              | ✔ | ✔ retained | ✔ |
| Creator archives / soft-deletes (→ `ARCHIVED` / `DELETED`)  | ✔ | ✔ retained; visible in "My Purchases", streamable | ✔ |
| Creator archives / soft-deletes                             | ✘ | ✘ hidden | ✔ |
| Entry re-enters `IN_REVIEW` (report escalation)             | ✔ | ✔ retained, with a notice | ⚠️ UI 404 / CDN serves — H3 |
| Creator edits title / thumbnail / preview (→ pending version) | ✔ | ✔ unchanged; entry stays `PUBLISHED`; buyer keeps seeing the approved version | ⚠️ entry → `IN_REVIEW`, buyer gets 404 — N9 |
| Pending edit **rejected** (`CHANGES_REJECTED`)              | ✔ | ✔ unchanged, **no dispute** | ⚠️ entry → `REJECTED` — N9 |
| New version **approved** (D-14)                             | ✔ | ✔ buyer keeps their effective version; sees "new version available"; updates (and can undo once) **only by explicit action**; never charged | n/a — N8 |
| New version of **one entry inside a purchased collection** (D-14 / S-07) | via collection | ✔ only that entry shows the notice; updating it changes nothing for the other entries; each entry has its own purchased / active / undo state | n/a — N8 |
| Entry **added to a collection** after purchase, later re-versioned | via collection | ✔ purchased version = the one current when the entry **entered** the collection (`max(grantedAt, addedAt)`) | n/a — N8 |
| Content **taken down** (`CONTENT_TAKEDOWN` → `moderationBlock`) | ✔ | ✘ **blocked, all versions** (D-16). Dispute opened (§8.8). Entitlement stays `ACTIVE` | ⚠️ UI 404 / CDN serves — H3 |
| Content taken down                                          | ✘ | ✘ blocked | ⚠️ CDN serves free content — H3 |
| Reinstated after takedown (→ `PUBLISHED`, block cleared)    | ✔ | ✔ access returns automatically, no entitlement change | — |
| Creator changes price after purchase                        | ✔ | ✔ no effect — entitlement is price-independent | ✔ |
| Creator changes wallet after purchase                       | ✔ | ✔ no effect — splits are frozen on the order | ✔ |
| Tenant blocked by platform                                  | ✔ | ✔ library and playback keep working on `{sub}.earnlumens.org` in read-only mode (H1 / D-04) | ⚠️ falls to default tenant — H1 |
| Buyer banned (participation sanction)                       | ✔ | ✔ read-only session keeps the library unless the sanction explicitly revokes it (N3) | ⚠️ total loss — N3 |

### 8.7 Versions & Download Policy

**Versions (🔜 N8 / D-14 / S-07).** The buyer always keeps the version they
acquired (`purchased_version`, computed from the dates of the right — §6a),
uses the one they choose (`version_choices.activeVersion`, written only when
they update) and can undo the last update once (`previousVersion`). Choices
are **per entry**: a collection buyer manages each entry independently and
updating one never affects the others or any other buyer. New versions are
offered only after approval; **nothing updates automatically**, updating is
free, and a change summary (`versions[n].changeSummary`) is shown before
updating. A takedown blocks **all** versions (D-16); there is no per-version
blocking.

**Download.** Everything a user can access (entry purchase, collection
purchase, or public free content) must be downloadable: video and audio
files, images at full resolution, resource/article content as PDF export.
⚠️ The dedicated download endpoint/button is **not implemented**; today
`GET /api/media/entitlements/{entryId}` returns `contentDisposition =
attachment` for non-inline types and the CDN streams the FULL asset.

> **Rationale.** Downloads are a convenience and a safeguard against platform
> failure, **not** a substitute for rights: with union access (§8.6) and
> buyer-controlled versions, the buyer no longer needs to "download before it
> disappears". Terms (§13) still recommend keeping local copies.

### 8.8 Moderation Blocks & Dispute Flow (🔜 H3 / D-09 / D-13 / D-15 / D-16)

#### Two kinds of rejection (D-13)

| What is moderated | Outcome | Access | Dispute |
|---|---|---|---|
| A **pending edit** (`pendingVersion`, N9) — title, description, thumbnail, preview, resource text, new FULL file, collection items | **`CHANGES_REJECTED`** — slot cleared, `moderationFeedback` set, live document and `status` untouched | Buyers and public unchanged | **None** |
| The **live content** — by report escalation (`HIGH` severity), a moderator's explicit "remove content", or a late discovery (stolen, illegal) | **`CONTENT_TAKEDOWN`** — `moderationBlock` set (+ `SUSPENDED` by admin-api), a `moderation_cases` document created | **Blocked for everyone, all versions** | **One `OPEN` dispute per affected purchase** |

The scope is **inferred from the moderated object** (S-05): the moderator
never fills a "scope" field. Removing live content is always a separate,
explicit action with a justification. If the classifier finds an illegal /
high-severity violation inside a pending edit, the edit is rejected **and** a
`PREVENTIVE` block is placed on the live content until a human reviews it.

#### Trigger (replaces the former status-based pseudocode)

```python
def on_content_blocked(case):               # case: moderation_cases document
    for entry_id in case.entryIds:
        entry = db.entries.findOne({ _id: entry_id })
        entry.moderationBlock = { caseId: case.id, reason: case.reason,
                                  by: case.actor, at: now() }
        db.entries.save(entry)
        edge_cache.invalidate(entry_id)      # HLS entitlement cache (N6)

    # Affected purchases = UNION of both access paths (D-15 / R-19)
    affected = {}
    for entry_id in case.entryIds:
        # (a) direct entry entitlements
        for e in db.entitlements.find({ tenantId: case.tenantId, targetType: "ENTRY",
                                        entryId: entry_id, grantType: "PURCHASE",
                                        status: "ACTIVE" }):
            affected.setdefault(e.id, (e, set())).[1].add(entry_id)
        # (b) collection entitlements whose right covers the entry (§8.6 rule)
        for coll in db.collections.find({ tenantId: case.tenantId, "items.entryId": entry_id }):
            recs = [r for r in coll.items if r.entryId == entry_id]
            for e in db.entitlements.find({ tenantId: case.tenantId, targetType: "COLLECTION",
                                            collectionId: coll._id, grantType: "PURCHASE",
                                            status: "ACTIVE" }):
                if any(r.removedAt is None or r.removedAt > e.grantedAt for r in recs):
                    affected.setdefault(e.id, (e, set())).[1].add(entry_id)

    # ONE dispute per affected purchase, idempotent on (caseId, entitlementId)
    for e, entry_ids in affected.values():
        db.disputes.upsert(
            { tenantId: case.tenantId, moderationCaseId: case.id, entitlementId: e.id },
            { orderId: e.orderId, userId: e.userId,
              entryId: min(entry_ids), affectedEntryIds: sorted(entry_ids),
              collectionId: e.collectionId, reason: case.reason,
              status: "OPEN", createdAt: now() })

    # Entitlements are NOT revoked. No refund. No automatic resolution.
```

A sweep (same pattern as the payment watchdog) re-runs the trigger for any
blocked entry whose buyers lack a dispute, so a crash mid-transition never
leaves a buyer without follow-up (R-20).

#### Resolution — administrator only (D-09)

- The administrator lists disputes **by `moderationCaseId`** and resolves in
  bulk or individually: `REFUNDED` (compensating payment off-platform; order
  → `REFUNDED`, entitlement → `REVOKED`), `RESOLVED` (content reinstated:
  `moderationBlock` cleared, access already back), `DISMISSED` (violation
  confirmed, no refund).
- **No code path ever moves a dispute out of `OPEN` or issues a refund by
  itself.** Opening is automatic; resolving never is.
- A buyer banned from the platform is **not** a dispute trigger (the trigger is
  the content, not the user); see N3 in the plan.

> **Implementation status:** none of §8.8 is implemented. Today a `SUSPENDED` /
> `REJECTED` entry simply disappears from the UI (404 for buyers) while the
> CDN keeps serving it; administrators handle complaints manually. §8.8 is the
> first P0 item of the resilience plan (H3 + N9).

### 8.9 Free content (D-19)

Free content (`isPaid = false`) is publicly accessible **only** while the
entry is `PUBLISHED` or `UNLISTED`. In `DRAFT`, `APPROVED`, `IN_REVIEW`,
`REJECTED`, `ARCHIVED` or `DELETED` only the creator can open or stream it;
under `moderationBlock` nobody can. This is the UI rule today; ⚠️ the CDN
currently serves free content in every status, including `SUSPENDED` — H3
aligns it.

---

## 9. Query Patterns

### 9.1 Public Feed (paginated, newest first)

```js
db.entries.find({
  tenantId: T,
  status: "PUBLISHED",
  visibility: "PUBLIC"
}).sort({ publishedAt: -1 }).skip(offset).limit(pageSize)
```

Uses index: `{ tenantId, status, publishedAt }`.  
Returns `thumbnailR2Key` for feed cards — no Asset join needed.

### 9.2 Filtered Feed by Type

```js
db.entries.find({
  tenantId: T,
  status: "PUBLISHED",
  visibility: "PUBLIC",
  type: "VIDEO"
}).sort({ publishedAt: -1 }).skip(offset).limit(pageSize)
```

Uses index: `{ tenantId, status, type, publishedAt }`.

### 9.3 Entry Detail

```js
// 1. Entry document
db.entries.findOne({ tenantId: T, _id: entryId })

// 2. All assets for this entry
db.assets.find({ tenantId: T, entryId: entryId })
```

Two queries. Asset results give the UI the preview + full asset metadata.

### 9.4 Entitlement Check (Worker flow) — target (`ContentAccessPolicy`, 🔜 H3 / H2 / H8 / N8)

```js
PUBLIC_VISIBLE = ["PUBLISHED", "UNLISTED"]

// 1. Load entry
entry = db.entries.findOne({ tenantId: T, _id: entryId })

// 2. Moderation block → DENY for everyone, owner included, all versions (D-16)
if (entry.moderationBlock != null) → denied(BLOCKED)

// 3. Owner always has access
if (entry.userId == requestUserId) → allowed(version = entry.currentVersion ?? 1)

// 4. Free entry → allowed only while publicly visible (D-19)
if (!entry.isPaid)
  → PUBLIC_VISIBLE.includes(entry.status) ? allowed(currentVersion) : denied(HIDDEN)

// 5. Direct entry entitlement (individual purchase) — any status
ent = db.entitlements.findOne({
  tenantId: T, userId: requestUserId,
  targetType: "ENTRY", entryId: entryId, status: "ACTIVE"
})
if (ent && backedByCompletedOrder(ent)) → allowed(version = effectiveVersion(requestUserId, entry))

// 6. Collection entitlement — union by right date (§8.6): a membership record
//    for the entry with removedAt == null or removedAt > entitlement.grantedAt
for (coll of db.collections.find({ tenantId: T, "items.entryId": entryId })) {   // any status
  ent = db.entitlements.findOne({ tenantId: T, userId: requestUserId,
                                  targetType: "COLLECTION", collectionId: coll._id, status: "ACTIVE" })
  if (!ent || !backedByCompletedOrder(ent)) continue
  if (coll.items.some(r => r.entryId == entryId && (r.removedAt == null || r.removedAt > ent.grantedAt)))
    → allowed(version = effectiveVersion(requestUserId, entry))
}

// 7. Paid, no right
→ PUBLIC_VISIBLE.includes(entry.status) ? denied(LOCKED) : denied(HIDDEN)

// 8. Resolve the file for the resolved version (only if allowed)
//    effectiveVersion = version_choices.activeVersion ?? purchasedVersion (§6a); no versions[] ⇒ 1
v = (entry.versions ?? []).find(x => x.version == version)
assetId = v ? v.assetId : latestReadyFull(T, entryId).id   // never a query that assumes uniqueness (H8)
return { r2Key, contentType, contentDisposition, hlsPrefix: v ? v.hlsR2Prefix : entry.hlsR2Prefix }
```

⚠️ **Today** (`MediaEntitlementService.checkEntitlement`): no step 2; step 4
is unconditional; step 6 consults `PUBLISHED` collections only and has no
`removedAt` records; the three denials are one 403; step 8 uses the
single-result query that throws with two READY FULL assets.

### 9.5 User's Library ("My Purchases")

```js
// 1. All active entitlements, newest first
db.entitlements.find({
  tenantId: T, userId: U, status: "ACTIVE"
}).sort({ grantedAt: -1 }).skip(offset).limit(pageSize)

// 2. Separate into entry and collection entitlements
entryIds      = results.filter(e => e.targetType == "ENTRY").map(e => e.entryId)
collectionIds = results.filter(e => e.targetType == "COLLECTION").map(e => e.collectionId)

// 3. Hydrate — NO status filter (buyers see DRAFT/ARCHIVED/DELETED too);
//    collection items = active records ∪ removed records with removedAt > grantedAt (🔜 H2)
entries     = db.entries.find({ tenantId: T, _id: { $in: entryIds } })
collections = db.collections.find({ tenantId: T, _id: { $in: collectionIds } })

// 4. Per-entry access state for the UI (🔜 H3 / N8) — version flags are per entry,
//    also for entries reached through a collection
entries.forEach(entry => {
  entry.accessState  = entry.moderationBlock ? "BLOCKED"          // greyed out, dispute reference
                     : PUBLIC_VISIBLE.includes(entry.status) ? "PUBLIC"
                     : "HIDDEN_BUT_OWNED"                           // fully accessible, small badge
  choice             = versionChoices.get(entry.id)                 // may be absent
  effective          = choice ? choice.activeVersion : purchasedVersion(U, entry)
  entry.newVersion   = (entry.currentVersion ?? 1) > effective && !entry.moderationBlock
  entry.canUndo      = choice?.previousVersion != null
})
```

✅ `PurchaseListService` already hydrates without a status filter and expands
collection items; the `accessState` / version flags are planned.

### 9.6 Creator Dashboard — My Entries

```js
db.entries.find({
  tenantId: T, userId: creatorId
}).sort({ createdAt: -1 }).skip(offset).limit(pageSize)
```

Uses index: `{ tenantId, userId }`.

### 9.7 Creator Dashboard — My Sales (entries + collections)

```js
db.orders.find({
  tenantId: T, sellerId: creatorId, status: "COMPLETED"
}).sort({ createdAt: -1 }).skip(offset).limit(pageSize)
```

Uses index: `{ tenantId, sellerId, status, createdAt }`.  
Response includes `targetType` so the dashboard can display entry vs. collection sales.

### 9.8 Collection Detail (with per-entry access status)

```js
// 1. Collection document (includes items[])
coll = db.collections.findOne({ tenantId: T, _id: collId })

// 2. Buyer's collection entitlement (any collection status for a buyer — 🔜 H2)
collEnt = db.entitlements.findOne({
  tenantId: T, userId: U,
  targetType: "COLLECTION", collectionId: collId, status: "ACTIVE"
})
hasCollAccess = collEnt != null && backedByCompletedOrder(collEnt)

// 3. Items to show (🔜 H2 / D-20): active records, plus — for a buyer — removed
//    records with removedAt > grantedAt, rendered after the live ones and flagged
//    "no longer in this collection"
active  = coll.items.filter(r => r.removedAt == null).sort(position)
retained = hasCollAccess ? coll.items.filter(r => r.removedAt != null && r.removedAt > collEnt.grantedAt) : []
entryIds = unique([...active, ...retained].map(r => r.entryId))
entries = db.entries.find({ tenantId: T, _id: { $in: entryIds } })

// 4. If no collection access, check individual entry entitlements
if (!hasCollAccess) {
  paidEntryIds = entries.filter(e => e.isPaid).map(e => e.id)
  entitledEntryIds = db.entitlements.find({
    tenantId: T, userId: U,
    targetType: "ENTRY", entryId: { $in: paidEntryIds }, status: "ACTIVE"
  }).map(e => e.entryId)
  // plus other purchased collections whose union contains these entries (§9.4 step 6)
}

// 5. For each entry, compute locked/unlocked (§8.5) and per-entry version flags (§9.5);
//    moderationBlock ⇒ shown as blocked
entries.forEach(entry => {
  if (entry.moderationBlock) { blocked = true }
  else if (!entry.isPaid) { locked = false; unlocked = false }
  else if (isOwner || hasCollAccess || entitledEntryIds.includes(entry.id))
    { locked = false; unlocked = true }
  else { locked = true; unlocked = false }
})
```

✅ Today: steps 1, 2 (PUBLISHED only), 4 and 5 (without `moderationBlock`);
non-owners cannot open a non-`PUBLISHED` collection; no retained records.

### 9.9 "Collections containing this entry"

```js
// Purchasable, published collections that contain entry X
db.collections.find({
  tenantId: T,
  status: "PUBLISHED",
  "items.entryId": entryId,
  isPaid: true
})
```

Uses index: `{ tenantId, "items.entryId" }`. ✅ Implemented
(`CollectionService.getCollectionsContainingEntry`) and shown on the entry
paywall as "also available in…". ⏸ The `COLLECTION_ONLY` use (hide the
individual buy button) is deferred with `pricingMode` (D-17).

### 9.10 Collection Feed (paginated)

```js
db.collections.find({
  tenantId: T,
  status: "PUBLISHED",
  visibility: "PUBLIC"
}).sort({ publishedAt: -1 }).skip(offset).limit(pageSize)
```

Uses index: `{ tenantId, status, publishedAt }`.

### 9.11 Purchase Flow — Entry

```js
// 1. Validate target
entry = db.entries.findOne({ tenantId: T, _id: E })
require(entry.isPaid && entry.status == "PUBLISHED" && entry.userId != U)
// (⏸ pricingMode is NOT checked — D-17)

// 2. Reconcile the buyer's previous orders for THIS target type (§5.3)
//    ⚠️ today the query omits targetType and picks up TIP orders — H10
existing = db.orders.find({ tenantId: T, userId: U, targetType: "ENTRY", entryId: E })
processExistingOrders(existing)        // may reuse a live PENDING order or reject "already purchased"

// 3. Build the full split (§5.1), validate wallets, build the unsigned tx
//    and persist the PENDING order WITH its definitive stellarTxHash
db.orders.insertOne({
  tenantId: T, userId: U, targetType: "ENTRY",
  entryId: E, collectionId: null,
  sellerId: S, franchiseId, resellerId, originalCreatorId,
  amountXlm, originalAmountUsd, xlmUsdRate, priceCurrency,
  status: "PENDING", stellarTxHash: hash, buyerWallet: W, memo: M,
  unsignedXdr: xdr, integrityHash, expiresAt, paymentSplits: fullSplits
})

// 4. Buyer signs → submit: CAS PENDING→PROCESSING + strict XDR verification,
//    then async Horizon submission and on-chain confirmation

// 5. On confirmation: CAS PROCESSING→COMPLETED, then grant
db.orders.updateOne({ _id: orderId, status: "PROCESSING" },
                    { $set: { status: "COMPLETED", completedAt: now } })
db.entitlements.upsert(                                   // 🔜 H11 / S-06 (today: insert)
  { tenantId: T, userId: U, targetType: "ENTRY", entryId: E },
  { $set: { status: "ACTIVE", grantType: "PURCHASE", orderId, grantedAt: now, franchiseId } })
// grantedAt is all the version model needs: purchasedVersion = version current at grantedAt (§6a)
// 🔜 N2: publish prepared/completed records to the external rights ledger (async)
```

### 9.12 Purchase Flow — Collection

```js
// 1. Validate target
coll = db.collections.findOne({ tenantId: T, _id: C })
require(coll.isPaid && coll.status == "PUBLISHED" && coll.userId != U)

// 2. Reconcile previous orders for THIS target type (⚠️ H10: today without targetType)
existing = db.orders.find({ tenantId: T, userId: U, targetType: "COLLECTION", collectionId: C })
processExistingOrders(existing)

// 3. Persist the PENDING order (same shape as 9.11, entryId: null, collectionId: C)

// 4. Sign → submit → confirm (same pipeline)

// 5. ONE entitlement for the whole collection — nothing else is stored
db.entitlements.upsert(                                   // 🔜 H11 / S-06
  { tenantId: T, userId: U, targetType: "COLLECTION", collectionId: C },
  { $set: { status: "ACTIVE", grantType: "PURCHASE", orderId, grantedAt: now, franchiseId } })
// No per-entry entitlements and no snapshot: the right = every item record of C with
// removedAt == null or removedAt > grantedAt (§8.6); versions per entry via §6a
```

---

## 10. Code ↔ Design Gaps (what still has to change in `media-store-api`)

The collection-commerce migration that this section used to track is **done**
(`TargetType`, collection pricing, collection purchases, collection access in
the CDN check, favorites locked/unlocked). What remains is the delta between
the code and the access model of §8, owned by the resilience plan. Order of
execution and acceptance criteria live there; this table is the map.

| # | Gap | Where | Plan ref | Depends on a product decision? |
|---|---|---|---|---|
| ~~1~~ | ~~Core indexes exist only as annotations; unique definitions on `orders`/`entitlements` are wrong (§5, §6)~~ — ✅ closed by **H9** (`CoreIndexMigration`, corrected annotations, real-mongod test) | `*Entity`, `CoreIndexMigration` | **H9** | No |
| 2 | `prepare` treats `TIP` orders as purchases (§5.3) | `OrderMongoRepository`, `OrderRepositoryImpl` | **H10** | No |
| 3 | Re-purchase after `REVOKED` silently fails (§6) | `PaymentService.createEntitlement` → upsert | **H11** / S-06 | No |
| 4 | CDN ignores entry status; UI hides buyers in `IN_REVIEW`/`REJECTED`/`SUSPENDED`; free content served in any status; three denials collapsed into one | new `ContentAccessPolicy` used by `MediaEntitlementService` + `PublicEntryService` | **H3** (D-01, D-19, S-03) | Resolved (D-19) |
| 5 | No `moderationBlock`, `moderation_cases`, `disputes`; takedown = status change | entities + new collections + admin-api moderation | **H3** (D-09, D-13, D-15, D-16) | Resolved |
| 6 | Edits on published entries overwrite the live document and send it to `IN_REVIEW` | `EntryUploadService`, `CollectionService`, `ModerationJobService` → `pendingVersion` | **N9** (S-05) | No |
| 7 | Second `FULL` upload on a sold entry: no re-moderation, and two READY FULL assets ⇒ 5xx in six call-sites | `AssetRepository` ("latest"), `EntryUploadService` (409 gate) | **H8** | No |
| 8 | No versions; buyer always sees the live document | `Entry.versions[]/currentVersion`, new `version_choices`, `ContentVersionService` | **N8** (D-14, S-01, S-02, S-07) | Resolved |
| 9 | Collection access requires `PUBLISHED`; removed items are lost; hard delete with buyers allowed | `MediaEntitlementService`, `PublicEntryService`, `CollectionService` (`items[].addedAt/removedAt`) | **H2** (D-11, D-20, S-04) | Resolved (D-20) |
| 10 | Tenant fee silently dropped when the tenant has no wallet (§5.1) | `PaymentService.buildFullSplits` / `buildPublishingSplits` | **H7** | No |
| 11 | Blocked tenant falls back to the default tenant; buyers lose their library | `TenantResolver`, read-only mode | **H1** (D-04) | Resolved |
| 12 | Banned buyer loses the library | `AuthController`, restricted session | **N3** | No |
| 13 | Rights exist only in Mongo; no external ledger | `ledger*` fields on `Order`, publisher, restore job | **N2** (D-03, D-10, D-12) | Resolved |
| 14 | No download endpoint (§8.7) | new endpoint + UI | checklist §12 | No |

**Deferred by decision — do not implement:** `pricingMode` enforcement
(D-17); `GIFT` / `PROMO` / `CREATOR` grants and `expiresAt` evaluation
(D-18); per-version blocking (D-16); version-file GC in P0 (S-02); any
on-chain change (D-03).

---

## 11. Design Decisions & Rationale

| Decision | Rationale |
|----------|-----------|
| **Separate `assets` collection** (not embedded in Entry), **append-only** | Assets are uploaded independently, have their own lifecycle (UPLOADED→READY) and may be many per entry. Append-only storage is also what lets versions (D-14) be pure metadata: the old file is already there. |
| **Denormalized `thumbnailR2Key` / `previewR2Key` / `durationSec` / `hlsR2Prefix` on Entry** | Feed queries read hundreds of entries; no Asset join. 🔜 With versions, these fields are **the projection of `currentVersion`** — a declared invariant, not a second source of truth. |
| **`orders` is a general payment ledger** (`targetType` with 5 values) | One audited Stellar pipeline for purchases, tips and publishing fees. Only `ENTRY`/`COLLECTION` grant rights; the others are context. Orders are **not** unique per target: retries and repeated tips are legitimate. |
| **`orders` separate from `entitlements`** | Order tracks financial state (tx hash, amount, refund); entitlement tracks access. **One entitlement per right**, pointing at its latest completing order; refunding revokes it but the orders persist for accounting. |
| **`stellarTxHash` computed at `prepare`** | The order is persisted with its definitive hash before the buyer signs — there is never a confirmed payment without a trace (basis of N2). No on-chain change is ever needed to link a payment to a purchase (D-03). |
| **Full split snapshotted on the order** | Who was paid what is frozen at purchase time (§5.1). Wallet, fee or royalty changes afterwards cannot alter a settled sale. |
| **Fail-closed for mandatory payees, fail-open for voluntary attributions** | A tenant with a configured fee must be payable or the sale stops (H7); a dead reseller or original attribution is dropped and the buyer pays the same price (D-05). Horizon outages on the pre-sign wallet check stay fail-open: a dead destination fails on-chain without charging (D-06). |
| **`sellerId` / `franchiseId` / `resellerId` / `originalCreatorId` on Order** | Denormalized for dashboards and audit without joins. `Entitlement.franchiseId` is analytics only. |
| **`Decimal128` for amounts** | Native decimal type, maps to `BigDecimal`; no floating-point rounding. |
| **Compound indexes with `tenantId` first; explicit migrations** | Every query is tenant-scoped. Auto-index-creation is off, so every index is created by a named, idempotent migration and nothing else (H9). |
| **Partial unique indexes on `entitlements`** | `entryId`/`collectionId` are `null` for the other target type; a plain compound unique index would collide on `null`. `partialFilterExpression` on `targetType` gives exactly "one entitlement per user × target". |
| **No `collectionId` on Entry** | An entry can appear in multiple collections. The relationship is owned by `collections.items[]`; the index on `items.entryId` supports reverse lookup. |
| **Union by right date for collections (D-11 / D-20 / S-04)** — replaces "Patreon live access" | A right acquired by payment cannot be diminished by the creator's ordinary actions. The collection keeps a membership history (`addedAt`/`removedAt`) and the rule "record still active or removed after my `grantedAt`" gives every buyer exactly what the collection held at any moment since they bought it. Later buyers do not acquire removed items. Nothing per buyer, no backfill, no new index. |
| **Versions chosen per (user, entry), purchased version computed (D-14 / S-07)** | A collection buyer holds N entries that evolve independently and an entry can be reached by several rights. One tiny `version_choices` document per entry **only when the buyer updates**, and a purchased version derived from immutable dates, is the smallest representation that is unambiguous for both paths. |
| **Access is decided by two facts, not a status matrix (S-03)** | `status` = public visibility; `moderationBlock` = blocked for everyone. Everything else is derivable. One nullable sub-document instead of a persisted enum. |
| **Rejection scope inferred from the moderated object (D-13 / S-05)** | Rejecting a pending edit never touches rights; removing live content always does and opens disputes. No "scope" field for moderators to get wrong. |
| **Pending edits are staged, never applied live (N9)** | A published entry stays `PUBLISHED` while an edit is reviewed; a rejected edit changes nothing visible. One slot, no edit history. |
| **Minimal buyer-controlled versions (D-14)** — three pointers, no file copies, no backfill, no GC in P0 | The buyer keeps what they paid for and decides when to update; the platform pays neither for a full VCS nor for a new deletion path over sold content. |
| **Takedown blocks all versions (D-16)** | A past approval is not a guarantee of legitimacy; per-version blocking would add ambiguity and an evasion path. |
| **Disputes are opened automatically, resolved only by an administrator (D-09 / D-15)** | Every affected purchase gets follow-up without inflating admin work: one dispute per purchase, grouped by `moderationCaseId`. Refunds are never automatic — Stellar payments cannot be reversed; compensation is an administrative act. |
| **`tenantId` = subdomain** | The subdomain is immutable and is what every edge and backend component resolves; the Mongo `_id` of `tenants` never leaves admin-api. |
| **Deferred, not deleted: `pricingMode` (D-17), gift/promo/temporary grants (D-18)** | Keeping the enum values costs nothing; implementing them would add surface for features without a current need. |

---

## 12. Implementation Checklist

### ✅ Implemented (verified against code, 2026-10-16)

- [x] `Entry` with pricing, visibility, reseller settings, remix/original fields, spaces, status history
- [x] `Entry` status machine as in §2 (incl. `DELETED` soft delete and restore)
- [x] `Asset` append-only model; HLS transcoding deferred to post-approval
- [x] `Collection` with pricing fields, author denormalization, moderation (`IN_REVIEW` / `REJECTED`), soft delete
- [x] `Order` as general ledger: `TargetType` ×5, attribution fields, full split snapshot, tx hash at prepare, CAS state machine, async submit, Horizon reconciliation watchdog
- [x] `Entitlement` with `targetType` / `collectionId` / `franchiseId`; `isBackedByCompletedOrder` on the CDN path
- [x] Collection purchase flow; collection entitlement check in `MediaEntitlementService` (PUBLISHED collections, live membership)
- [x] `FavoriteService` locked/unlocked for entries and collections
- [x] "Collections containing entry X" query (§9.9)
- [x] Franchise split carve-out (§5.2); tips; publishing orders
- [x] Enums as listed in §14
- [x] UI: locked/unlocked indicators, paywall with "also in collections", collection detail with per-entry state

### 🔜 Planned — owned by `MYCELIUM-RESILIENCE.md` (in execution order)

- [x] **H9** — real Atlas indexes recorded (plan §10.0: none of the core indexes existed); `CoreIndexMigration`; non-unique order indexes; partial unique entitlement indexes; aligned annotations; `CoreIndexMigrationIntegrationTest` (embedded real mongod, no Docker)
- [ ] **H10** — `targetType` filter in previous-order lookups; tip-then-buy tests
- [ ] **N10** — this document's factual sync (done in this revision; keep it true)
- [ ] **H3 + N9** — `ContentAccessPolicy` (§8.3) in CDN and UI; `moderationBlock`; `moderation_cases`; `disputes` trigger with union of access paths; `pendingVersion` staging; free-content rule (D-19); edge-cache invalidation; buyer-facing denial messages
- [ ] **H8 + N8** — repository "latest READY FULL"; 409 gate on FULL outside the versioned path; `versions[]` / `currentVersion`; `version_choices` collection + unique index; per-entry update / undo; computed purchased version; change summary; "absent = v1"; no GC
- [ ] **H2 + H11** — `items[].addedAt/removedAt` membership history (no backfill); union-by-right-date in CDN, UI and "My Purchases"; collection access in any status; 409 on hard delete with buyers; `createEntitlement` upsert
- [ ] **H1**, **N3** — tenant read-only mode; restricted session for sanctioned buyers
- [ ] **H7** — fail-closed tenant payout (`TENANT_PAYOUT_UNCONFIGURED`), also for publishing splits
- [ ] **N2** — external rights ledger (`ledger*` outbox fields, async publisher, restore job) — *no on-chain change*
- [ ] **H5**, **N4**, **N1**, **H4**, **H6**, **N5**, **N6**, **N7** — see plan
- [ ] Download endpoint/button for all accessible content (§8.7)
- [ ] Terms & Conditions page updated from §13

### ⏸ Deferred by decision

- `pricingMode` enforcement and UI (D-17)
- `GIFT` / `PROMO` / `CREATOR` grants, `expiresAt` evaluation, `EXPIRED` status (D-18)
- Per-version moderation (D-16), version-file GC (S-02, P2 optional)

---

## 13. Purchase Terms & User Responsibilities

> Text basis for the Terms & Conditions page and purchase confirmation
> dialogs. It describes the **target** behaviour (§8). Until the planned items
> of §12 ship, the platform honours these terms **manually** where the code
> does not yet (collection removals, re-reviews, takedowns).

### What the user purchases

**Entry purchase.** A perpetual, non-transferable right to access and
download the entry. This right **survives every creator-initiated change**:
unlisting, archiving, deleting, price changes, collection changes and new
versions. The buyer always keeps the version they bought and decides whether
to update to a newer approved version (updating is free and can be undone
once).

**Collection purchase.** A perpetual right to access and download
**everything the collection contained when purchased, plus everything added
to it afterwards** — even if the creator later removes an item, archives,
unpublishes or deletes the collection. Items removed **before** a purchase are
not part of that purchase. One right covers the whole collection; nothing is
granted per item.

**The only exception.** Content that the platform removes for policy or legal
reasons (illegal, stolen, or in justified breach of the rules) is blocked for
everyone — buyers included, in **all** of its versions. In that case the
platform automatically opens a **dispute** for every affected purchase and an
administrator resolves it: compensation, reinstatement, or dismissal. Access
is never removed by the creator, by a rejected edit, by a temporary review of
reported content, by the disappearance of the store, its domain or any
intermediary.

### User responsibilities

- **Backup.** Downloading is available for all accessible content; users are
  encouraged to keep local copies as protection against platform failure.
  Backups are **not** a condition of the right.
- **Policy actions.** In rare cases content may be removed by the platform.
  Users are notified, see the reason in their library, and may receive
  compensation through the dispute process.
- **No resale.** Purchased content is for personal use only and may not be
  redistributed, resold or sublicensed.

### Creator responsibilities

- Creators may change collections at any time (add/remove entries, change
  pricing). **Removing an entry never removes it from existing buyers**; it
  only stops future buyers from acquiring it.
- Unlisting, archiving or deleting content never revokes buyer access.
- Edits to published content are reviewed before they become visible; a
  rejected edit changes nothing for buyers or visitors. Replacing the main
  file of sold content is only possible as a **new version**, which buyers
  adopt voluntarily.
- Creators must not use modifications to materially diminish the value of a
  purchase in bad faith; the platform may intervene.

### Refunds

- Refunds are decided and processed by platform administrators only, as the
  outcome of a dispute. Stellar payments cannot be reversed; a refund is a
  compensating payment.
- Refunding a purchase revokes the corresponding entitlement (entry or whole
  collection). A later re-purchase reactivates it.

### Disputes (platform actions on purchased content)

- When purchased content is removed by the platform, a dispute is opened
  automatically for **each affected purchase** (including purchases made via
  a collection), grouped under the moderation case that caused it.
- Resolution is always an administrator's decision: **refund**, **reinstate**
  (access returns automatically — no change to the purchase record), or
  **dismiss** (violation confirmed, no refund owed).
- Buyers are notified at each stage of the dispute.

---

## 14. Enums Reference (as in code)

| Enum                | Values                                                                           | Used by            |
|---------------------|----------------------------------------------------------------------------------|--------------------|
| `EntryType`         | `VIDEO, AUDIO, IMAGE, RESOURCE, COLLECTION` (`COLLECTION` only as `moderation_jobs` discriminator) | Entry, ModerationJob |
| `EntryStatus`       | `DRAFT, IN_REVIEW, APPROVED, PUBLISHED, REJECTED, SUSPENDED, UNLISTED, ARCHIVED, DELETED` | Entry |
| `MediaVisibility`   | `PUBLIC, PRIVATE`                                                                | Entry, Collection  |
| `MediaKind`         | `THUMBNAIL, PREVIEW, FULL`                                                       | Asset              |
| `AssetStatus`       | `UPLOADED, PROCESSING, READY, FAILED`                                            | Asset              |
| `CollectionType`    | `SERIES, COURSE, ALBUM, BUNDLE, LIST, LIBRARY, CATALOG, VOLUME, ARCHIVE`         | Collection         |
| `CollectionStatus`  | `DRAFT, IN_REVIEW, PUBLISHED, REJECTED, ARCHIVED, DELETED`                       | Collection         |
| `PricingMode`       | `INDIVIDUAL, COLLECTION_ONLY, BOTH` — ⏸ deferred (D-17)                          | Entry              |
| `PriceCurrency`     | `XLM, USD`                                                                       | Entry, Collection (Order stores a String) |
| `SplitRole`         | `PLATFORM, TENANT, SELLER, COLLABORATOR, FRANCHISE, RESELLER, ORIGINAL`          | PaymentSplit       |
| `TargetType`        | `ENTRY, COLLECTION, TIP, PUBLISH_FEE, PUBLISH_FAST_PASS`                         | Order (all), Entitlement (`ENTRY`, `COLLECTION` only), Rating |
| `OrderStatus`       | `PENDING, PROCESSING, COMPLETED, FAILED, EXPIRED, REFUNDED`                      | Order              |
| `GrantType`         | `PURCHASE` ✅ · `GIFT, PROMO, CREATOR` ⏸ deferred (D-18)                          | Entitlement        |
| `EntitlementStatus` | `ACTIVE` ✅ · `REVOKED` (admin refund, design) · `EXPIRED` ⏸ deferred (D-18)      | Entitlement        |
| `FavoriteItemType`  | `ENTRY, COLLECTION`                                                              | Favorite           |
| `ModerationDecision`| `APPROVE, REJECT, MANUAL_QUEUE` (scope is inferred from the moderated object, §8.8) | ModerationJob   |
| `ModerationJobStatus` / `TranscodingJobStatus` | `PENDING, DISPATCHED, PROCESSING, COMPLETED, FAILED, DEAD`   | jobs               |
| `ThumbnailJobStatus`| `PENDING, DISPATCHED, PROCESSING, COMPLETED, SKIPPED, FAILED, DEAD`              | ThumbnailJob       |
| `ThumbnailJobKind`  | `THUMBNAIL, PREVIEW, COVER`                                                      | ThumbnailJob       |
| `ReportReason`      | `SCAM, PHISHING, MALWARE, DOXXING, EXTREME_VIOLENCE, ILLEGAL, CSAM, SELF_HARM, HATE_SPEECH, NSFW, SPAM, IMPERSONATION, COPYRIGHT, STOLEN_CONTENT, DUPLICATE, OFF_TOPIC, OTHER` | Report |
| `ReportSeverity`    | `HIGH, MEDIUM, LOW`                                                              | Report (HIGH ⇒ preventive block, §8.8) |
| `ReportResolution`  | `OPEN, REMOVED, SANCTIONED, CLOSED, DISMISSED`                                   | Report             |
| `RatingProofType`   | `PURCHASE, FREE_VIEW`                                                            | Rating             |
| `FranchiseStatus` (admin-api) | `ACTIVE, DISABLED`                                                     | Franchise          |

Planned (🔜): `moderation_cases.kind` = `TAKEDOWN \| PREVENTIVE`;
`moderation_cases.resolution` = `REINSTATED \| UPHELD`; `disputes.status` =
`OPEN \| REFUNDED \| RESOLVED \| DISMISSED`; `versions[].changeSummary.parts`
= `FILE \| TITLE \| DESCRIPTION \| THUMBNAIL \| PREVIEW \| RESOURCE_TEXT`.
