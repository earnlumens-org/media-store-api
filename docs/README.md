# Media Store API - Documentation

Backend service for EarnLumens platform built with Spring Boot.

## Documentation Index

| Document | Description |
|----------|-------------|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Hexagonal architecture, package layout, how to add features |
| [AUTH.md](AUTH.md) | Authentication flow, token lifecycle, security model |

## Quick Start

```bash
# Development
./gradlew bootRun

# Tests
./gradlew test

# Build
./gradlew build
```

## Tech Stack

- **Framework**: Spring Boot 4.x
- **Security**: Spring Security + OAuth2
- **Database**: MongoDB
- **Architecture**: Hexagonal / Clean Architecture

## Project Structure

```
src/main/java/org/earnlumens/mediastore/
├── domain/          # Business core (models, repository interfaces)
├── application/     # Use cases and orchestration services
├── infrastructure/  # Adapters (persistence, external integrations)
└── web/             # HTTP controllers
```

## Configuration

The application uses profile-based configuration:
- `application.properties` - Base configuration
- `application-dev.properties` - Local development (gitignored)
- `application-pdn.properties` - Production settings

## API Overview

All protected endpoints require Bearer token authentication.

| Category | Description |
|----------|-------------|
| Auth | Session management, token refresh, logout |
| User | Profile retrieval and management |
| Public | Health checks, waitlist |

See [AUTH.md](AUTH.md) for authentication details.
