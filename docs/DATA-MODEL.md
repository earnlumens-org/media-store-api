# EarnLumens — MongoDB Data-Model & States Specification

> **Audience:** Engineering team.  
> **Scope:** Eight MongoDB collections — `entries`, `assets`, `collections`, `orders`, `entitlements`, `favorites`, plus the franchise read-models `franchises` and `franchise_user_bans` (written by admin-api, read here).  
> **Constraints:** Shared multi-tenant documents (every document carries `tenantId`); Stellar payments (XLM native, USD converted at purchase time); purchases target entries OR collections; no relational joins — minimize round-trips.

---

## 1. MongoDB Collections Overview

| Collection     | Purpose                                    | Cardinality                          |
|----------------|--------------------------------------------|--------------------------------------|
| `entries`      | Content listings (the "product")           | 1 per publishable item               |
| `assets`       | Files stored in R2                         | N per entry                          |
| `collections`  | Curated groups of entries (album, course…) | 1 per group                          |
| `orders`       | Purchase records                           | 1 per user × target (entry or coll.) |
| `entitlements` | Granted access rights                      | 1 per user × target (entry or coll.) |
| `favorites`    | User-saved items (entries or collections)  | 1 per user × item                    |
| `franchises`   | Franchise ("beta") storefronts under a tenant (written by admin-api) | 1 per franchise          |
| `franchise_user_bans` | Users barred from creating a franchise under a tenant (admin-api) | 1 per tenant × user |

---

## 2. `entries`

A publishable content item (video, audio, image, or resource/article).

### Fields

| Field              | Type       | Required | Default      | Notes                                                         |
|--------------------|------------|----------|--------------|---------------------------------------------------------------|
| `_id`              | ObjectId   | auto     |              | Exposed as `String id` in Java                                |
| `tenantId`         | String     | ✔        |              |                                                               |
| `userId`           | String     | ✔        |              | Creator's user ID                                             |
| `authorUsername`    | String     |          | `null`       | **Denormalized** from user profile for feed perf              |
| `authorAvatarUrl`  | String     |          | `null`       | **Denormalized** from user profile for feed perf              |
| `title`            | String     | ✔        |              | Max 200 chars                                                 |
| `description`      | String     |          | `null`       | Max 2 000 chars                                               |
| `resourceContent`  | String     |          | `null`       | Rich-text body for `RESOURCE` entries. Max 50 000 chars       |
| `type`             | String     | ✔        |              | Enum: `VIDEO \| AUDIO \| IMAGE \| RESOURCE`                   |
| `status`           | String     | ✔        | `DRAFT`      | Enum: `DRAFT \| IN_REVIEW \| APPROVED \| PUBLISHED \| REJECTED \| SUSPENDED \| UNLISTED \| ARCHIVED` |
| `previousStatus`   | String     |          | `null`       | Status before archiving — used to restore on unarchive        |
| `visibility`       | String     | ✔        | `PUBLIC`     | Enum: `PUBLIC \| PRIVATE`                                     |
| `isPaid`           | Boolean    | ✔        | `false`      |                                                               |
| `priceXlm`         | Decimal128 |          | `null`       | `null` or `0` → free. `BigDecimal` in Java                   |
| `priceUsd`         | Decimal128 |          | `null`       | USD price (converted to XLM at purchase time)                 |
| `priceCurrency`    | String     |          | `null`       | Enum: `XLM \| USD` — currency creator set the price in        |
| `sellerWallet`     | String     |          | `null`       | Stellar public key (`G…`). Required when `isPaid = true`      |
| `paymentSplits`    | [Object]   |          | `[]`         | Embedded `PaymentSplit` sub-docs (see §2.1)                   |
| `pricingMode`      | String     | ✔        | `INDIVIDUAL` | **NEW** — Enum: `INDIVIDUAL \| COLLECTION_ONLY \| BOTH` (see §2.2) |
| `tags`             | [String]   |          | `[]`         | Lowercase, max 10 items                                       |
| `contentLanguage`  | String     |          | `null`       | ISO 639-1 code (`"es"`, `"en"`)                               |
| `thumbnailR2Key`   | String     |          | `null`       | **Denormalized** from THUMBNAIL asset for feed perf           |
| `previewR2Key`     | String     |          | `null`       | **Denormalized** from PREVIEW asset                           |
| `durationSec`      | Integer    |          | `null`       | For video / audio entries                                     |
| `hlsReady`         | Boolean    | ✔        | `false`      | `true` when HLS transcoding completed                         |
| `hlsR2Prefix`      | String     |          | `null`       | R2 key prefix where HLS segments live                         |
| `viewCount`        | Long       | ✔        | `0`          | Total view count                                              |
| `createdAt`        | DateTime   | auto     |              | `@CreatedDate`                                                |
| `updatedAt`        | DateTime   | auto     |              | `@LastModifiedDate`                                           |
| `publishedAt`      | DateTime   |          | `null`       | Set when status → `PUBLISHED`                                 |

#### 2.1 `paymentSplits[]` sub-document

| Field     | Type       | Required | Notes                                                      |
|-----------|------------|----------|------------------------------------------------------------|
| `wallet`  | String     | ✔        | Stellar public key (`G…`)                                  |
| `role`    | String     | ✔        | Enum: `PLATFORM \| TENANT \| SELLER \| COLLABORATOR \| FRANCHISE`  |
| `percent` | Decimal128 | ✔        | Percentage of total (e.g. `10.00` = 10%). Sum must = 100   |

> Currently: `PLATFORM (10%) + SELLER (90%)`. The PLATFORM split is applied dynamically at payment time from environment config. `TENANT` is an optional operator fee. `FRANCHISE` is carved **out of the `TENANT` share** when a sale is made through a franchise (see §5.1) — the final price and every other split are unchanged. `COLLABORATOR` is reserved for future multi-recipient splits.

#### 2.2 `pricingMode` — How an entry can be purchased

| Value             | Meaning                                                                  |
|-------------------|--------------------------------------------------------------------------|
| `INDIVIDUAL`      | Can only be purchased as a standalone entry (default, current behavior)  |
| `COLLECTION_ONLY` | Cannot be purchased individually — only accessible by buying a collection that contains it. Preview page shows "Available in…" with purchasable collections. |
| `BOTH`            | Can be purchased individually OR via a collection purchase               |

> `pricingMode` only applies when `isPaid = true`. Free entries (`isPaid = false`) are always accessible regardless of mode.

### Status State Machine

```
DRAFT ──→ IN_REVIEW ──→ APPROVED ──→ PUBLISHED
  ↑           │                      │   │
  │           ↓                      │   ↓
  ←─────── REJECTED                  │  UNLISTED
  ←──────────────────────────────────┘  (unpublish)
                                        │
                      SUSPENDED ←── (admin)
                      ARCHIVED  ←── (creator, from any status)
```

| Transition               | Trigger              | Side-effects                              |
|--------------------------|----------------------|-------------------------------------------|
| DRAFT → IN_REVIEW       | Creator submits      | Requires ≥ 1 READY asset (FULL)          |
| IN_REVIEW → APPROVED    | Moderator approves   |                                           |
| IN_REVIEW → REJECTED    | Moderator rejects    | Reason stored (separate audit log)        |
| APPROVED → PUBLISHED    | Creator publishes    | Sets `publishedAt = now()`                |
| PUBLISHED → DRAFT       | Creator unpublishes  | Clears `publishedAt`                      |
| PUBLISHED → UNLISTED    | Creator unlists      | Accessible by direct link only            |
| REJECTED → DRAFT        | Creator re-edits     |                                           |
| Any → SUSPENDED         | Admin action         | Saves `previousStatus`                    |
| Any → ARCHIVED          | Creator archives     | Saves `previousStatus`                    |
| ARCHIVED → (previous)   | Creator unarchives   | Restores `previousStatus`                 |

### Indexes

```js
// existing — keep
{ tenantId: 1, _id: 1 }                              // unique, point lookup
{ tenantId: 1, userId: 1 }                            // creator dashboard

// new
{ tenantId: 1, status: 1, publishedAt: -1 }           // public feed (PUBLISHED, newest first)
{ tenantId: 1, status: 1, type: 1, publishedAt: -1 }  // filtered feed by type
```

---

## 3. `assets`

An individual file (thumbnail, preview, or full content) stored in Cloudflare R2.

### Fields

| Field           | Type     | Required | Default    | Notes                                  |
|-----------------|----------|----------|------------|----------------------------------------|
| `_id`           | ObjectId | auto     |            |                                        |
| `tenantId`      | String   | ✔        |            |                                        |
| `entryId`       | String   | ✔        |            | Parent entry reference                 |
| `r2Key`         | String   | ✔        |            | R2 object key (unique per tenant)      |
| `contentType`   | String   | ✔        |            | MIME type (`video/mp4`, `image/webp`)  |
| `fileName`      | String   | ✔        |            | Original upload filename               |
| `fileSizeBytes` | Long     |          | `null`     |                                        |
| `kind`          | String   | ✔        |            | Enum: `THUMBNAIL \| PREVIEW \| FULL`  |
| `status`        | String   | ✔        | `UPLOADED` | Enum: `UPLOADED \| PROCESSING \| READY \| FAILED` |
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
| UPLOADED → PROCESSING  | Background job picks up| Optional, skip if no transcoding  |
| UPLOADED → READY       | Direct (images, small) | For assets that need no processing|
| PROCESSING → READY     | Job completes          |                                   |
| PROCESSING → FAILED    | Job errors             |                                   |

### Indexes

```js
{ tenantId: 1, entryId: 1, kind: 1 }   // lookup assets for an entry by kind
{ tenantId: 1, r2Key: 1 }              // unique — R2 key uniqueness per tenant
```

### Relationship to Entry

- Entry **references** Assets by `entryId` (Assets store the FK).
- Entry **denormalizes** `thumbnailR2Key` and `previewR2Key` for feed queries. These are updated when an Asset of kind THUMBNAIL/PREVIEW reaches status READY.
- The entitlement / Worker flow resolves the FULL asset:
  ```
  Worker →  GET /api/media/entitlements/{entryId}
  Backend → Entry(entryId) → Asset(entryId, kind=FULL, status=READY) → r2Key
  ```

---

## 4. `collections`

A curated group of entries (series, course, album, bundle, etc.). Can be free or paid. **Purchasing a paid collection grants live access to all its current entries** (Patreon model — see §8).

### Fields

| Field              | Type       | Required | Default      | Notes                                                    |
|--------------------|------------|----------|--------------|----------------------------------------------------------|
| `_id`              | ObjectId   | auto     |              |                                                          |
| `tenantId`         | String     | ✔        |              |                                                          |
| `userId`           | String     | ✔        |              | Creator / curator                                        |
| `authorUsername`    | String     |          | `null`       | **Denormalized** from user profile                       |
| `authorAvatarUrl`  | String     |          | `null`       | **Denormalized** from user profile                       |
| `title`            | String     | ✔        |              | Max 200 chars                                            |
| `description`      | String     |          | `null`       | Max 2 000 chars                                          |
| `collectionType`   | String     | ✔        |              | Enum: `SERIES \| COURSE \| ALBUM \| BUNDLE \| LIST`     |
| `coverR2Key`       | String     |          | `null`       | R2 key for cover image                                   |
| `status`           | String     | ✔        | `DRAFT`      | Enum: `DRAFT \| PUBLISHED \| ARCHIVED`                   |
| `visibility`       | String     | ✔        | `PUBLIC`     | **NEW** — Enum: `PUBLIC \| PRIVATE`. Reuses `MediaVisibility` |
| `isPaid`           | Boolean    | ✔        | `false`      | **NEW** — Whether the collection requires purchase        |
| `priceXlm`         | Decimal128 |          | `null`       | **NEW** — XLM price. `null`/`0` → free                   |
| `priceUsd`         | Decimal128 |          | `null`       | **NEW** — USD price (converted to XLM at purchase time)   |
| `priceCurrency`    | String     |          | `null`       | **NEW** — Enum: `XLM \| USD`                             |
| `sellerWallet`     | String     |          | `null`       | **NEW** — Stellar public key. Required when `isPaid = true` |
| `paymentSplits`    | [Object]   |          | `[]`         | **NEW** — Same `PaymentSplit` sub-docs as Entry (§2.1)    |
| `items`            | [Object]   |          | `[]`         | Embedded array (see below)                               |
| `createdAt`        | DateTime   | auto     |              | `@CreatedDate`                                           |
| `updatedAt`        | DateTime   | auto     |              | `@LastModifiedDate`                                      |
| `publishedAt`      | DateTime   |          | `null`       | Set when status → `PUBLISHED`                            |

#### `items[]` sub-document

| Field      | Type    | Required | Notes                        |
|------------|---------|----------|------------------------------|
| `entryId`  | String  | ✔        | Reference to `entries._id`   |
| `position` | Integer | ✔        | 0-based sort order           |

### Status State Machine

```
DRAFT ──→ PUBLISHED ──→ ARCHIVED
  ↑                        │
  ←────────────────────────┘  (unarchive)
```

### Indexes

```js
{ tenantId: 1, _id: 1 }                                 // unique, point lookup
{ tenantId: 1, userId: 1 }                               // creator dashboard
{ tenantId: 1, status: 1, publishedAt: -1 }              // public feed
{ tenantId: 1, "items.entryId": 1 }                      // "collections containing entry X"
```

---

## 5. `orders`

One purchase record per user per target (entry or collection). Stellar payments in XLM (USD-priced items converted at purchase time).

### Fields

| Field              | Type       | Required | Default     | Notes                                                        |
|--------------------|------------|----------|-------------|--------------------------------------------------------------|
| `_id`              | ObjectId   | auto     |             |                                                              |
| `tenantId`         | String     | ✔        |             |                                                              |
| `userId`           | String     | ✔        |             | Buyer's user ID                                              |
| `targetType`       | String     | ✔        |             | **NEW** — Enum: `ENTRY \| COLLECTION`                        |
| `entryId`          | String     |          | `null`      | **NOW NULLABLE** — FK to `entries._id`. Set when `targetType = ENTRY` |
| `collectionId`     | String     |          | `null`      | **NEW** — FK to `collections._id`. Set when `targetType = COLLECTION` |
| `sellerId`         | String     | ✔        |             | Creator's user ID (denormalized)                             |
| `franchiseId`      | String     |          | `null`      | **NEW** — FK to `franchises._id`. Set when the sale was made through a franchise storefront (`/f/<slug>`), else `null` for a direct tenant sale |
| `amountXlm`        | Decimal128 | ✔        |             | Final XLM amount charged (`BigDecimal`)                      |
| `originalAmountUsd`| Decimal128 |          | `null`      | Original USD amount (only for USD-priced items)              |
| `xlmUsdRate`       | Decimal128 |          | `null`      | XLM/USD rate used for conversion                             |
| `priceCurrency`    | String     |          | `null`      | `"XLM"` or `"USD"` — currency the price was set in          |
| `status`           | String     | ✔        | `PENDING`   | Enum: `PENDING \| PROCESSING \| COMPLETED \| FAILED \| EXPIRED \| REFUNDED` |
| `stellarTxHash`    | String     |          | `null`      | Filled on COMPLETED                                          |
| `buyerWallet`      | String     |          | `null`      | Buyer's Stellar public key (`G…`)                            |
| `memo`             | String     |          | `null`      | MEMO text on the Stellar tx                                  |
| `unsignedXdr`      | String     |          | `null`      | Base-64 XDR of unsigned tx envelope                          |
| `signedXdr`        | String     |          | `null`      | Base-64 XDR of signed tx envelope                            |
| `integrityHash`    | String     |          | `null`      | SHA-256 hex of unsigned XDR — tamper detection                |
| `paymentSplits`    | [Object]   |          | `[]`        | Snapshot of `PaymentSplit` at order-creation time             |
| `createdAt`        | DateTime   | auto     |             | `@CreatedDate`                                               |
| `completedAt`      | DateTime   |          | `null`      | Set when status → COMPLETED                                  |
| `expiresAt`        | DateTime   |          | `null`      | Tx timeBounds maxTime                                        |

### Status State Machine

```
PENDING ──→ PROCESSING ──→ COMPLETED ──→ REFUNDED
   │             │
   ↓             ↓
 FAILED        FAILED
   │
   ↓
 EXPIRED
```

| Transition              | Trigger                    | Side-effects                                          |
|-------------------------|----------------------------|-------------------------------------------------------|
| PENDING → PROCESSING   | User signed the tx         | `signedXdr` stored                                    |
| PROCESSING → COMPLETED | Stellar tx confirmed       | Create Entitlement (ACTIVE, PURCHASE, matching `targetType`) |
| PENDING → FAILED       | Tx timeout / horizon error | No entitlement                                        |
| PROCESSING → FAILED    | Tx submission error        | No entitlement                                        |
| PENDING → EXPIRED      | `expiresAt` reached        | No entitlement                                        |
| COMPLETED → REFUNDED   | Admin action               | Revoke matching Entitlement                           |

### Indexes

```js
{ tenantId: 1, userId: 1, targetType: 1, entryId: 1 }       // unique — prevent double entry purchase (sparse on entryId)
{ tenantId: 1, userId: 1, targetType: 1, collectionId: 1 }   // unique — prevent double collection purchase (sparse on collectionId)
{ tenantId: 1, entryId: 1, status: 1 }                       // entry purchase count / status check
{ tenantId: 1, collectionId: 1, status: 1 }                   // collection purchase count / status check
{ tenantId: 1, userId: 1, status: 1, createdAt: -1 }          // buyer's purchase history
{ tenantId: 1, sellerId: 1, status: 1, createdAt: -1 }        // seller's sales dashboard
{ tenantId: 1, franchiseId: 1, status: 1, createdAt: -1 }     // franchise's own sales metrics
{ stellarTxHash: 1 }                                           // tx hash lookup (sparse)
{ status: 1, expiresAt: 1 }                                   // expired order cleanup
```

#### 5.1 Franchise split rule

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
franchise is rejected.

---

## 6. `entitlements`

Access right connecting a user to a target (entry or collection). For the Patreon-style collection model, see §8.

### Fields

| Field          | Type     | Required | Default  | Notes                                                        |
|----------------|----------|----------|----------|--------------------------------------------------------------|
| `_id`          | ObjectId | auto     |          |                                                              |
| `tenantId`     | String   | ✔        |          |                                                              |
| `userId`       | String   | ✔        |          |                                                              |
| `targetType`   | String   | ✔        |          | **NEW** — Enum: `ENTRY \| COLLECTION`                        |
| `entryId`      | String   |          | `null`   | **NOW NULLABLE** — FK to `entries._id`. Set when `targetType = ENTRY` |
| `collectionId` | String   |          | `null`   | **NEW** — FK to `collections._id`. Set when `targetType = COLLECTION` |
| `franchiseId`  | String   |          | `null`   | **NEW** — FK to `franchises._id`. Copied from the granting order; `null` for direct tenant sales |
| `grantType`    | String   | ✔        |          | Enum: `PURCHASE \| GIFT \| PROMO \| CREATOR`                 |
| `orderId`      | String   |          | `null`   | FK to `orders._id` (if grantType = PURCHASE)                 |
| `status`       | String   | ✔        | `ACTIVE` | Enum: `ACTIVE \| REVOKED \| EXPIRED`                         |
| `grantedAt`    | DateTime | auto     |          | `@CreatedDate`                                               |
| `expiresAt`    | DateTime |          | `null`   | For time-limited promos; `null` = forever                    |

### Indexes

```js
{ tenantId: 1, userId: 1, targetType: 1, entryId: 1 }        // unique — one entitlement per user × entry (sparse on entryId)
{ tenantId: 1, userId: 1, targetType: 1, collectionId: 1 }    // unique — one entitlement per user × collection (sparse on collectionId)
{ tenantId: 1, entryId: 1, status: 1 }                        // check entitlement for entry
{ tenantId: 1, collectionId: 1, status: 1 }                    // check entitlement for collection
{ tenantId: 1, userId: 1, status: 1, grantedAt: -1 }           // user's library / "My Purchases"
```

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

No state transitions — insert or delete only (toggle).

### Indexes

```js
{ tenantId: 1, userId: 1, itemId: 1 }          // unique — prevent duplicates
{ tenantId: 1, userId: 1, addedAt: -1 }         // paginated favorites list sorted by most recent
```

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

---

## 8. Access Model — Patreon-Style Live Access

This section defines **how `locked` / `unlocked` are computed** at runtime. These are never stored — they are derived from entitlements, ownership, and collection membership.

### 8.1 Terminology (matches UI code)

| UI Field   | Meaning                                                              |
|------------|----------------------------------------------------------------------|
| `locked`   | Content requires payment and user does NOT have access               |
| `unlocked` | Content requires payment and user DOES have access (purchased)       |
| (neither)  | Content is free — always accessible                                  |

### 8.2 Entry Status & Access — Creator vs Admin/Mod Actions

Entry statuses are split into two categories that determine whether a **purchased** entry remains accessible:

#### Creator-initiated statuses (DO NOT revoke purchased access)

| Status      | Trigger             | Effect on purchasers                                    |
|-------------|---------------------|---------------------------------------------------------|
| `DRAFT`     | Creator unpublishes | Entry disappears from public feeds, but purchasers retain full access (stream, download) via "My Purchases" / direct link |
| `UNLISTED`  | Creator unlists     | Entry hidden from discovery, but purchasers retain full access |
| `ARCHIVED`  | Creator archives    | Same as DRAFT — hidden from public, purchasers keep access |

> **Principle:** A creator cannot retroactively remove access from paying users by changing the entry status. The entitlement is a contract — the user paid, the user keeps access.

#### Admin/Mod-initiated statuses (BLOCK access for everyone)

| Status      | Trigger               | Effect on purchasers                                    |
|-------------|-----------------------|---------------------------------------------------------|
| `SUSPENDED` | Admin suspends        | **Access blocked** for ALL users including purchasers. Entry cannot be streamed or downloaded. Triggers dispute flow (§8.8). |
| `REJECTED`  | Moderator rejects     | **Access blocked** — content failed review. Triggers dispute flow (§8.8). |

> **Principle:** Admin/mod actions override all entitlements. These are content-policy or legal actions — the platform must be able to block harmful content regardless of purchase status.

#### Status classification helper

```java
// Statuses that block access for EVERYONE (including purchasers)
Set<EntryStatus> ADMIN_BLOCKED = Set.of(SUSPENDED, REJECTED);

// Statuses where purchasers retain access (but entry is hidden from public feeds)
Set<EntryStatus> CREATOR_HIDDEN = Set.of(DRAFT, UNLISTED, ARCHIVED);

// Status where entry is fully public
Set<EntryStatus> PUBLIC_VISIBLE = Set.of(PUBLISHED);

// Statuses where entry is in a workflow (not yet accessible to anyone)
Set<EntryStatus> WORKFLOW = Set.of(IN_REVIEW, APPROVED);
```

### 8.3 Entry Access Check

```python
ADMIN_BLOCKED = {SUSPENDED, REJECTED}

def has_entry_access(user_id, entry):
    # 0. Admin/mod-blocked → NO ONE has access (except owner for editing)
    if entry.status in ADMIN_BLOCKED:
        return False
    
    # 1. Owner always has access
    if entry.userId == user_id:
        return True
    
    # 2. Workflow statuses (IN_REVIEW, APPROVED) → not yet accessible
    if entry.status in {IN_REVIEW, APPROVED}:
        return False
    
    # 3. Free entries → accessible if PUBLISHED, UNLISTED, DRAFT, or ARCHIVED
    #    (DRAFT/ARCHIVED free entries are only reachable via direct link)
    if not entry.isPaid:
        return True
    
    # 4. Direct entry entitlement (purchased individually)
    #    Survives creator-initiated status changes (DRAFT, UNLISTED, ARCHIVED)
    if entitlements.exists(userId=user_id, targetType=ENTRY,
                           entryId=entry.id, status=ACTIVE):
        return True
    
    # 5. Collection entitlement — LIVE ACCESS
    #    Find all PUBLISHED collections containing this entry
    collection_ids = collections.find(
        status=PUBLISHED, "items.entryId"=entry.id
    ).map(c => c.id)
    
    if entitlements.exists(userId=user_id, targetType=COLLECTION,
                           collectionId IN collection_ids, status=ACTIVE):
        return True
    
    # 6. Not purchased → only accessible if PUBLISHED
    if entry.status == PUBLISHED:
        return False  # entry is paid but user hasn't purchased
    
    return False
```

### 8.4 Collection Access Check

```python
def has_collection_access(user_id, collection):
    # 1. Owner always has access
    if collection.userId == user_id:
        return True
    
    # 2. Free collections are always accessible
    if not collection.isPaid:
        return True
    
    # 3. Collection entitlement
    if entitlements.exists(userId=user_id, targetType=COLLECTION,
                           collectionId=collection.id, status=ACTIVE):
        return True
    
    return False
```

### 8.5 Computing `locked` / `unlocked`

```python
# For an entry:
locked   = entry.isPaid and not has_entry_access(user_id, entry)
unlocked = entry.isPaid and has_entry_access(user_id, entry) and not is_owner

# For a collection:
locked   = collection.isPaid and not has_collection_access(user_id, collection)
unlocked = collection.isPaid and has_collection_access(user_id, collection) and not is_owner
```

### 8.6 Access Rules Summary (Live Access, No Snapshot)

#### Collection purchase rules

| Scenario                                                  | Result                                        |
|-----------------------------------------------------------|-----------------------------------------------|
| User buys collection → all current entries accessible     | ✔ Access via collection entitlement            |
| Creator adds entry to collection after purchase           | ✔ User gets access (live membership check)     |
| Creator removes entry from collection after purchase      | ✘ User loses access via collection (unless they also purchased the entry individually or it's in another purchased collection) |
| Creator changes free entry to paid inside collection      | ✔ Still accessible via collection entitlement  |
| Creator changes paid entry to free                        | ✔ Accessible to everyone                       |
| Collection refunded                                       | ✘ Entitlement revoked → access lost            |
| Entry is `COLLECTION_ONLY` and in no purchased collection | ✘ Locked — preview shows "Available in…"       |

> **Key principle:** An entitlement is a right to access. For `targetType = COLLECTION`, the right means "access to whatever is currently in this collection." No snapshots, no individual entry entitlements generated. Like a Patreon tier.

#### Individual purchase vs entry status rules

| Scenario                                                    | Has individual purchase? | Result                                  |
|-------------------------------------------------------------|--------------------------|------------------------------------------|
| Creator unpublishes entry (→ DRAFT)                         | ✔ Yes                    | ✔ Access retained. Entry visible in "My Purchases", streamable/downloadable via direct link |
| Creator unlists entry (→ UNLISTED)                          | ✔ Yes                    | ✔ Access retained. Same as above         |
| Creator archives entry (→ ARCHIVED)                         | ✔ Yes                    | ✔ Access retained. Same as above         |
| Creator unpublishes entry (→ DRAFT)                         | ✘ No                     | ✘ Entry hidden. Not accessible           |
| Admin suspends entry (→ SUSPENDED)                          | ✔ Yes                    | ✘ **Access blocked.** Dispute flow triggered (§8.8) |
| Moderator rejects entry (→ REJECTED)                        | ✔ Yes                    | ✘ **Access blocked.** Dispute flow triggered (§8.8) |
| Admin suspends entry (→ SUSPENDED)                          | ✘ No                     | ✘ Entry blocked for everyone             |
| Creator re-publishes previously ARCHIVED entry              | ✔ Yes                    | ✔ Access was never lost — now also visible in public feeds again |
| Creator changes entry price after purchase                  | ✔ Yes                    | ✔ No effect — entitlement is price-independent |

### 8.7 Download Policy

All content that a user has access to (via entry purchase, collection purchase, or free) must be downloadable. The UI provides a **"Download"** button on every accessible entry. This applies to all entry types: video files, audio files, images at full resolution, and resource/article content as PDF export.

> **Rationale:** Since collection access is live and content can be removed, the user's right is to access-and-download while the content is available. Terms & conditions (§13) clarify that users are responsible for keeping copies of purchased content.

### 8.8 Dispute Flow (triggered by admin/mod actions on purchased content)

When an entry with **active PURCHASE entitlements** is moved to `SUSPENDED` or `REJECTED`, the system must trigger a dispute flow:

```python
def on_entry_status_change(entry, old_status, new_status, actor_role):
    ADMIN_BLOCKED = {SUSPENDED, REJECTED}
    
    if new_status not in ADMIN_BLOCKED:
        return  # creator actions — no dispute
    
    # Find all active PURCHASE entitlements for this entry
    affected = db.entitlements.find({
        tenantId: entry.tenantId,
        targetType: "ENTRY",
        entryId: entry.id,
        grantType: "PURCHASE",
        status: "ACTIVE"
    })
    
    if affected.count() == 0:
        return  # no purchasers affected
    
    # Create dispute records for each affected buyer
    for entitlement in affected:
        db.disputes.insertOne({
            tenantId: entry.tenantId,
            entitlementId: entitlement.id,
            orderId: entitlement.orderId,
            userId: entitlement.userId,          # buyer
            entryId: entry.id,
            reason: new_status,                  # SUSPENDED or REJECTED
            status: "OPEN",                      # OPEN → REFUNDED | RESOLVED | DISMISSED
            createdAt: now()
        })
    
    # Also check collection entitlements that grant access to this entry
    collection_ids = db.collections.find(
        { tenantId: entry.tenantId, "items.entryId": entry.id },
        { _id: 1 }
    ).map(c => c._id)
    
    # NOTE: Collection entitlements are NOT revoked — only this specific
    # entry is blocked. Other entries in the collection remain accessible.
    # Dispute records are informational — admin decides resolution.
```

#### Dispute statuses

| Status      | Meaning                                                           |
|-------------|-------------------------------------------------------------------|
| `OPEN`      | Buyer was affected by admin/mod action. Awaiting resolution.      |
| `REFUNDED`  | Admin issued a (partial) refund to the buyer.                     |
| `RESOLVED`  | Entry was reinstated (e.g. SUSPENDED → PUBLISHED). Access restored automatically. |
| `DISMISSED` | Admin reviewed and dismissed (e.g. content violated ToS, no refund). |

> **Implementation status:** The dispute system is **not yet implemented**. Initially, admin handles disputes manually. The `disputes` collection and automated notifications will be added in a future iteration.

> **Key rule:** SUSPENDED/REJECTED entries are blocked at the access-check level (§8.3, step 0). Entitlements are NOT revoked — they remain ACTIVE. If the entry is later reinstated (SUSPENDED → PUBLISHED), access is automatically restored without any entitlement changes.

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

### 9.4 Entitlement Check (Worker flow) — Status-Aware

```js
ADMIN_BLOCKED = ["SUSPENDED", "REJECTED"]
WORKFLOW      = ["IN_REVIEW", "APPROVED"]

// 1. Load entry
entry = db.entries.findOne({ tenantId: T, _id: entryId })

// 2. Admin/mod-blocked → DENY for everyone (including owner in streaming context)
if (ADMIN_BLOCKED.includes(entry.status)) → denied

// 3. Workflow statuses → not yet accessible
if (WORKFLOW.includes(entry.status)) → denied

// 4. Owner always has access
if (entry.userId == requestUserId) → allowed

// 5. Free entry → allowed (even if DRAFT/UNLISTED/ARCHIVED — reachable via direct link)
if (!entry.isPaid) → allowed

// 6. Direct entry entitlement (individual purchase)
//    Survives DRAFT, UNLISTED, ARCHIVED — only blocked by step 2
if db.entitlements.exists({
  tenantId: T, userId: requestUserId,
  targetType: "ENTRY", entryId: entryId, status: "ACTIVE"
}) → allowed

// 7. Collection entitlement (live access)
collectionIds = db.collections.find(
  { tenantId: T, status: "PUBLISHED", "items.entryId": entryId },
  { _id: 1 }
).map(c => c._id)

if (collectionIds.length > 0) {
  if db.entitlements.exists({
    tenantId: T, userId: requestUserId,
    targetType: "COLLECTION", collectionId: { $in: collectionIds },
    status: "ACTIVE"
  }) → allowed
}

// 8. Entry is PUBLISHED and paid but user has no entitlement → denied
// 9. Entry is DRAFT/UNLISTED/ARCHIVED and user has no entitlement → denied
→ denied

// 10. Resolve r2Key (only if allowed)
asset = db.assets.findOne({
  tenantId: T, entryId: entryId, kind: "FULL", status: "READY"
})
return asset.r2Key
```

### 9.5 User's Library ("My Purchases") — Status-Aware

```js
ADMIN_BLOCKED = ["SUSPENDED", "REJECTED"]

// 1. All active entitlements, newest first
db.entitlements.find({
  tenantId: T, userId: U, status: "ACTIVE"
}).sort({ grantedAt: -1 }).skip(offset).limit(pageSize)

// 2. Separate into entry and collection entitlements
entryIds      = results.filter(e => e.targetType == "ENTRY").map(e => e.entryId)
collectionIds = results.filter(e => e.targetType == "COLLECTION").map(e => e.collectionId)

// 3. Hydrate — NO status filter on entries (purchasers see DRAFT/ARCHIVED too)
entries     = db.entries.find({ tenantId: T, _id: { $in: entryIds } })
collections = db.collections.find({ tenantId: T, _id: { $in: collectionIds } })

// 4. Flag admin-blocked entries (UI shows "Content suspended" badge instead of lock)
entries.forEach(entry => {
  entry.adminBlocked = ADMIN_BLOCKED.includes(entry.status)
  entry.creatorHidden = ["DRAFT", "UNLISTED", "ARCHIVED"].includes(entry.status)
  // adminBlocked → greyed out, no stream/download, shows dispute info
  // creatorHidden → fully accessible, small "unlisted" badge (informational)
})
```

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

// 2. Hydrate entries
entryIds = coll.items.sort(position).map(i => i.entryId)
entries = db.entries.find({ tenantId: T, _id: { $in: entryIds } })

// 3. Check user's access per entry (for logged-in users)
//    a. Does user have collection entitlement? → all entries unlocked
hasCollAccess = db.entitlements.exists({
  tenantId: T, userId: U
  targetType: "COLLECTION", collectionId: collId, status: "ACTIVE"
})

//    b. If no collection access, check individual entry entitlements
if (!hasCollAccess) {
  paidEntryIds = entries.filter(e => e.isPaid).map(e => e.id)
  entitledEntryIds = db.entitlements.find({
    tenantId: T, userId: U,
    targetType: "ENTRY", entryId: { $in: paidEntryIds }, status: "ACTIVE"
  }).map(e => e.entryId)
  
  // Also check other collection entitlements that contain these entries
  // (an entry might be unlocked via a different purchased collection)
}

// 4. For each entry, compute locked/unlocked
entries.forEach(entry => {
  if (!entry.isPaid) { locked = false; unlocked = false }
  else if (isOwner || hasCollAccess || entitledEntryIds.includes(entry.id))
    { locked = false; unlocked = true }
  else { locked = true; unlocked = false }
})
```

### 9.9 "Collections containing this entry" (for COLLECTION_ONLY entries)

```js
// Find purchasable collections that contain entry X
db.collections.find({
  tenantId: T,
  status: "PUBLISHED",
  "items.entryId": entryId,
  isPaid: true
})
```

Uses index: `{ tenantId, "items.entryId" }`.  
Returned on the entry preview/paywall page when `pricingMode = COLLECTION_ONLY`.

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
// 1. Validate pricingMode allows individual purchase
entry = db.entries.findOne({ tenantId: T, _id: E })
if (entry.pricingMode == "COLLECTION_ONLY") → reject (400)

// 2. Check no existing order
db.orders.findOne({ tenantId: T, userId: U, targetType: "ENTRY", entryId: E })

// 3. Insert PENDING order
db.orders.insertOne({
  tenantId: T, userId: U, targetType: "ENTRY",
  entryId: E, collectionId: null,
  sellerId: S, amountXlm: price, priceCurrency: cur,
  status: "PENDING", buyerWallet: W, memo: M,
  unsignedXdr: xdr, integrityHash: hash,
  expiresAt: expiry, paymentSplits: splits
})

// 4. User signs tx → status: PROCESSING → submit to Stellar

// 5. On tx success
db.orders.updateOne(
  { _id: orderId, status: "PROCESSING" },
  { $set: { status: "COMPLETED", stellarTxHash: hash, completedAt: now } }
)
db.entitlements.insertOne({
  tenantId: T, userId: U, targetType: "ENTRY",
  entryId: E, collectionId: null,
  grantType: "PURCHASE", orderId: orderId, status: "ACTIVE"
})
```

### 9.12 Purchase Flow — Collection

```js
// 1. Validate collection is purchasable
coll = db.collections.findOne({ tenantId: T, _id: C })
if (!coll.isPaid) → reject (400)

// 2. Check no existing order
db.orders.findOne({ tenantId: T, userId: U, targetType: "COLLECTION", collectionId: C })

// 3. Insert PENDING order
db.orders.insertOne({
  tenantId: T, userId: U, targetType: "COLLECTION",
  entryId: null, collectionId: C,
  sellerId: S, amountXlm: price, priceCurrency: cur,
  status: "PENDING", buyerWallet: W, memo: M,
  unsignedXdr: xdr, integrityHash: hash,
  expiresAt: expiry, paymentSplits: splits
})

// 4. User signs tx → status: PROCESSING → submit to Stellar

// 5. On tx success — ONE entitlement for the whole collection
db.orders.updateOne(
  { _id: orderId, status: "PROCESSING" },
  { $set: { status: "COMPLETED", stellarTxHash: hash, completedAt: now } }
)
db.entitlements.insertOne({
  tenantId: T, userId: U, targetType: "COLLECTION",
  entryId: null, collectionId: C,
  grantType: "PURCHASE", orderId: orderId, status: "ACTIVE"
})
// No individual entry entitlements created — access is live via collection membership
```

---

## 10. Migration — Collection Commerce Changes

This section tracks the delta from the current codebase to the collection commerce model.

### 10.1 Entry

| Action    | Field         | Notes                                                            |
|-----------|---------------|------------------------------------------------------------------|
| **ADD**   | `pricingMode` | `INDIVIDUAL` default. Enum: `INDIVIDUAL \| COLLECTION_ONLY \| BOTH` |

### 10.2 Collection

| Action    | Field              | Notes                                                     |
|-----------|--------------------|-----------------------------------------------------------|
| **ADD**   | `visibility`       | `PUBLIC` default. Reuses `MediaVisibility` enum           |
| **ADD**   | `isPaid`           | `false` default                                           |
| **ADD**   | `priceXlm`         | Nullable. Same semantics as Entry                         |
| **ADD**   | `priceUsd`         | Nullable. Same semantics as Entry                         |
| **ADD**   | `priceCurrency`    | Nullable. Reuses `PriceCurrency` enum                     |
| **ADD**   | `sellerWallet`     | Nullable. Required when `isPaid = true`                   |
| **ADD**   | `paymentSplits`    | Same `PaymentSplit` sub-doc as Entry                      |
| **ADD**   | `authorUsername`    | Denormalized from user profile                            |
| **ADD**   | `authorAvatarUrl`  | Denormalized from user profile                            |

### 10.3 Order

| Action      | Field          | Notes                                                    |
|-------------|----------------|----------------------------------------------------------|
| **ADD**     | `targetType`   | Required. Enum: `ENTRY \| COLLECTION`                    |
| **ADD**     | `collectionId` | Nullable FK to `collections._id`                         |
| **CHANGE**  | `entryId`      | Was `@NotBlank` → now nullable (null when targetType = COLLECTION) |

### 10.4 Entitlement

| Action      | Field          | Notes                                                    |
|-------------|----------------|----------------------------------------------------------|
| **ADD**     | `targetType`   | Required. Enum: `ENTRY \| COLLECTION`                    |
| **ADD**     | `collectionId` | Nullable FK to `collections._id`                         |
| **CHANGE**  | `entryId`      | Was `@NotBlank` → now nullable (null when targetType = COLLECTION) |

### 10.5 New Enums

| Enum           | Values                                | Package location                |
|----------------|---------------------------------------|---------------------------------|
| `PricingMode`  | `INDIVIDUAL, COLLECTION_ONLY, BOTH`   | `domain.media.model`            |
| `TargetType`   | `ENTRY, COLLECTION`                   | `domain.media.model`            |

### 10.6 Index Changes

**OrderEntity** — replace `idx_order_tenant_user_entry` unique index:
```java
// Remove
@CompoundIndex(name = "idx_order_tenant_user_entry",
    def = "{'tenantId': 1, 'userId': 1, 'entryId': 1}", unique = true)

// Add
@CompoundIndex(name = "idx_order_tenant_user_target_entry",
    def = "{'tenantId': 1, 'userId': 1, 'targetType': 1, 'entryId': 1}", unique = true)
@CompoundIndex(name = "idx_order_tenant_user_target_coll",
    def = "{'tenantId': 1, 'userId': 1, 'targetType': 1, 'collectionId': 1}", unique = true)
@CompoundIndex(name = "idx_order_tenant_coll_status",
    def = "{'tenantId': 1, 'collectionId': 1, 'status': 1}")
```

**EntitlementEntity** — replace `idx_tenant_user_entry` unique index:
```java
// Remove
@CompoundIndex(name = "idx_tenant_user_entry",
    def = "{'tenantId': 1, 'userId': 1, 'entryId': 1}", unique = true)

// Add
@CompoundIndex(name = "idx_ent_tenant_user_target_entry",
    def = "{'tenantId': 1, 'userId': 1, 'targetType': 1, 'entryId': 1}", unique = true)
@CompoundIndex(name = "idx_ent_tenant_user_target_coll",
    def = "{'tenantId': 1, 'userId': 1, 'targetType': 1, 'collectionId': 1}", unique = true)
@CompoundIndex(name = "idx_ent_tenant_coll_status",
    def = "{'tenantId': 1, 'collectionId': 1, 'status': 1}")
```

### 10.7 Service Impact

**`MediaEntitlementService.checkEntitlement`** — must add collection-based access check (step 4 in §9.4). This is the most critical change for the CDN Worker flow.

**`FavoriteService.listFavorites`** — currently returns `locked=false, unlocked=false` for all collections. Must compute using same logic as entries: `isPaid && !isOwner && !hasEntitlement`.

**`OrderService` (or equivalent)** — must support `targetType=COLLECTION` orders. Must reject orders for `COLLECTION_ONLY` entries.

---

## 11. Design Decisions & Rationale

| Decision | Rationale |
|----------|-----------|
| **Separate `assets` collection** (not embedded in Entry) | Assets are uploaded independently, have their own lifecycle (UPLOADED→READY), and may be large in number (multi-resolution). Embedding would make Entry documents grow and complicate partial updates. |
| **Denormalized `thumbnailR2Key` / `previewR2Key` on Entry** | Feed queries read hundreds of entries. Avoiding an Asset join per entry keeps feed latency flat. Updated via application code when Asset status → READY. |
| **`orders` separate from `entitlements`**  | Order tracks financial state (tx hash, amount, refund). Entitlement tracks access state. Refunding an order revokes the entitlement but the order record persists for accounting. |
| **`sellerId` on Order** | Denormalized from Entry/Collection to support seller sales dashboard without joining. |
| **`Decimal128` for amounts** | MongoDB native decimal type, maps to `BigDecimal` in Java; avoids floating-point rounding in XLM amounts. |
| **Compound indexes with `tenantId` first** | Every query is scoped to a tenant. Tenant-first compound indexes enable efficient range scans within a tenant partition. |
| **`stellarTxHash` sparse index** | Most orders start PENDING (no hash). Sparse index only indexes documents where the field exists, saving space. |
| **No `collectionId` on Entry** | An entry can appear in multiple collections. The relationship is owned by `collections.items[]`; the index on `items.entryId` supports reverse lookup. |
| **Patreon-style live access (no snapshot)** | Buying a collection gives access to whatever is currently in it. Simpler than snapshot model — one entitlement record, no N individual entitlements to generate. Matches user expectations from Patreon/Spotify. |
| **`pricingMode` on Entry** | Allows creators to restrict individual sales (like "album-only" tracks on iTunes). Enforced at the order creation step — backend rejects orders for `COLLECTION_ONLY` entries. |
| **`targetType` discriminator on Order & Entitlement** | Single collection for both entry and collection purchases. Sparse indexes on `entryId`/`collectionId` keep queries efficient. Avoids separate `collection_orders` / `collection_entitlements` collections. |

---

## 12. Checklist for Implementation

### Already implemented (code matches spec)

- [x] `Entry` domain model with pricing (isPaid, priceXlm, priceUsd, priceCurrency, sellerWallet, paymentSplits)
- [x] `Entry` visibility (MediaVisibility: PUBLIC/PRIVATE)
- [x] `Entry` status state machine (DRAFT → PUBLISHED, with SUSPENDED/UNLISTED/ARCHIVED)
- [x] `Asset` domain model, entity, mapper, repository, adapter
- [x] `Collection` domain model — basic (title, description, type, status, items)
- [x] `Order` domain model with Stellar payment flow (XDR, memo, integrityHash, paymentSplits)
- [x] `Entitlement` domain model with grantType, orderId, expiresAt
- [x] `Favorite` domain model (entries + collections)
- [x] All enums: EntryType, EntryStatus, AssetStatus, CollectionType, CollectionStatus, OrderStatus, GrantType, PriceCurrency, SplitRole
- [x] Compound indexes on EntryEntity, CollectionEntity, OrderEntity, EntitlementEntity
- [x] UI: CollectionCard with locked/unlocked visual indicators
- [x] UI: Preview/paywall page with collection-specific benefits and locked items list
- [x] UI: Collection detail page with locked entry redirect

### New — Collection commerce (Patreon model)

- [ ] Add `PricingMode` enum: `INDIVIDUAL, COLLECTION_ONLY, BOTH`
- [ ] Add `pricingMode` field to `Entry` / `EntryEntity`
- [ ] Add `TargetType` enum: `ENTRY, COLLECTION`
- [ ] Add pricing fields to `Collection` / `CollectionEntity`: visibility, isPaid, priceXlm, priceUsd, priceCurrency, sellerWallet, paymentSplits
- [ ] Add denormalized author fields to `Collection` / `CollectionEntity`: authorUsername, authorAvatarUrl
- [ ] Add `targetType`, `collectionId` to `Order` / `OrderEntity`; make `entryId` nullable
- [ ] Add `targetType`, `collectionId` to `Entitlement` / `EntitlementEntity`; make `entryId` nullable
- [ ] Update `@CompoundIndex` on `OrderEntity` (add targetType to unique indexes, add collectionId indexes)
- [ ] Update `@CompoundIndex` on `EntitlementEntity` (add targetType to unique indexes, add collectionId indexes)
- [ ] Implement collection purchase flow in `OrderService` (validate isPaid, create order with targetType=COLLECTION)
- [ ] Update `MediaEntitlementService.checkEntitlement` — add collection entitlement check (step 4 in §9.4)
- [ ] Update `FavoriteService` — compute locked/unlocked for collections (same logic as entries: isPaid + entitlement check)
- [ ] Create endpoint: "purchasable collections containing entry X" (§9.9) — needed for COLLECTION_ONLY preview page
- [ ] Update collection detail endpoint — hydrate entries with per-entry locked/unlocked status (§9.8)
- [ ] Reject individual purchase orders for `COLLECTION_ONLY` entries (§9.11, step 1)
- [ ] Add download endpoint/button for all accessible content (video, audio, image, resource-as-PDF)
- [ ] Update Terms & Conditions page with purchase terms (§13)
- [ ] Implement status-aware access check — creator-initiated statuses (DRAFT, UNLISTED, ARCHIVED) do not revoke purchased access (§8.2, §8.3, §9.4)
- [ ] Implement dispute flow trigger — when admin/mod sets SUSPENDED/REJECTED on an entry with active PURCHASE entitlements (§8.8)
- [ ] Add `disputes` MongoDB collection (future iteration — initially manual admin handling)
- [ ] Update "My Purchases" (§9.5) to show all purchased entries regardless of creator status, with `adminBlocked` / `creatorHidden` badges

---

## 13. Purchase Terms & User Responsibilities

> This section defines the terms to be displayed on the Terms & Conditions page and purchase confirmation dialogs.

### What the user purchases

**Entry purchase:** The user acquires a perpetual, non-transferable right to access and download the entry content. This right **survives creator-initiated changes** — if the creator unpublishes, unlists, or archives the entry, the buyer retains full access (streaming, download). The entitlement is never affected by price changes, collection membership changes, or creator edits to the entry.

**The only exception:** Platform admin or moderator actions (`SUSPENDED`, `REJECTED`) override all entitlements and block access. These are content-policy or legal actions. When this happens, affected buyers are notified and a dispute resolution process is initiated (see §8.8).

**Collection purchase (Patreon model):** The user acquires a perpetual right to access and download ALL content currently included in the collection. This is a **live access** right:

- Content **added** to the collection after purchase → the user gains access.
- Content **removed** from the collection after purchase → the user loses access to that specific content via this collection (unless they hold a separate entry entitlement or access through another purchased collection).
- The collection entitlement itself is perpetual and cannot be revoked except by admin refund.
- If a specific entry within a purchased collection is `SUSPENDED`/`REJECTED` by admin/mod, **only that entry** is blocked. The rest of the collection remains accessible.

### User responsibilities

- **Backup:** The user is responsible for downloading and keeping local copies of all purchased content. The platform provides download functionality for all accessible content (video, audio, image, resource).
- **Live access risk (collections):** Since collection content is managed by the creator and access is live, users are advised to download content promptly after purchase.
- **Admin actions:** In rare cases, content may be suspended by the platform for policy violations. Users will be notified and may receive a refund through the dispute process.
- **No resale:** Purchased content is for personal use only and may not be redistributed, resold, or sublicensed.

### Creator responsibilities

- Creators may modify collection contents at any time (add/remove entries, change pricing).
- Removing an entry from a collection removes access for collection buyers (but not for users who purchased the entry individually).
- **Unpublishing, unlisting, or archiving an individually purchased entry does NOT revoke buyer access.** Creators cannot use status changes to circumvent purchases.
- Creators should not use collection modifications to materially diminish the value of a purchase in bad faith. The platform reserves the right to intervene in disputes.

### Refunds

- Refunds are processed by platform admin only.
- Refunding an entry purchase revokes the entry entitlement.
- Refunding a collection purchase revokes the collection entitlement (access to all entries via that collection is lost).

### Disputes (admin/mod actions on purchased content)

- When an entry with active purchase entitlements is `SUSPENDED` or `REJECTED`, the platform creates a **dispute record** for each affected buyer.
- Disputes are resolved by admin: **refund**, **reinstate content**, or **dismiss** (e.g. content violated ToS, no refund owed).
- If the entry is reinstated (status returns to `PUBLISHED`), access is automatically restored — no entitlement changes needed.
- Buyers are notified at each stage of the dispute.

---

## 14. Enums Reference

| Enum                | Values                                                                           | Used by            |
|---------------------|----------------------------------------------------------------------------------|--------------------|
| `EntryType`         | `VIDEO, AUDIO, IMAGE, RESOURCE`                                                 | Entry              |
| `EntryStatus`       | `DRAFT, IN_REVIEW, APPROVED, PUBLISHED, REJECTED, SUSPENDED, UNLISTED, ARCHIVED`| Entry              |
| `MediaVisibility`   | `PUBLIC, PRIVATE`                                                                | Entry, Collection  |
| `MediaKind`         | `THUMBNAIL, PREVIEW, FULL`                                                       | Asset              |
| `AssetStatus`       | `UPLOADED, PROCESSING, READY, FAILED`                                            | Asset              |
| `CollectionType`    | `SERIES, COURSE, ALBUM, BUNDLE, LIST`                                            | Collection         |
| `CollectionStatus`  | `DRAFT, PUBLISHED, ARCHIVED`                                                     | Collection         |
| `PricingMode`       | `INDIVIDUAL, COLLECTION_ONLY, BOTH`                                              | Entry (**NEW**)    |
| `PriceCurrency`     | `XLM, USD`                                                                       | Entry, Collection, Order |
| `SplitRole`         | `PLATFORM, SELLER, COLLABORATOR`                                                 | PaymentSplit       |
| `TargetType`        | `ENTRY, COLLECTION`                                                              | Order, Entitlement (**NEW**) |
| `OrderStatus`       | `PENDING, PROCESSING, COMPLETED, FAILED, EXPIRED, REFUNDED`                     | Order              |
| `GrantType`         | `PURCHASE, GIFT, PROMO, CREATOR`                                                 | Entitlement        |
| `EntitlementStatus` | `ACTIVE, REVOKED, EXPIRED`                                                       | Entitlement        |
| `FavoriteItemType`  | `ENTRY, COLLECTION`                                                              | Favorite           |
