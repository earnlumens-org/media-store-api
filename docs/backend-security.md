# Backend Security Specification (Spring Boot)

This document details the authentication, authorization, and token lifecycle mechanisms implemented in the EarnLumens backend.

---

## 1. Authentication Model

The backend implements a **dual-token security model**:

### Access Token
- Short-lived JWT (recommended: 5–15 minutes).
- Contains minimal identity (e.g., user ID, roles).
- Returned to the frontend on `/auth/login` and `/auth/refresh`.
- Used in every authorized API request via `Authorization: Bearer <token>`.

### Refresh Token
- Long-lived.
- Issued **only once** during login.
- Delivered exclusively through an **HTTP-only, Secure, SameSite cookie**.
- Never returned in a JSON body.
- Automatically included by the browser when calling `/auth/refresh`.
- Never accessible to JavaScript.

---

## 2. Login Endpoint (`POST /auth/login`)

### Steps on successful login:

1. Backend authenticates user credentials or OAuth identity.
2. Backend generates:
   - **Access Token (JWT)**
   - **Refresh Token** (opaque or JWT, stored server-side)
3. Response format:
   ```json
   { "accessToken": "<jwt>" }
4. Refresh token is sent as:

   ```
   Set-Cookie: refresh_token=<value>;
               HttpOnly; Secure; SameSite=Strict; Path=/auth/refresh
   ```

### Notes

* Refresh token MUST NOT be exposed outside cookie.
* Access token is short-lived to minimize attack window.

---

## 3. Refresh Endpoint (`POST /auth/refresh`)

This endpoint renews the Access Token based on the refresh token cookie.

### Request Behavior

* Browser automatically attaches `refresh_token` cookie.
* No request body is needed.

### Server Behavior

1. Validate refresh token existence.
2. Validate token validity (DB lookup, signature, expiration).
3. Issue a **new Access Token**.
4. Return new access token in JSON:

   ```json
   { "accessToken": "<new-jwt>" }
   ```

### Important

* Refresh token **is not rotated** unless backend explicitly decides to.
* No new refresh token is issued on refresh (simplifies SPA correctness).

### Failure Cases

| Condition              | Response         |
| ---------------------- | ---------------- |
| Invalid/missing cookie | 401 Unauthorized |
| Expired refresh token  | 401 Unauthorized |
| Revoked refresh token  | 403 Forbidden    |

---

## 4. Logout Endpoint (`POST /auth/logout`)

On logout:

1. Backend clears refresh cookie:

   ```
   Set-Cookie: refresh_token=; Max-Age=0; Path=/auth/refresh;
               HttpOnly; Secure; SameSite=Strict
   ```
2. Access token expires naturally on client.
3. Frontend clears its Web Worker access-token state.

---

## 5. Spring Security Configuration

Spring Security enforces all authentication and authorization rules.

### Key Configurations

#### Stateless Session

```java
.sessionManagement()
    .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
```

#### JWT Filter

* Extracts JWT from `Authorization` header.
* Validates signature + expiration.
* Rejects malformed tokens.

#### CORS Configuration

* Restrict allowed origins to production/frontend domains.
* Allow credentials for `/auth/refresh`.
* Allow necessary headers:

  * `Authorization`
  * `Content-Type`

#### CSRF

* Disabled for stateless JWT architecture.

#### Cookie Security

* Must enforce:

  * `HttpOnly`
  * `Secure`
  * `SameSite=Strict`
  * `Path=/auth/refresh`

---

## 6. Token Validation

### Access Token Validation

* Validate signature (HMAC or RSA/ECDSA).
* Validate expiration claim (`exp`).
* Extract user identity (`sub`).
* Load roles for authorization.

### Refresh Token Validation

* Usually stored in DB or cache.
* Confirm:

  * Not expired
  * Not revoked
  * Matches user session
  * Originates from valid login

### Recommended Storage

* Database table: `refresh_tokens`
* Fields:

  * `userId`
  * `tokenHash`
  * `expiresAt`
  * `createdAt`
  * `revoked`

---

## 7. Error Handling and Status Codes

| Error                        | Description                    | Response |
| ---------------------------- | ------------------------------ | -------- |
| Missing/invalid access token | No valid JWT provided          | 401      |
| Expired access token         | Token lifetime ended           | 401      |
| Invalid refresh cookie       | Missing or tampered            | 401      |
| Expired refresh token        | Requires new login             | 401      |
| Revoked refresh token        | Fraud prevention               | 403      |
| Insufficient role            | User lacks required permission | 403      |

---

## 8. Security Guarantees

* Refresh tokens are **never exposed to JavaScript**.
* Access tokens are short-lived and scoped.
* All sessions are fully stateless.
* Backend controls token issuance and revocation.
* Cookie-level protections prevent XSS and CSRF attacks.
* Clear separation between login, refresh, and logout flows.

---

## 9. Summary

The backend implements a secure, scalable authentication system based on:

* Short-lived access tokens
* Long-lived HTTP-only refresh cookies
* Clear, stateless validation rules
* Strict cookie security
* Minimal token exposure surface

This aligns with the security architecture used by major identity platforms (Auth0, Okta, Google Identity, AWS Cognito) and is appropriate for modern high-scale SPAs.


