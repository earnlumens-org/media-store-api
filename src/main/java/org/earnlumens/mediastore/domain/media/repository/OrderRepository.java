package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.Order;
import org.earnlumens.mediastore.domain.media.model.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository {

    Optional<Order> findByTenantIdAndUserIdAndEntryId(String tenantId, String userId, String entryId);

    Optional<Order> findById(String id);

    /** Find expired PENDING orders for cleanup */
    List<Order> findByStatusAndExpiresAtBefore(OrderStatus status, LocalDateTime cutoff);

    Order save(Order order);
}
