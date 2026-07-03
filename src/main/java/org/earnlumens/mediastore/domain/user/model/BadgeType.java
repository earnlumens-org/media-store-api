package org.earnlumens.mediastore.domain.user.model;

/**
 * Badge types for verified users.
 * <ul>
 *   <li>{@code U1} — Community (blue badge). Tenant-scoped.</li>
 *   <li>{@code U2} — Ecosystem (gold badge). Tenant-scoped.</li>
 *   <li>{@code U3} — Stellar Ambassador (gray badge). <b>Global</b>: a single
 *       ACTIVE assignment applies across every tenant. It is granted and
 *       revoked only from the main tenant via admin-api, so reads must not
 *       filter U3 by tenantId.</li>
 * </ul>
 */
public enum BadgeType {
    U1,
    U2,
    U3
}
