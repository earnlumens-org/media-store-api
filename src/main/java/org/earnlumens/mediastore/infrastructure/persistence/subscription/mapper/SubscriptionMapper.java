package org.earnlumens.mediastore.infrastructure.persistence.subscription.mapper;

import org.earnlumens.mediastore.domain.subscription.model.Subscription;
import org.earnlumens.mediastore.infrastructure.persistence.subscription.entity.SubscriptionEntity;
import org.mapstruct.Mapper;
import org.mapstruct.InheritInverseConfiguration;

@Mapper(componentModel = "spring")
public interface SubscriptionMapper {
    Subscription toModel(SubscriptionEntity entity);

    @InheritInverseConfiguration
    SubscriptionEntity toEntity(Subscription model);
}
