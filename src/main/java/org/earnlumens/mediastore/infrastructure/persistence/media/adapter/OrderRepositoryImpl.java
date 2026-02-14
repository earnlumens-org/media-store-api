package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import org.earnlumens.mediastore.domain.media.model.Order;
import org.earnlumens.mediastore.domain.media.repository.OrderRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.OrderEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.OrderMapper;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.OrderMongoRepository;
import org.springframework.stereotype.Repository;

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
    public Optional<Order> findByTenantIdAndUserIdAndEntryId(String tenantId, String userId, String entryId) {
        return orderMongoRepository.findByTenantIdAndUserIdAndEntryId(tenantId, userId, entryId)
                .map(orderMapper::toModel);
    }

    @Override
    public Order save(Order order) {
        OrderEntity entity = orderMapper.toEntity(order);
        OrderEntity saved = orderMongoRepository.save(entity);
        return orderMapper.toModel(saved);
    }
}
