# Layered Architecture - Media Store API

This document explains the layered architecture implemented in `media-store-api`. It is written as a practical guide for developers and AIs that need to add new endpoints, features, or persistence-backed entities without prior project context.

## Table of Contents

1. [High-Level View](#high-level-view)
2. [Package Layout](#package-layout)
3. [Architecture Layers](#architecture-layers)
4. [Feature-First Organization (waitlist, user, ...)](#feature-first-organization-waitlist-user-)
5. [End-to-End Example: User](#end-to-end-example-user)
6. [Cross-Cutting Services (External Integrations, Shared Infrastructure)](#cross-cutting-services-external-integrations-shared-infrastructure)
7. [How to Implement a New Feature](#how-to-implement-a-new-feature)
8. [Naming Conventions](#naming-conventions)
9. [Request Flow Cheatsheet](#request-flow-cheatsheet)

---

## High-Level View

The codebase follows **Clean Architecture / Hexagonal Architecture** principles. Dependencies must always point inward: Web → Application → Domain. Infrastructure implements domain ports and talks to external systems.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                                   WEB                                       │
│                       (Controllers, HTTP concerns)                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                               APPLICATION                                   │
│                     (Use cases / orchestration services)                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                 DOMAIN                                      │
│                   (Models + repository interfaces/ports)                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                             INFRASTRUCTURE                                  │
│          (Persistence adapters, external integrations, framework glue)      │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Golden rule:** a layer may depend only on inner layers. Inner layers never import outer layers.

---

## Package Layout

Below is the *current* layout of the project (updated to match the actual code under `src/main/java/org/earnlumens/mediastore`).

```
src/main/java/org/earnlumens/mediastore/
│
├── MediaStoreApiApplication.java                 # Spring Boot entry point
│
├── domain/                                      # DOMAIN (business core)
│   ├── user/                                    # Feature: user
│   │   ├── model/
│   │   │   └── User.java
│   │   └── repository/
│   │       └── UserRepository.java
│   │
│   └── waitlist/                                # Feature: waitlist
│       ├── model/
│       ├── repository/
│       └── dto/
│
├── application/                                 # APPLICATION (use cases)
│   ├── user/
│   │   └── UserService.java
│   └── waitlist/
│       └── WaitlistService.java
│
├── infrastructure/                              # INFRASTRUCTURE (adapters)
│   ├── persistence/
│   │   ├── user/
│   │   │   ├── entity/
│   │   │   │   └── UserEntity.java
│   │   │   ├── mapper/
│   │   │   │   └── UserMapper.java
│   │   │   ├── adapter/
│   │   │   │   └── UserRepositoryImpl.java
│   │   │   └── repository/
│   │   │       └── UserMongoRepository.java
│   │   │
│   │   └── waitlist/
│   │       ├── entity/
│   │       ├── mapper/
│   │       ├── adapter/
│   │       └── repository/
│   │
│   └── external/                                # External integrations
│       └── captcha/
│           └── CaptchaVerificationService.java
│
└── web/                                         # WEB (HTTP entrypoints)
    ├── waitlist/
    │   └── WaitlistController.java
    └── health/
        └── HealthCheckController.java
```

Note: `web/user/UserController.java` now exists and exposes basic profile endpoints. The `HealthCheckController` still contains a lightweight sanity-check endpoint (`POST /public/test-user`) that can be used to validate the full user persistence stack end-to-end.

---

## Architecture Layers

### 1) Domain (`domain/`)

The **Domain** is the business core. It defines:

- Models (plain Java objects)
- Repository interfaces (ports) that describe what the domain needs from persistence

Domain code should be framework-agnostic:

- No Spring annotations like `@Service`
- No persistence annotations like `@Document` / `@Entity`
- No `MongoRepository`

It answers: **What is the business concept? What operations does the business need?**

---

### 2) Application (`application/`)

The **Application** layer orchestrates use cases. It depends on domain interfaces (ports) and coordinates operations.

Typical responsibilities:

- Use-case logic (e.g., “create user”, “subscribe to waitlist”)
- Validation/orchestration and transaction boundaries (when needed)
- Calls domain repository interfaces (not infrastructure implementations)

It answers: **How do we execute a business operation end-to-end?**

---

### 3) Infrastructure (`infrastructure/`)

The **Infrastructure** layer provides concrete implementations for:

- Persistence (MongoDB adapters)
- External integrations (hCaptcha, etc.)
- Framework glue code

Infrastructure answers: **How do we talk to MongoDB / external APIs / frameworks?**

#### Persistence substructure (`infrastructure/persistence/{feature}/`)

For a persistence-backed feature, you typically see:

- `entity/` → database entities (MongoDB documents)
- `repository/` → Spring Data repositories (`MongoRepository`)
- `mapper/` → MapStruct (Entity ↔ Domain Model)
- `adapter/` → “RepositoryImpl” that implements the domain repository interface

This keeps MongoDB and Spring Data isolated from the Domain.

---

### 4) Web (`web/`)

The **Web** layer is the HTTP boundary.

Controllers:

- Accept HTTP requests
- Call the corresponding Application service
- Return HTTP responses

Controllers should not:

- Access Mongo repositories directly
- Contain core business rules

---

## Feature-First Organization (waitlist, user, ...)

### Why feature-first?

Packages are split by **business feature** (e.g. `waitlist`, `user`) instead of by technical type (controllers/services/repos).

This makes it easier to:

- Locate everything related to a feature quickly
- Scale the codebase without mega-packages
- Evolve features independently

### When to create a new feature package

Create a feature package when the concept is cohesive and has its own models/use cases:

- `user` → user persistence + user use cases
- `waitlist` → waitlist subscription + stats

Do not create a new feature package for a small helper shared by multiple features; those go to cross-cutting infrastructure.

---

## End-to-End Example: User

The `user` feature demonstrates the “clean” model across layers.

### Current files

- Domain model: `domain/user/model/User.java`
- Domain port: `domain/user/repository/UserRepository.java`
- Application service: `application/user/UserService.java`
- MongoDB document: `infrastructure/persistence/user/entity/UserEntity.java`
- Spring Data repository: `infrastructure/persistence/user/repository/UserMongoRepository.java`
- Mapper: `infrastructure/persistence/user/mapper/UserMapper.java`
- Adapter implementing the domain port: `infrastructure/persistence/user/adapter/UserRepositoryImpl.java`

### How data flows (save)

```
Application (UserService)
        │ calls
        ▼
Domain Port (UserRepository interface)
        │ implemented by
        ▼
Infrastructure Adapter (UserRepositoryImpl)
        │ uses
        ├── UserMapper (Model ↔ Entity)
        ▼
Spring Data (UserMongoRepository)
        ▼
MongoDB (collection: users)
```

### How to test it today

`UserController` exists and exposes basic profile reads by username. A sanity check also lives in the health controller:

User endpoints:
- `GET /api/user/by-username/{username}`
- `GET /api/user/exists/{username}`

- `POST /public/test-user` (implemented in `web/health/HealthCheckController.java`)

That endpoint creates a sample user, saves it, and verifies `findByOauthUserId` and `existsByOauthUserId`.

---

## Cross-Cutting Services (External Integrations, Shared Infrastructure)

Some code does not belong to a single feature package because it is reused across features or is an external integration.

### External integrations

Location:

```
infrastructure/external/{integration}/...
```

Example:

- `infrastructure/external/captcha/CaptchaVerificationService.java`

Why it lives here:

- It integrates with a third-party API (hCaptcha)
- It can be reused by multiple features (e.g., waitlist subscription, future auth flows)
- It is not a domain concept on its own

### Where to place other cross-cutting concerns

This project currently has `infrastructure/external/...` and `infrastructure/persistence/...`.

If you introduce new cross-cutting code later, keep it under `infrastructure/` and name it by intent, for example:

- `infrastructure/security/` (JWT/OAuth2 glue)
- `infrastructure/config/` (Spring configuration beans)
- `infrastructure/clock/` or `infrastructure/time/` (time abstractions)

---

## How to Implement a New Feature

Use this as the default recipe when adding a new persistence-backed feature (example: `video`).

### 1) Create the packages

```
domain/video/
├── model/
└── repository/

application/video/

infrastructure/persistence/video/
├── entity/
├── mapper/
├── adapter/
└── repository/

web/video/
```

### 2) Implement in this order

1. `domain/{feature}/model/*` (pure domain models)
2. `domain/{feature}/repository/*` (ports/interfaces)
3. `infrastructure/persistence/{feature}/entity/*` (Mongo entities)
4. `infrastructure/persistence/{feature}/repository/*` (Spring Data repositories)
5. `infrastructure/persistence/{feature}/mapper/*` (MapStruct mappers)
6. `infrastructure/persistence/{feature}/adapter/*` (port implementations)
7. `application/{feature}/*Service` (use cases)
8. `web/{feature}/*Controller` (HTTP endpoints)

### 3) Sanity checklist

- Domain does not import Spring/Mongo
- Application depends on domain interfaces, not infrastructure classes
- Infrastructure implements domain interfaces
- Web depends on Application services

---

## Naming Conventions

### Class naming

| Layer | Pattern | Example |
|------|---------|---------|
| Domain model | `{Entity}` | `User` |
| Domain port | `{Entity}Repository` | `UserRepository` |
| Mongo entity | `{Entity}Entity` | `UserEntity` |
| Spring Data repo | `{Entity}MongoRepository` | `UserMongoRepository` |
| Adapter | `{Entity}RepositoryImpl` | `UserRepositoryImpl` |
| Mapper | `{Entity}Mapper` | `UserMapper` |
| Application service | `{Feature}Service` | `UserService`, `WaitlistService` |
| Controller | `{Feature}Controller` | `WaitlistController`, `HealthCheckController` |

### Method naming

- `findBy{Field}()`
- `existsBy{Field}()`
- `save()`
- Mapper: `toModel()` / `toEntity()`

---

## Request Flow Cheatsheet

Typical request flow for a new endpoint:

```
HTTP request
   ▼
web/{feature}/*Controller
   ▼
application/{feature}/*Service
   ▼
domain/{feature}/repository/*Repository (interface)
   ▼
infrastructure/.../*RepositoryImpl (adapter)
   ▼
infrastructure/.../*MongoRepository (Spring Data)
   ▼
MongoDB
```

---

## References

- Clean Architecture (Robert C. Martin): https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html
- Hexagonal Architecture (Alistair Cockburn): https://alistair.cockburn.us/hexagonal-architecture/
- Spring Guides: https://spring.io/guides
