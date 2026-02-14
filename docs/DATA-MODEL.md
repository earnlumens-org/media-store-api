# EarnLumens — MongoDB Data-Model & States Specification

> **Audience:** Engineering team.  
> **Scope:** Five collections — `entries`, `assets`, `collections`, `orders`, `entitlements`.  
> **Constraints:** Shared multi-tenant collections (every document carries `tenantId`); XLM-only payments; 1 purchase = 1 entry; no relational joins — minimize round-trips.

---

## 1. Collections Overview

| Collection     | Purpose                           | Cardinality            |
|----------------|-----------------------------------|------------------------|
| `entries`      | Content listings (the "product")  | 1 per publishable item |
| `assets`       | Files stored in R2                | N per entry            |
| `collections`  | Curated groups of entries         | 1 per group            |
| `orders`       | Purchase records                  | 1 per user × entry     |
| `entitlements` | Granted access rights             | 1 per user × entry     |

---

## 2. `entries`

A publishable content item (video, audio, image, or article).

### Fields

| Field              | Type       | Required | Default   | Notes                                                   |
|--------------------|------------|----------|-----------|---------------------------------------------------------|
| `_id`              | ObjectId   | auto     |           | Exposed as `String id` in Java                          |
| `tenantId`         | String     | ✔        |           |                                                         |
| `userId`           | String     | ✔        |           | Creator's user ID                                       |
| `title`            | String     | ✔        |           | Max 200 chars                                           |
| `description`      | String     |          | `null`    | Max 2 000 chars                                         |
| `type`             | String     | ✔        |           | Enum: `VIDEO \| AUDIO \| IMAGE \| ARTICLE`              |
| `status`           | String     | ✔        | `DRAFT`   | Enum: `DRAFT \| IN_REVIEW \| APPROVED \| PUBLISHED \| REJECTED` |
| `visibility`       | String     | ✔        | `PUBLIC`  | Enum: `PUBLIC \| PRIVATE`                               |
| `isPaid`           | Boolean    | ✔        | `false`   |                                                         |
| `priceXlm`         | Decimal128 |          | `null`    | `null` or `0` → free. Stored as `BigDecimal` in Java    |
| `tags`             | [String]   |          | `[]`      | Lowercase, max 10 items                                 |
| `thumbnailR2Key`   | String     |          | `null`    | **Denormalized** from the THUMBNAIL asset for feed perf |
| `previewR2Key`     | String     |          | `null`    | **Denormalized** from the PREVIEW asset                 |
| `durationSec`      | Integer    |          | `null`    | For video / audio entries                               |
| `createdAt`        | DateTime   | auto     |           | `@CreatedDate`                                          |
| `updatedAt`        | DateTime   | auto     |           | `@LastModifiedDate`                                     |
| `publishedAt`      | DateTime   |          | `null`    | Set when status → `PUBLISHED`                           |

### Status State Machine

```
DRAFT ──→ IN_REVIEW ──→ APPROVED ──→ PUBLISHED
  ↑           │                          │
  │           ↓                          │
  ←─────── REJECTED                      │
  ←──────────────────────────────────────┘  (unpublish)
```

| Transition               | Trigger            | Side-effects                        |
|--------------------------|--------------------|-------------------------------------|
| DRAFT → IN_REVIEW       | Creator submits    | Requires ≥ 1 READY asset (FULL)    |
| IN_REVIEW → APPROVED    | Moderator approves |                                     |
| IN_REVIEW → REJECTED    | Moderator rejects  | Reason stored (separate audit log)  |
| APPROVED → PUBLISHED    | Creator publishes  | Sets `publishedAt = now()`          |
| PUBLISHED → DRAFT       | Creator unpublishes| Clears `publishedAt`                |
| REJECTED → DRAFT        | Creator re-edits   |                                     |

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

A curated group of entries (series, course, album, etc.).

### Fields

| Field            | Type       | Required | Default  | Notes                                           |
|------------------|------------|----------|----------|-------------------------------------------------|
| `_id`            | ObjectId   | auto     |          |                                                 |
| `tenantId`       | String     | ✔        |          |                                                 |
| `userId`         | String     | ✔        |          | Creator / curator                               |
| `title`          | String     | ✔        |          | Max 200 chars                                   |
| `description`    | String     |          | `null`   | Max 2 000 chars                                 |
| `collectionType` | String     | ✔        |          | Enum: `SERIES \| COURSE \| ALBUM \| BUNDLE \| LIST` |
| `coverR2Key`     | String     |          | `null`   | R2 key for cover image                          |
| `status`         | String     | ✔        | `DRAFT`  | Enum: `DRAFT \| PUBLISHED \| ARCHIVED`          |
| `items`          | [Object]   |          | `[]`     | Embedded array (see below)                      |
| `createdAt`      | DateTime   | auto     |          | `@CreatedDate`                                  |
| `updatedAt`      | DateTime   | auto     |          | `@LastModifiedDate`                             |
| `publishedAt`    | DateTime   |          | `null`   | Set when status → `PUBLISHED`                   |

#### `items[]` sub-document

| Field      | Type    | Required | Notes                        |
|------------|---------|----------|------------------------------|
| `entryId`  | String  | ✔        | Reference to `entries._id`   |
| `position` | Integer | ✔        | 0-based sort order           |

### Indexes

```js
{ tenantId: 1, _id: 1 }                                 // unique, point lookup
{ tenantId: 1, userId: 1 }                               // creator dashboard
{ tenantId: 1, status: 1, publishedAt: -1 }              // public feed
{ tenantId: 1, "items.entryId": 1 }                      // "collections containing entry X"
```

---

## 5. `orders`

One purchase record per user per entry. XLM only.

### Fields

| Field            | Type       | Required | Default   | Notes                                     |
|------------------|------------|----------|-----------|--------------------------------------------|
| `_id`            | ObjectId   | auto     |           |                                            |
| `tenantId`       | String     | ✔        |           |                                            |
| `userId`         | String     | ✔        |           | Buyer's user ID                            |
| `entryId`        | String     | ✔        |           | Purchased entry                            |
| `sellerId`       | String     | ✔        |           | Entry creator's user ID (denormalized)     |
| `amountXlm`      | Decimal128 | ✔        |           | Price at time of purchase (`BigDecimal`)   |
| `status`         | String     | ✔        | `PENDING` | Enum: `PENDING \| COMPLETED \| FAILED \| REFUNDED` |
| `stellarTxHash`  | String     |          | `null`    | Filled on COMPLETED                        |
| `createdAt`      | DateTime   | auto     |           | `@CreatedDate`                             |
| `completedAt`    | DateTime   |          | `null`    | Set when status → COMPLETED                |

### Status State Machine

```
PENDING ──→ COMPLETED ──→ REFUNDED
   │
   ↓
 FAILED
```

| Transition           | Trigger                    | Side-effects                     |
|----------------------|----------------------------|----------------------------------|
| PENDING → COMPLETED | Stellar tx confirmed       | Create Entitlement(ACTIVE, PURCHASE) |
| PENDING → FAILED    | Tx timeout / horizon error | No entitlement                   |
| COMPLETED → REFUNDED| Admin action               | Revoke Entitlement               |

### Indexes

```js
{ tenantId: 1, userId: 1, entryId: 1 }                  // unique — prevent double purchase
{ tenantId: 1, entryId: 1, status: 1 }                   // entry purchase count / status check
{ tenantId: 1, userId: 1, status: 1, createdAt: -1 }     // buyer's purchase history
{ tenantId: 1, sellerId: 1, status: 1, createdAt: -1 }   // seller's sales dashboard
{ stellarTxHash: 1 }                                      // tx hash lookup (sparse)
```

---

## 6. `entitlements`

Access right connecting a user to an entry. Already exists — additions marked **NEW**.

### Fields

| Field       | Type     | Required | Default  | Notes                                               |
|-------------|----------|----------|----------|------------------------------------------------------|
| `_id`       | ObjectId | auto     |          |                                                      |
| `tenantId`  | String   | ✔        |          |                                                      |
| `userId`    | String   | ✔        |          |                                                      |
| `entryId`   | String   | ✔        |          |                                                      |
| `grantType` | String   | ✔        |          | **NEW** — Enum: `PURCHASE \| GIFT \| PROMO \| CREATOR` |
| `orderId`   | String   |          | `null`   | **NEW** — FK to `orders._id` (if grantType=PURCHASE)  |
| `status`    | String   | ✔        | `ACTIVE` | Existing — `ACTIVE \| REVOKED \| EXPIRED`            |
| `grantedAt` | DateTime | auto     |          | Existing — `@CreatedDate`                            |
| `expiresAt` | DateTime |          | `null`   | **NEW** — For time-limited promos; `null` = forever   |

### Indexes

```js
// existing — keep
{ tenantId: 1, userId: 1, entryId: 1 }                  // unique
{ tenantId: 1, entryId: 1, status: 1 }                   // check entitlement for entry

// new
{ tenantId: 1, userId: 1, status: 1, grantedAt: -1 }     // user's library / "My Purchases"
```

---

## 7. Query Patterns

### 7.1 Public Feed (paginated, newest first)

```js
db.entries.find({
  tenantId: T,
  status: "PUBLISHED",
  visibility: "PUBLIC"
}).sort({ publishedAt: -1 }).skip(offset).limit(pageSize)
```

Uses index: `{ tenantId, status, publishedAt }`.  
Returns `thumbnailR2Key` for feed cards — no Asset join needed.

### 7.2 Filtered Feed by Type

```js
db.entries.find({
  tenantId: T,
  status: "PUBLISHED",
  visibility: "PUBLIC",
  type: "VIDEO"
}).sort({ publishedAt: -1 }).skip(offset).limit(pageSize)
```

Uses index: `{ tenantId, status, type, publishedAt }`.

### 7.3 Entry Detail

```js
// 1. Entry document
db.entries.findOne({ tenantId: T, _id: entryId })

// 2. All assets for this entry
db.assets.find({ tenantId: T, entryId: entryId })
```

Two queries. Asset results give the UI the preview + full asset metadata.

### 7.4 Entitlement Check (Worker flow)

```js
// 1. Entry + owner check
entry = db.entries.findOne({ tenantId: T, _id: entryId })
if (entry.userId == requestUserId) → allowed

// 2. Entitlement check
db.entitlements.exists({
  tenantId: T, userId: requestUserId, entryId: entryId, status: "ACTIVE"
})

// 3. Resolve r2Key (only if allowed)
asset = db.assets.findOne({
  tenantId: T, entryId: entryId, kind: "FULL", status: "READY"
})
return asset.r2Key
```

Three indexed point-lookups. Fast.

### 7.5 User's Library ("My Purchases")

```js
// 1. Active entitlements, newest first
db.entitlements.find({
  tenantId: T, userId: U, status: "ACTIVE"
}).sort({ grantedAt: -1 }).skip(offset).limit(pageSize)

// 2. Hydrate entries (single $in query)
entryIds = results.map(e => e.entryId)
db.entries.find({ tenantId: T, _id: { $in: entryIds } })
```

Two queries. Index: `{ tenantId, userId, status, grantedAt }`.

### 7.6 Creator Dashboard — My Entries

```js
db.entries.find({
  tenantId: T, userId: creatorId
}).sort({ createdAt: -1 }).skip(offset).limit(pageSize)
```

Uses index: `{ tenantId, userId }`.

### 7.7 Creator Dashboard — My Sales

```js
db.orders.find({
  tenantId: T, sellerId: creatorId, status: "COMPLETED"
}).sort({ createdAt: -1 }).skip(offset).limit(pageSize)
```

Uses index: `{ tenantId, sellerId, status, createdAt }`.

### 7.8 Collection Detail

```js
// 1. Collection document (includes items[])
coll = db.collections.findOne({ tenantId: T, _id: collId })

// 2. Hydrate entries
entryIds = coll.items.sort(position).map(i => i.entryId)
db.entries.find({ tenantId: T, _id: { $in: entryIds } })
```

Two queries. Client re-sorts entries by `items[].position`.

### 7.9 Purchase Flow

```js
// 1. Check no existing order (unique index enforces this too)
db.orders.findOne({ tenantId: T, userId: U, entryId: E })

// 2. Insert PENDING order
db.orders.insertOne({ tenantId: T, userId: U, entryId: E, sellerId: S,
                       amountXlm: price, status: "PENDING" })

// 3. Submit Stellar tx ...

// 4. On tx success — atomic update + insert
db.orders.updateOne(
  { _id: orderId, status: "PENDING" },
  { $set: { status: "COMPLETED", stellarTxHash: hash, completedAt: now } }
)
db.entitlements.insertOne({
  tenantId: T, userId: U, entryId: E,
  grantType: "PURCHASE", orderId: orderId, status: "ACTIVE"
})
```

---

## 8. Migration from Step 1 Models

The Step 1 `Entry` and `Entitlement` models were intentionally minimal. Below is the diff to evolve them.

### 8.1 Entry

| Action  | Field                           | Notes                                              |
|---------|---------------------------------|----------------------------------------------------|
| **ADD** | `title`, `description`          | Required for listings                              |
| **ADD** | `type`                          | `VIDEO \| AUDIO \| IMAGE \| ARTICLE`               |
| **ADD** | `status`                        | `DRAFT` default; replaces implicit "exists = live"  |
| **ADD** | `isPaid`, `priceXlm`            | Monetization fields                                |
| **ADD** | `tags`                          | Discovery                                          |
| **ADD** | `thumbnailR2Key`, `previewR2Key`| Denormalized from Asset                            |
| **ADD** | `durationSec`                   | Video/audio length                                 |
| **ADD** | `updatedAt`, `publishedAt`      | Lifecycle timestamps                               |
| **DROP**| `r2Key`                         | Moved to Asset                                     |
| **DROP**| `contentType`                   | Moved to Asset                                     |
| **DROP**| `fileName`                      | Moved to Asset                                     |
| **DROP**| `kind` (MediaKind)              | Moved to Asset.kind                                |
| **DROP**| `fileSizeBytes`                 | Moved to Asset                                     |

> **MediaKind enum** (`THUMBNAIL | PREVIEW | FULL`) stays — it is now used by Asset instead of Entry.  
> **MediaVisibility enum** (`PUBLIC | PRIVATE`) stays on Entry.

### 8.2 Entitlement

| Action  | Field        | Notes                               |
|---------|--------------|-------------------------------------|
| **ADD** | `grantType`  | `PURCHASE \| GIFT \| PROMO \| CREATOR` |
| **ADD** | `orderId`    | Nullable FK to `orders._id`          |
| **ADD** | `expiresAt`  | Nullable, for time-limited grants    |

### 8.3 New Enums

| Enum               | Values                                                       |
|--------------------|--------------------------------------------------------------|
| `EntryType`        | `VIDEO, AUDIO, IMAGE, ARTICLE`                               |
| `EntryStatus`      | `DRAFT, IN_REVIEW, APPROVED, PUBLISHED, REJECTED`            |
| `AssetStatus`      | `UPLOADED, PROCESSING, READY, FAILED`                        |
| `CollectionType`   | `SERIES, COURSE, ALBUM, BUNDLE, LIST`                        |
| `CollectionStatus` | `DRAFT, PUBLISHED, ARCHIVED`                                 |
| `OrderStatus`      | `PENDING, COMPLETED, FAILED, REFUNDED`                       |
| `GrantType`        | `PURCHASE, GIFT, PROMO, CREATOR`                             |

Existing enums unchanged: `MediaKind`, `MediaVisibility`, `EntitlementStatus`.

### 8.4 New Domain Repository Methods

```java
// EntryRepository — add:
Page<Entry> findPublishedFeed(String tenantId, int offset, int limit);
Page<Entry> findByTenantIdAndUserId(String tenantId, String userId, int offset, int limit);

// AssetRepository (new)
Optional<Asset> findByTenantIdAndEntryIdAndKindAndStatus(
        String tenantId, String entryId, MediaKind kind, AssetStatus status);
List<Asset> findByTenantIdAndEntryId(String tenantId, String entryId);
Asset save(Asset asset);

// CollectionRepository (new)
Optional<Collection> findByTenantIdAndId(String tenantId, String id);
Page<Collection> findPublishedFeed(String tenantId, int offset, int limit);
Collection save(Collection collection);

// OrderRepository (new)
Optional<Order> findByTenantIdAndUserIdAndEntryId(String tenantId, String userId, String entryId);
Order save(Order order);

// EntitlementRepository — add:
Page<Entitlement> findUserLibrary(String tenantId, String userId, int offset, int limit);
```

### 8.5 Service Impact — MediaEntitlementService

Current `checkEntitlement` resolves `r2Key` directly from Entry. After migration it must resolve via Asset:

```java
// Before
entry.getR2Key()

// After
Asset fullAsset = assetRepository
    .findByTenantIdAndEntryIdAndKindAndStatus(
        tenantId, entryId, MediaKind.FULL, AssetStatus.READY)
    .orElseThrow();
fullAsset.getR2Key()
```

---

## 9. Design Decisions & Rationale

| Decision | Rationale |
|----------|-----------|
| **Separate `assets` collection** (not embedded in Entry) | Assets are uploaded independently, have their own lifecycle (UPLOADED→READY), and may be large in number (multi-resolution). Embedding would make Entry documents grow and complicate partial updates. |
| **Denormalized `thumbnailR2Key` / `previewR2Key` on Entry** | Feed queries read hundreds of entries. Avoiding an Asset join per entry keeps feed latency flat. Updated via application code when Asset status → READY. |
| **`orders` separate from `entitlements`**  | Order tracks financial state (tx hash, amount, refund). Entitlement tracks access state. Refunding an order revokes the entitlement but the order record persists for accounting. |
| **`sellerId` on Order** | Denormalized from Entry to support seller sales dashboard without joining entries. |
| **`Decimal128` for amounts** | MongoDB native decimal type, maps to `BigDecimal` in Java; avoids floating-point rounding in XLM amounts. |
| **Compound indexes with `tenantId` first** | Every query is scoped to a tenant. Tenant-first compound indexes enable efficient range scans within a tenant partition. |
| **`stellarTxHash` sparse index** | Most orders start PENDING (no hash). Sparse index only indexes documents where the field exists, saving space. |
| **No `collectionId` on Entry** | An entry can appear in multiple collections. The relationship is owned by `collections.items[]`; the index on `items.entryId` supports reverse lookup. |

---

## 10. Checklist for Implementation

- [ ] Refactor `Entry` domain model — drop file fields, add new fields per §8.1
- [ ] Create `Asset` domain model, entity, mapper, repository, adapter
- [ ] Create `Collection` domain model + infrastructure layer
- [ ] Create `Order` domain model + infrastructure layer
- [ ] Expand `Entitlement` — add `grantType`, `orderId`, `expiresAt`
- [ ] Add new enums: `EntryType`, `EntryStatus`, `AssetStatus`, `CollectionType`, `CollectionStatus`, `OrderStatus`, `GrantType`
- [ ] Create/update `@CompoundIndex` annotations on all entities per index specs
- [ ] Update `MediaEntitlementService.checkEntitlement` to resolve r2Key via Asset
- [ ] Update `MediaEntitlementController` tests
- [ ] Update CDN Worker (no changes needed — it still receives `r2Key` from backend)
- [ ] Update `EntryMockController` to match new Entry shape (or replace with real endpoints)
