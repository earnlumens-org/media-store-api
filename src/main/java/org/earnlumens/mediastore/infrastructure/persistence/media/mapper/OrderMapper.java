package org.earnlumens.mediastore.infrastructure.persistence.media.mapper;

import org.earnlumens.mediastore.domain.media.model.Order;
import org.earnlumens.mediastore.domain.media.model.OrderStatus;
import org.earnlumens.mediastore.domain.media.model.PaymentSplit;
import org.earnlumens.mediastore.domain.media.model.SplitRole;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.OrderEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.PaymentSplitEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.Collections;
import java.util.List;

@Mapper(componentModel = "spring")
public interface OrderMapper {

    @Mapping(target = "status", source = "status", qualifiedByName = "stringToOrderStatus")
    @Mapping(target = "paymentSplits", source = "paymentSplits", qualifiedByName = "entitiesToSplits")
    Order toModel(OrderEntity entity);

    @Mapping(target = "status", source = "status", qualifiedByName = "orderStatusToString")
    @Mapping(target = "paymentSplits", source = "paymentSplits", qualifiedByName = "splitsToEntities")
    OrderEntity toEntity(Order model);

    @Named("stringToOrderStatus")
    default OrderStatus stringToOrderStatus(String value) {
        return value == null ? null : OrderStatus.valueOf(value);
    }

    @Named("orderStatusToString")
    default String orderStatusToString(OrderStatus value) {
        return value == null ? null : value.name();
    }

    @Named("entitiesToSplits")
    default List<PaymentSplit> entitiesToSplits(List<PaymentSplitEntity> entities) {
        if (entities == null) return Collections.emptyList();
        return entities.stream().map(this::toSplitModel).toList();
    }

    @Named("splitsToEntities")
    default List<PaymentSplitEntity> splitsToEntities(List<PaymentSplit> splits) {
        if (splits == null) return Collections.emptyList();
        return splits.stream().map(this::toSplitEntity).toList();
    }

    default PaymentSplit toSplitModel(PaymentSplitEntity e) {
        return new PaymentSplit(e.getWallet(), SplitRole.valueOf(e.getRole()), e.getPercent());
    }

    default PaymentSplitEntity toSplitEntity(PaymentSplit s) {
        PaymentSplitEntity e = new PaymentSplitEntity();
        e.setWallet(s.getWallet());
        e.setRole(s.getRole().name());
        e.setPercent(s.getPercent());
        return e;
    }
}
