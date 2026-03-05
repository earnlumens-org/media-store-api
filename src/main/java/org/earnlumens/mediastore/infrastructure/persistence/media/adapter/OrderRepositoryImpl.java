package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import org.earnlumens.mediastore.domain.media.model.Order;
import org.earnlumens.mediastore.domain.media.model.OrderStatus;
import org.earnlumens.mediastore.domain.media.repository.OrderRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.OrderEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.OrderMapper;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.OrderMongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class OrderRepositoryImpl implements OrderRepository {

    private final OrderMongoRepository orderMongoRepository;
    private final OrderMapper orderMapper;

    public OrderRepositoryImpl(OrderMongoRepository orderMongoRepository, OrderMapper orderMapper) {
        this.orderMongoRepository = orderMongoRepository;
        this.orderMapper = orderMapper;
    }

    @Override
    public List<Order> findAllByTenantIdAndUserIdAndEntryId(String tenantId, String userId, String entryId) {
        return orderMongoRepository.findByTenantIdAndUserIdAndEntryId(tenantId, userId, entryId)
                .stream()
                .map(orderMapper::toModel)
                .toList();
    }

    @Override
    public Optional<Order> findById(String id) {
        return orderMongoRepository.findById(id)
                .map(orderMapper::toModel);
    }

    @Override
    public List<Order> findByStatusAndExpiresAtBefore(OrderStatus status, LocalDateTime cutoff) {
        return orderMongoRepository.findByStatusAndExpiresAtBefore(status.name(), cutoff)
                .stream()
                .map(orderMapper::toModel)
                .toList();
    }

    @Override
    public long countByTenantIdAndSellerIdAndStatus(String tenantId, String sellerId, OrderStatus status) {
        return orderMongoRepository.countByTenantIdAndSellerIdAndStatus(tenantId, sellerId, status.name());
    }

    @Override
    public List<Order> findByTenantIdAndSellerIdAndStatus(String tenantId, String sellerId, OrderStatus status) {
        return orderMongoRepository.findByTenantIdAndSellerIdAndStatusOrderByCompletedAtDesc(tenantId, sellerId, status.name())
                .stream()
                .map(orderMapper::toModel)
                .toList();
    }

    @Override
    public Order save(Order order) {
        OrderEntity entity = orderMapper.toEntity(order);
        OrderEntity saved = orderMongoRepository.save(entity);
        return orderMapper.toModel(saved);
    }
}
