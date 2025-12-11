# Security Architecture Overview

This document defines the end-to-end security architecture for the EarnLumens Authentication System.  
It describes the login flow, session model, refresh mechanism, token lifecycle, and the roles of the Web Worker and HTTP-only cookies.

---

## 1. Core Principles

- **Zero exposure of sensitive tokens to the browser runtime.**
- **Access Tokens live only in memory**, never in `localStorage`, `sessionStorage`, or IndexedDB.
- **Refresh Token stored exclusively in a Secure, HTTP-only cookie** — invisible to JavaScript.
- **Token renewal is automatic, atomic, and isolated inside a Web Worker**.
- **Frontend communicates only through a controlled API gateway layer (`api.ts`).**
- **Backend is the single authority for session lifecycle and token validity.**

---

## 2. Authentication Model (High-Level)

EarnLumens uses a **dual-token model**:

### Access Token
- Short-lived (minutes).
- Stored **only in Web Worker memory**.
- Used in every authorized API request.
- Rotated automatically via `/auth/refresh`.

### Refresh Token
- Long-lived (days/weeks).
- Issued **once** during login.
- Stored as **HTTP-only, Secure, SameSite cookie**.
- Never revealed to JavaScript.
- Used implicitly by the `/auth/refresh` endpoint.

---

## 3. Login Flow

1. User authenticates with the backend.
2. Backend issues:
   - **Access Token** → returned in JSON (to be sent into the Web Worker).
   - **Refresh Token** → placed in **HTTP-only cookie**.
3. Frontend forwards the Access Token to the Web Worker.
4. The Web Worker initializes internal state:
   - `accessToken`
   - `expiresAt`
   - `isRefreshing = false`

The browser **cannot** read or modify the refresh token at any time.

---

## 4. Web Worker Role (Token Manager)

The Web Worker is a dedicated security boundary responsible for the entire lifecycle of the Access Token.

### Responsibilities

- Store the Access Token securely in memory.
- Track expiration via its `exp` claim or server-provided TTL.
- Refresh the token when needed.
- Ensure that only **one refresh request** runs at a time.
- Validate incoming `postMessage` requests.
- Provide **always-valid tokens** to the `api.ts` layer.
- Notify the frontend when:
  - refresh fails,
  - the session expires,
  - logout is required.
- Never expose sensitive data to the DOM or global JavaScript context.

### Not Responsible For

- Performing fetch requests (except `/auth/refresh`).
- Managing UI state.
- Managing refresh tokens directly.
- Storing anything persistently.

---

## 5. Token Refresh Mechanism (Lazy Expiration Check)

The refresh mechanism is triggered **only when needed**, not via timers.

### Process

1. Whenever `api.ts` needs to call the backend, it requests a token from the Web Worker.
2. The Web Worker checks:
   - If the token is expired → trigger refresh.
   - If the token will expire soon (recommended <30s) → trigger early refresh.
3. If refresh is needed, the Worker performs:
   - **Single** `/auth/refresh` request. 
   The HTTP-only cookie containing the refresh token is automatically included by the browser.
4. Backend validates the cookie and responds with a **new Access Token only**.
5. Worker updates:
- `accessToken`
- `expiresAt`
6. Worker returns a **valid token** to `api.ts`.

The refresh token **never leaves the cookie** and is **never rotated** unless explicitly required by backend policy.

---

## 6. Backend Responsibilities

- Issue tokens with proper TTL and claims.
- Deliver the refresh token via HTTPS-only cookie.
- Validate refresh tokens server-side.
- Provide `/auth/login`, `/auth/refresh`, and `/auth/logout`.
- Reject invalid or expired tokens with correct HTTP status codes.
- Enforce CORS and domain restrictions.
- Invalidate refresh token cookies during logout.

---

## 7. Frontend Responsibilities

- Store no tokens outside the Web Worker.
- Route all backend calls through `api.ts`.
- Sanitize all dynamic content.
- Validate origin when communicating with the Web Worker.
- Handle logout and expiration events.
- React to Worker notifications (expiration, offline mode, forced logout).

---

## 8. Failure Scenarios

| Scenario | Web Worker Behavior | Frontend Behavior |
|----------|---------------------|-------------------|
| Expired Access Token | Refresh → return new token | Transparent |
| Refresh Token invalid | Signal logout | Redirect to login |
| Refresh endpoint unreachable | Enter offline mode | Retry later |
| Simultaneous token requests | Serialize via `isRefreshing` | Prevent race conditions |

---

## 9. Summary

EarnLumens enforces a modern, secure, and scalable authentication architecture by combining:

- Strict token isolation  
- Stateless API authentication  
- Controlled token renewal  
- Defense against XSS and token theft  
- Clear separation of concerns  

This approach follows standards used by major identity providers (Auth0, Okta, Google Identity) and ensures strong operational security for large-scale applications.
