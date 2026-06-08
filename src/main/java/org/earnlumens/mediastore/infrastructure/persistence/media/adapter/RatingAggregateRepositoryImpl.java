package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import org.earnlumens.mediastore.domain.media.model.RatingAggregate;
import org.earnlumens.mediastore.domain.media.model.TargetType;
import org.earnlumens.mediastore.domain.media.repository.RatingAggregateRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.RatingAggregateEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.RatingAggregateMapper;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.RatingAggregateMongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class RatingAggregateRepositoryImpl implements RatingAggregateRepository {

    private final RatingAggregateMongoRepository mongoRepository;
    private final RatingAggregateMapper mapper;

    public RatingAggregateRepositoryImpl(RatingAggregateMongoRepository mongoRepository,
                                         RatingAggregateMapper mapper) {
        this.mongoRepository = mongoRepository;
        this.mapper = mapper;
    }

    @Override
    public Optional<RatingAggregate> findByTenantIdAndTargetTypeAndTargetId(
            String tenantId, TargetType targetType, String targetId) {
        return mongoRepository
                .findByTenantIdAndTargetTypeAndTargetId(tenantId, targetType.name(), targetId)
                .map(mapper::toModel);
    }

    @Override
    public RatingAggregate save(RatingAggregate aggregate) {
        RatingAggregateEntity entity = mapper.toEntity(aggregate);
        RatingAggregateEntity saved = mongoRepository.save(entity);
        return mapper.toModel(saved);
    }
}
