# Authentication System - Backend

This document describes the authentication architecture implemented in the Media Store API.

## Overview

The system implements a **dual-token security model** with OAuth2 social login:

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   OAuth2    │────▶│   Backend   │────▶│   Frontend  │
│  Provider   │     │   (tokens)  │     │  (consumer) │
└─────────────┘     └─────────────┘     └─────────────┘
```

## Token Types

### Access Token
- Short-lived JWT
- Contains minimal identity claims
- Delivered in JSON response body
- Used for API authorization via `Authorization: Bearer` header

### Refresh Token
- Long-lived JWT
- Delivered exclusively via **HttpOnly, Secure, SameSite cookie**
- Never exposed in response body
- Automatically attached by browser on refresh requests

## Authentication Flow

### 1. OAuth2 Login

```
User ──▶ OAuth2 Provider ──▶ Backend Callback ──▶ Frontend Callback
                                    │
                                    ▼
                           Generate temp UUID
                           Store with user data
                           Redirect with UUID
```

The backend generates a temporary identifier after successful OAuth2 authentication and redirects to the frontend callback page.

### 2. Session Creation

```
Frontend ──▶ Session Endpoint (with UUID header)
                    │
                    ▼
            Validate UUID (TTL ~2 min)
            Generate Access Token
            Generate Refresh Token
                    │
                    ▼
            Response: { accessToken: "..." }
            Cookie: [refresh token, HttpOnly]
```

### 3. Token Refresh

```
Frontend ──▶ Refresh Endpoint
                    │
            Browser auto-attaches cookie
                    │
                    ▼
            Validate refresh token
            Generate new Access Token
                    │
                    ▼
            Response: { accessToken: "..." }
```

**Important**: The refresh token is NOT rotated on refresh. This simplifies SPA token management.

### 4. Logout

```
Frontend ──▶ Logout Endpoint
                    │
                    ▼
            Clear refresh cookie (Max-Age=0)
            Response: 204 No Content
```

## Response Codes

| Scenario | Code | Description |
|----------|------|-------------|
| Success | 200 | Token issued/refreshed |
| Logout | 204 | Session cleared |
| Invalid UUID | 401 | UUID expired or not found |
| Invalid Token | 401 | Token missing, expired, or malformed |
| Revoked Token | 403 | Token explicitly revoked |

## Security Considerations

### Cookie Configuration
- `HttpOnly`: Prevents JavaScript access
- `Secure`: HTTPS only (enforced in production)
- `SameSite=Strict`: CSRF protection
- `Path=/`: Available for all routes

### JWT Claims
Access tokens contain minimal claims:
- Subject (user identifier)
- Issued at / Expiration
- Token type

Refresh tokens contain:
- Subject (user identifier)
- Issued at / Expiration
- Token type marker

### Protected Endpoints
All non-public endpoints require valid Bearer token. The `AuthTokenFilter` validates tokens and establishes security context for each request.

## OAuth2 Providers

Currently supported:
- **X (Twitter)**: Primary identity provider, provides username for public profiles

Future considerations:
- Google: Viewer-only accounts (no public profile)

## User Roles

| Provider | Role | Public Profile | Username Source |
|----------|------|----------------|-----------------|
| X | Creator | Yes | OAuth username |
| Google | Viewer | No | N/A |

## Implementation References

| Component | Location |
|-----------|----------|
| Security Config | `infrastructure/security/` |
| Auth Controller | `web/auth/` |
| Auth Service | `application/auth/` |
| JWT Utilities | `infrastructure/security/jwt/` |
| OAuth2 Handlers | `infrastructure/security/oauth2/` |
