package org.earnlumens.mediastore.infrastructure.reseller;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A reseller's link to a single content entry. Generated on demand by a
 * logged-in user who wants to earn a commission by distributing someone else's
 * paid content. The link carries an opaque, unguessable {@link #code} that is
 * appended to the product URL as {@code ?r=<code>}; at purchase time the backend
 * resolves the code back to this document and pays the reseller a commission
 * carved out of the seller's own share.
 *
 * <p>Invariants enforced by unique indexes:
 * <ul>
 *   <li>{@code (tenantId, code)} — a code is globally unique within a tenant, so
 *       resolving a code is unambiguous.</li>
 *   <li>{@code (tenantId, entryId, resellerUserId)} — a user has at most one
 *       active link per entry; asking again returns the same code.</li>
 * </ul>
 *
 * <p>The commission percent is <b>not</b> frozen here: it is read live from the
 * entry at purchase time, so a creator changing their commission immediately
 * changes what every existing link pays on the next sale.
 */
@Document(collection = "reseller_links")
@CompoundIndex(name = "uk_reseller_tenant_code", def = "{'tenantId': 1, 'code': 1}", unique = true)
@CompoundIndex(name = "uk_reseller_tenant_entry_user", def = "{'tenantId': 1, 'entryId': 1, 'resellerUserId': 1}", unique = true)
public class ResellerLink {

    @Id
    private String id;

    /** Tenant subdomain (canonical tenantId). Immutable. */
    private String tenantId;

    /** Entry this link resells. Immutable. */
    private String entryId;

    /** OAuth provider user-id of the reseller (link owner). Immutable. */
    private String resellerUserId;

    /** Stellar public key where the reseller receives their commission. Editable. */
    private String resellerWallet;

    /** Opaque, unguessable code appended to the product URL as {@code ?r=<code>}. Immutable. */
    private String code;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public ResellerLink() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getEntryId() { return entryId; }
    public void setEntryId(String entryId) { this.entryId = entryId; }

    public String getResellerUserId() { return resellerUserId; }
    public void setResellerUserId(String resellerUserId) { this.resellerUserId = resellerUserId; }

    public String getResellerWallet() { return resellerWallet; }
    public void setResellerWallet(String resellerWallet) { this.resellerWallet = resellerWallet; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
