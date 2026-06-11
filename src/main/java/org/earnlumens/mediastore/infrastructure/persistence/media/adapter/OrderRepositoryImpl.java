package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import org.earnlumens.mediastore.domain.media.model.Order;
import org.earnlumens.mediastore.domain.media.model.OrderStatus;
import org.earnlumens.mediastore.domain.media.repository.OrderRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.OrderEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.OrderMapper;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.OrderMongoRepository;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class OrderRepositoryImpl implements OrderRepository {

    /** findAndModify must return the post-update document so callers see the new status. */
    private static final FindAndModifyOptions RETURN_NEW = FindAndModifyOptions.options().returnNew(true);

    private final OrderMongoRepository orderMongoRepository;
    private final OrderMapper orderMapper;
    private final MongoTemplate mongoTemplate;

    public OrderRepositoryImpl(OrderMongoRepository orderMongoRepository,
                               OrderMapper orderMapper,
                               MongoTemplate mongoTemplate) {
        this.orderMongoRepository = orderMongoRepository;
        this.orderMapper = orderMapper;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public List<Order> findAllByTenantIdAndUserIdAndEntryId(String tenantId, String userId, String entryId) {
        return orderMongoRepository.findByTenantIdAndUserIdAndEntryId(tenantId, userId, entryId)
                .stream()
                .map(orderMapper::toModel)
                .toList();
    }

    @Override
    public List<Order> findAllByTenantIdAndUserIdAndCollectionId(String tenantId, String userId, String collectionId) {
        return orderMongoRepository.findByTenantIdAndUserIdAndCollectionId(tenantId, userId, collectionId)
                .stream()
                .map(orderMapper::toModel)
                .toList();
    }

    @Override
    public Optional<Order> findByTenantIdAndId(String tenantId, String id) {
        return orderMongoRepository.findByTenantIdAndId(tenantId, id)
                .map(orderMapper::toModel);
    }

    @Override
    public List<Order> findByTenantIdAndStatusAndExpiresAtBefore(String tenantId, OrderStatus status, LocalDateTime cutoff) {
        return orderMongoRepository.findByTenantIdAndStatusAndExpiresAtBefore(tenantId, status.name(), cutoff)
                .stream()
                .map(orderMapper::toModel)
                .toList();
    }

    @Override
    public List<Order> findAllByTenantIdAndUserIdAndStatus(String tenantId, String userId, OrderStatus status) {
        return orderMongoRepository.findByTenantIdAndUserIdAndStatus(tenantId, userId, status.name())
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

    // ── Atomic state-machine operations ──

    @Override
    public Optional<Order> tryLockForProcessing(String tenantId, String orderId, String userId,
                                                String signedXdr, LocalDateTime now) {
        Query query = Query.query(Criteria.where("_id").is(orderId)
                .and("tenantId").is(tenantId)
                .and("userId").is(userId)
                .and("status").is(OrderStatus.PENDING.name())
                .and("expiresAt").gt(now));
        Update update = new Update()
                .set("status", OrderStatus.PROCESSING.name())
                .set("signedXdr", signedXdr);
        OrderEntity updated = mongoTemplate.findAndModify(query, update, RETURN_NEW, OrderEntity.class);
        return Optional.ofNullable(updated).map(orderMapper::toModel);
    }

    @Override
    public Optional<Order> tryComplete(String tenantId, String orderId, String stellarTxHash,
                                       LocalDateTime completedAt) {
        Query query = Query.query(Criteria.where("_id").is(orderId)
                .and("tenantId").is(tenantId)
                .and("status").is(OrderStatus.PROCESSING.name()));
        Update update = new Update()
                .set("status", OrderStatus.COMPLETED.name())
                .set("stellarTxHash", stellarTxHash)
                .set("completedAt", completedAt);
        OrderEntity updated = mongoTemplate.findAndModify(query, update, RETURN_NEW, OrderEntity.class);
        return Optional.ofNullable(updated).map(orderMapper::toModel);
    }

    @Override
    public Optional<Order> tryTransitionStatus(String tenantId, String orderId,
                                               OrderStatus expected, OrderStatus next) {
        Query query = Query.query(Criteria.where("_id").is(orderId)
                .and("tenantId").is(tenantId)
                .and("status").is(expected.name()));
        Update update = new Update().set("status", next.name());
        OrderEntity updated = mongoTemplate.findAndModify(query, update, RETURN_NEW, OrderEntity.class);
        return Optional.ofNullable(updated).map(orderMapper::toModel);
    }

    @Override
    public boolean existsCompletedByStellarTxHashExcludingOrder(String stellarTxHash, String excludeOrderId) {
        return orderMongoRepository.existsByStellarTxHashAndStatusAndIdNot(
                stellarTxHash, OrderStatus.COMPLETED.name(), excludeOrderId);
    }

    @Override
    public long expirePendingOrdersForUserExcept(String tenantId, String userId, String excludeOrderId) {
        Query query = Query.query(Criteria.where("tenantId").is(tenantId)
                .and("userId").is(userId)
                .and("status").is(OrderStatus.PENDING.name())
                .and("_id").ne(excludeOrderId));
        Update update = new Update().set("status", OrderStatus.EXPIRED.name());
        return mongoTemplate.updateMulti(query, update, OrderEntity.class).getModifiedCount();
    }
}
