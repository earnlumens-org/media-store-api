package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.Order;

import java.util.Optional;

public interface OrderRepository {

    Optional<Order> findByTenantIdAndUserIdAndEntryId(String tenantId, String userId, String entryId);

    Order save(Order order);
}
