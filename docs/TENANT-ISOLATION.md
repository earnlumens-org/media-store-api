# Tenant Isolation — Implementation & Developer Guide

> **Criticality:** **CRITICAL** — A tenant isolation failure means one customer can see, modify, or delete another customer's data. Every line of code that touches the database must respect tenant boundaries. There are no exceptions.  
> **Audience:** All engineers contributing to `media-store-api`.  
> **Last updated:** March 2026.

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
   - [TenantFilter](#tenantfilter)
   - [TenantResolver](#tenantresolver)
   - [TenantContext](#tenantcontext)
   - [MissingTenantException](#missingtenantexception)
3. [Repository Rules](#repository-rules)
   - [Tenant-Scoped Repositories](#tenant-scoped-repositories)
   - [Global Repositories (Exempt)](#global-repositories-exempt)
   - [Cross-Tenant Operations](#cross-tenant-operations)
4. [Automated Enforcement — TenantIsolationArchTest](#automated-enforcement--tenantisolationarchtest)
5. [Developer Guide — Adding a New Feature](#developer-guide--adding-a-new-feature)
   - [Step 1: Domain Repository Interface](#step-1-domain-repository-interface)
   - [Step 2: MongoDB Interface](#step-2-mongodb-interface)
   - [Step 3: Repository Adapter](#step-3-repository-adapter)
   - [Step 4: Application Service](#step-4-application-service)
   - [Step 5: Controller](#step-5-controller)
   - [Step 6: Tests](#step-6-tests)
6. [Developer Guide — Adding a New Method to an Existing Repository](#developer-guide--adding-a-new-method-to-an-existing-repository)
7. [Developer Guide — Background Jobs / Schedulers](#developer-guide--background-jobs--schedulers)
8. [Common Mistakes & How They Are Prevented](#common-mistakes--how-they-are-prevented)
9. [Quick Reference Cheatsheet](#quick-reference-cheatsheet)

---

## Overview

All tenant-scoped MongoDB collections share a `tenantId` field. Every document is owned by exactly one tenant. The system guarantees that:

1. **No query ever runs without a tenant filter** (enforced at the repository interface level).
2. **No HTTP request goes unresolved** — `TenantFilter` runs at the highest precedence and sets the tenant before any business logic executes.
3. **No developer can accidentally bypass isolation** — `TenantIsolationArchTest` scans every repository interface at build time and fails if any method lacks a `tenantId` parameter.

```
HTTP Request
    │
    ▼
┌──────────────┐   ①  Extracts tenant from Host header
│ TenantFilter │──────► TenantResolver.resolve(request)
└──────┬───────┘
       │           ②  Stores in ThreadLocal
       ▼
┌──────────────┐
│TenantContext │   TenantContext.set("earnlumens")
└──────┬───────┘
       │           ③  Business code calls TenantContext.require()
       ▼
┌──────────────────┐
│ Controller       │
│  → Service       │   String tenantId = TenantContext.require();
│    → Repository  │   repo.findByTenantIdAndId(tenantId, id);
└──────────────────┘
       │
       ▼           ④  TenantFilter clears ThreadLocal in finally{}
┌──────────────┐
│TenantContext │   TenantContext.clear()
└──────────────┘
```

---

## Architecture

### TenantFilter

**Location:** `infrastructure/tenant/TenantFilter.java`

A `@Component` servlet filter annotated with `@Order(Ordered.HIGHEST_PRECEDENCE)`. It runs before Spring Security, before authentication, and before any controller.

- **On every request:** calls `TenantResolver.resolve(request)` → `TenantContext.set(tenantId)`.
- **After every request (finally):** calls `TenantContext.clear()` to prevent ThreadLocal leaks.
- Registered in `WebSecurityConfig` via `addFilterBefore(tenantFilter, UsernamePasswordAuthenticationFilter.class)`.

> **Invariant:** There is no code path where a controller method executes without `TenantContext` being set.

### TenantResolver

**Location:** `infrastructure/tenant/TenantResolver.java`

Resolves the `tenantId` from the request's `Host` header (via `request.getServerName()`). Spring's `ForwardedHeaderFilter` ensures `X-Forwarded-Host` is correctly reflected behind proxies/load balancers.

Current mapping:

| Host pattern | Resolved tenant |
|---|---|
| `*.earnlumens.org` | `earnlumens` |
| `localhost` / `127.0.0.1` | `earnlumens` |

When additional tenants are onboarded, extend this class (e.g., with a tenant registry lookup). The rest of the codebase does not need to change.

### TenantContext

**Location:** `infrastructure/tenant/TenantContext.java`

A static utility backed by two `ThreadLocal` variables:

| Method | Purpose |
|---|---|
| `set(String tenantId)` | Called by `TenantFilter`. Sets the tenant for the current thread. |
| `require()` | **Primary accessor.** Returns the tenant ID or throws `MissingTenantException`. All tenant-scoped code must use this. |
| `get()` | Returns the tenant ID or `null`. Use only when you need to check without throwing. |
| `clear()` | Called by `TenantFilter` in its `finally` block. Removes both ThreadLocals. |
| `isCrossTenantAllowed()` | Returns `true` if the current thread is inside a `runWithoutTenant` block. |
| `runWithoutTenant(Runnable)` | Executes a block with cross-tenant access. See [Cross-Tenant Operations](#cross-tenant-operations). |

### MissingTenantException

**Location:** `infrastructure/tenant/MissingTenantException.java`

A `RuntimeException` thrown by `TenantContext.require()` when no tenant is set and cross-tenant access is not explicitly allowed. This ensures a **fail-fast, fail-loud** behavior — if you forgot to set the tenant, the request crashes immediately instead of silently leaking data.

---

## Repository Rules

### Tenant-Scoped Repositories

Every repository interface in `domain/*/repository/` that operates on tenant-scoped data must follow these rules:

| Rule | Description |
|---|---|
| **Every query method must include `tenantId` as a parameter** | Method names follow the pattern `findByTenantIdAnd...`, `deleteByTenantIdAnd...`, `existsByTenantIdAnd...`, `countByTenantIdAnd...`. |
| **`save()` is exempt** | The entity object already carries its `tenantId` field. The service layer is responsible for setting it before saving. |
| **No bare `findById()` or `deleteById()`** | These methods allow cross-tenant access by design. Always use `findByTenantIdAndId()` or `deleteByTenantIdAndId()`. |
| **`tenantId` is conventionally the first parameter** | This makes it visually obvious at every call site. |

**Current tenant-scoped repositories:**

- `EntryRepository`
- `AssetRepository`
- `CollectionRepository`
- `OrderRepository`
- `EntitlementRepository`
- `FavoriteRepository`
- `SubscriptionRepository`
- `TranscodingJobRepository`

### Global Repositories (Exempt)

Some entities are inherently global (not tenant-scoped):

| Repository | Reason |
|---|---|
| `UserRepository` | Users authenticate across tenants via OAuth. User identity is global. |
| `FounderRepository` | Waitlist/founder data is platform-level, pre-tenant. |
| `FeedbackRepository` | Platform feedback, not tied to a tenant. |

These are excluded from the architecture test via `EXCLUDED_REPOSITORIES` in `TenantIsolationArchTest`.

### Cross-Tenant Operations

In rare cases, platform-level code needs to operate across all tenants (e.g., the transcoding dispatcher picks pending jobs from all tenants). These methods:

1. **Must be named with the `findAll` prefix** (e.g., `findAllByStatus`, `findAllStaleJobs`) to visually distinguish them from tenant-scoped queries.
2. **Must be registered in `CROSS_TENANT_ALLOWLIST`** inside `TenantIsolationArchTest` with a comment explaining why cross-tenant access is justified.
3. **Must be called from within `TenantContext.runWithoutTenant()`** at the call site.

Current cross-tenant methods:

| Method | Used by | Purpose |
|---|---|---|
| `TranscodingJobRepository.findAllByStatus()` | `TranscodingDispatcher` | Picks PENDING jobs across all tenants for dispatch. |
| `TranscodingJobRepository.findAllStaleJobs()` | `TranscodingJobWatchdog` | Recovers stale jobs across all tenants. |

---

## Automated Enforcement — TenantIsolationArchTest

**Location:** `src/test/java/org/earnlumens/mediastore/architecture/TenantIsolationArchTest.java`

This test runs on every build (`./gradlew test`) and **dynamically generates one test case per repository method**. For each method it verifies:

1. The method name contains `TenantId` or `tenantId`, **OR**
2. A parameter named `tenantId` exists (requires `-parameters` compiler flag, which is enabled in `build.gradle`), **OR**
3. The method is `save()` (entity carries tenantId), **OR**
4. The method is in `CROSS_TENANT_ALLOWLIST`, **OR**
5. The repository is in `EXCLUDED_REPOSITORIES`.

**If a developer adds a new repository method without `tenantId`, this test fails with a clear error message:**

```
TENANT ISOLATION VIOLATION: EntryRepository.findByStatus() does not include a
tenantId parameter. Every query/mutation on a tenant-scoped repository must be
scoped by tenantId. Either add a tenantId parameter, or if this is a legitimate
cross-tenant operation, add 'EntryRepository#findByStatus' to
CROSS_TENANT_ALLOWLIST in TenantIsolationArchTest with a justification.
```

> **This test cannot be bypassed by accident.** A developer must make a conscious, visible change to the allowlist — which will be caught in code review.

---

## Developer Guide — Adding a New Feature

When you create a new persistence-backed feature (e.g., `comment`), follow these steps to guarantee tenant isolation from day one.

### Step 1: Domain Repository Interface

```java
// domain/comment/repository/CommentRepository.java
public interface CommentRepository {

    Optional<Comment> findByTenantIdAndId(String tenantId, String id);

    Page<Comment> findByTenantIdAndEntryId(String tenantId, String entryId, Pageable pageable);

    Comment save(Comment comment);

    void deleteByTenantIdAndId(String tenantId, String id);
}
```

**Rules:**
- Every query/delete/count method takes `tenantId` as the first parameter.
- `save()` is the only exception (entity carries `tenantId`).
- Never add `findById(String id)` — use `findByTenantIdAndId(String tenantId, String id)`.

### Step 2: MongoDB Interface

```java
// infrastructure/persistence/comment/repository/CommentMongoRepository.java
public interface CommentMongoRepository extends MongoRepository<CommentEntity, String> {

    Optional<CommentEntity> findByTenantIdAndId(String tenantId, String id);

    Page<CommentEntity> findByTenantIdAndEntryId(String tenantId, String entryId, Pageable pageable);

    void deleteByTenantIdAndId(String tenantId, String id);
}
```

Spring Data derives queries from method names. The `TenantIdAnd` prefix automatically adds the tenant filter to the generated query.

### Step 3: Repository Adapter

```java
// infrastructure/persistence/comment/adapter/CommentRepositoryImpl.java
@Repository
public class CommentRepositoryImpl implements CommentRepository {

    private final CommentMongoRepository mongoRepository;
    private final CommentMapper mapper;

    // constructor...

    @Override
    public Optional<Comment> findByTenantIdAndId(String tenantId, String id) {
        return mongoRepository.findByTenantIdAndId(tenantId, id).map(mapper::toModel);
    }

    @Override
    public void deleteByTenantIdAndId(String tenantId, String id) {
        mongoRepository.deleteByTenantIdAndId(tenantId, id);
    }

    @Override
    public Comment save(Comment comment) {
        CommentEntity entity = mapper.toEntity(comment);
        return mapper.toModel(mongoRepository.save(entity));
    }
}
```

### Step 4: Application Service

```java
// application/comment/CommentService.java
@Service
public class CommentService {

    private final CommentRepository commentRepository;

    public Comment getComment(String tenantId, String commentId) {
        return commentRepository.findByTenantIdAndId(tenantId, commentId)
                .orElseThrow(() -> new NotFoundException("Comment not found"));
    }

    public void deleteComment(String tenantId, String commentId) {
        commentRepository.deleteByTenantIdAndId(tenantId, commentId);
    }
}
```

**Key rule:** Services receive `tenantId` as a parameter from the controller. They pass it to every repository call.

### Step 5: Controller

```java
// web/comment/CommentController.java
@RestController
@RequestMapping("/api/comments")
public class CommentController {

    private final CommentService commentService;

    @GetMapping("/{id}")
    public Comment getComment(@PathVariable String id) {
        String tenantId = TenantContext.require();  // ← always use require()
        return commentService.getComment(tenantId, id);
    }

    @DeleteMapping("/{id}")
    public void deleteComment(@PathVariable String id) {
        String tenantId = TenantContext.require();
        commentService.deleteComment(tenantId, id);
    }
}
```

**Key rule:** The controller calls `TenantContext.require()` once and passes the result to the service. Never call `TenantContext.get()` — use `require()` so you get a clear exception if the context is missing.

### Step 6: Tests

In unit tests for services that depend on `TenantContext.require()`:

```java
@BeforeEach
void setUp() {
    TenantContext.set("earnlumens");
}

@AfterEach
void tearDown() {
    TenantContext.clear();
}
```

For repository mock verifications, always verify the `tenantId` parameter:

```java
verify(commentRepository).findByTenantIdAndId("earnlumens", "comment-1");
// NEVER: verify(commentRepository).findById("comment-1");
```

---

## Developer Guide — Adding a New Method to an Existing Repository

1. **Add the method to the domain interface** with `tenantId` as the first parameter:
   ```java
   List<Entry> findByTenantIdAndTags(String tenantId, List<String> tags);
   ```

2. **Add the corresponding Spring Data method** to the `*MongoRepository` interface.

3. **Implement in the adapter** (`*RepositoryImpl`).

4. **Run `./gradlew test`** — `TenantIsolationArchTest` will confirm the method passes or tell you exactly what's wrong.

If you need a cross-tenant method:

1. Name it with the `findAll` prefix: `findAllByTags(List<String> tags)`.
2. Add it to `CROSS_TENANT_ALLOWLIST` in `TenantIsolationArchTest` with a comment:
   ```java
   private static final Set<String> CROSS_TENANT_ALLOWLIST = Set.of(
       // ...existing entries...
       // Platform-level tag analytics aggregates across all tenants
       "EntryRepository#findAllByTags"
   );
   ```
3. Call it from within `TenantContext.runWithoutTenant()`.

---

## Developer Guide — Background Jobs / Schedulers

Scheduled tasks (`@Scheduled`) and async jobs run outside an HTTP request, so `TenantFilter` does not set the context. There are two patterns:

### Pattern A: Single-tenant operation

If the job operates on one tenant's data, set the context manually:

```java
@Scheduled(fixedRate = 60_000)
public void cleanOrphanedDrafts() {
    TenantContext.set("earnlumens");
    try {
        // ... use tenant-scoped repository methods ...
    } finally {
        TenantContext.clear();
    }
}
```

### Pattern B: Cross-tenant platform operation

If the job legitimately operates across all tenants (e.g., dispatching transcoding jobs), use `runWithoutTenant`:

```java
@Scheduled(fixedRate = 30_000)
public void dispatchPendingJobs() {
    TenantContext.runWithoutTenant(() -> {
        // Can ONLY call methods in CROSS_TENANT_ALLOWLIST (findAllByStatus, etc.)
        // Regular tenant-scoped methods will still throw MissingTenantException
        List<TranscodingJob> pending = jobRepository.findAllByStatus(PENDING, 10);
        // ...
    });
}
```

> **Important:** Inside a `runWithoutTenant` block, `TenantContext.require()` returns `null` instead of throwing. This means you **cannot** call regular tenant-scoped service methods that rely on `require()` returning a non-null value. Design your cross-tenant code to use only explicitly cross-tenant repository methods.

---

## Common Mistakes & How They Are Prevented

| Mistake | Prevention Mechanism |
|---|---|
| Adding `findById(String id)` to a repository | `TenantIsolationArchTest` fails at build time. |
| Adding `deleteById(String id)` to a repository | `TenantIsolationArchTest` fails at build time. |
| Forgetting to pass `tenantId` to a repository call | Compilation error — the method signature requires it. |
| Calling a service method from a scheduler without `TenantContext` | `TenantContext.require()` throws `MissingTenantException` at runtime. |
| Creating a new repository without tenant scoping | `TenantIsolationArchTest` fails at build time (unless the repo is in `EXCLUDED_REPOSITORIES`). |
| Accessing data from tenant A while authenticated as tenant B | `TenantFilter` sets the tenant from the Host header — the user cannot control it. Repository queries are always filtered by the server-resolved `tenantId`. |

---

## Quick Reference Cheatsheet

```
┌───────────────────────────────────────────────────────────────────┐
│                     TENANT ISOLATION RULES                        │
├───────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ✅ DO:                                                           │
│     • Use TenantContext.require() in controllers                  │
│     • Pass tenantId as the FIRST parameter to every repo method   │
│     • Name methods findByTenantIdAnd..., deleteByTenantIdAnd...   │
│     • Use findAllXxx prefix for cross-tenant methods              │
│     • Wrap schedulers with TenantContext.set()/clear() or         │
│       TenantContext.runWithoutTenant()                             │
│     • Add TenantContext.set() / clear() in test @BeforeEach       │
│     • Run ./gradlew test before pushing                           │
│                                                                   │
│  ❌ DON'T:                                                        │
│     • Add findById() or deleteById() to any tenant-scoped repo   │
│     • Use TenantContext.get() when you need the tenant            │
│       (use require() instead)                                     │
│     • Call tenant-scoped repo methods from runWithoutTenant()     │
│     • Skip the tenantId parameter "because it's obvious"          │
│     • Add a repo to EXCLUDED_REPOSITORIES without team agreement  │
│     • Add a method to CROSS_TENANT_ALLOWLIST without a comment    │
│                                                                   │
│  🔧 FILES:                                                        │
│     infrastructure/tenant/TenantContext.java                      │
│     infrastructure/tenant/TenantFilter.java                       │
│     infrastructure/tenant/TenantResolver.java                     │
│     infrastructure/tenant/MissingTenantException.java             │
│     TenantIsolationArchTest.java (automated guard)                │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘
```
