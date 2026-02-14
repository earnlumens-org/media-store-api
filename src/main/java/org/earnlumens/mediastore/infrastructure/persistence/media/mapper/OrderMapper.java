package org.earnlumens.mediastore.infrastructure.persistence.media.mapper;

import org.earnlumens.mediastore.domain.media.model.Order;
import org.earnlumens.mediastore.domain.media.model.OrderStatus;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.OrderEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface OrderMapper {

    @Mapping(target = "status", source = "status", qualifiedByName = "stringToOrderStatus")
    Order toModel(OrderEntity entity);

    @Mapping(target = "status", source = "status", qualifiedByName = "orderStatusToString")
    OrderEntity toEntity(Order model);

    @Named("stringToOrderStatus")
    default OrderStatus stringToOrderStatus(String value) {
        return value == null ? null : OrderStatus.valueOf(value);
    }

    @Named("orderStatusToString")
    default String orderStatusToString(OrderStatus value) {
        return value == null ? null : value.name();
    }
}
