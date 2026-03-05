package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.Order;
import org.earnlumens.mediastore.domain.media.model.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository {

    List<Order> findAllByTenantIdAndUserIdAndEntryId(String tenantId, String userId, String entryId);

    Optional<Order> findById(String id);

    /** Find expired PENDING orders for cleanup */
    List<Order> findByStatusAndExpiresAtBefore(OrderStatus status, LocalDateTime cutoff);

    /** Count completed sales for a seller within a tenant */
    long countByTenantIdAndSellerIdAndStatus(String tenantId, String sellerId, OrderStatus status);

    /** List completed sales for a seller within a tenant, newest first */
    List<Order> findByTenantIdAndSellerIdAndStatus(String tenantId, String sellerId, OrderStatus status);

    Order save(Order order);
}
