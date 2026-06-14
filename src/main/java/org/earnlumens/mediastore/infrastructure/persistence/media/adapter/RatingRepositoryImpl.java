package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import org.earnlumens.mediastore.domain.media.model.Rating;
import org.earnlumens.mediastore.domain.media.model.RatingProofType;
import org.earnlumens.mediastore.domain.media.model.TargetType;
import org.earnlumens.mediastore.domain.media.repository.RatingRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.RatingEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.RatingMapper;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.RatingMongoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class RatingRepositoryImpl implements RatingRepository {

    private final RatingMongoRepository mongoRepository;
    private final RatingMapper mapper;

    public RatingRepositoryImpl(RatingMongoRepository mongoRepository, RatingMapper mapper) {
        this.mongoRepository = mongoRepository;
        this.mapper = mapper;
    }

    @Override
    public Optional<Rating> findByTenantIdAndUserIdAndTargetTypeAndTargetId(
            String tenantId, String userId, TargetType targetType, String targetId) {
        return mongoRepository
                .findByTenantIdAndUserIdAndTargetTypeAndTargetId(tenantId, userId, targetType.name(), targetId)
                .map(mapper::toModel);
    }

    @Override
    public long deleteByTenantIdAndUserIdAndTargetTypeAndTargetId(
            String tenantId, String userId, TargetType targetType, String targetId) {
        return mongoRepository
                .deleteByTenantIdAndUserIdAndTargetTypeAndTargetId(tenantId, userId, targetType.name(), targetId);
    }

    @Override
    public long countByTenantIdAndUserIdAndCreatedAtAfter(String tenantId, String userId, LocalDateTime after) {
        return mongoRepository.countByTenantIdAndUserIdAndCreatedAtAfter(tenantId, userId, after);
    }

    @Override
    public Page<Rating> findByTenantIdAndTargetTypeAndTargetId(
            String tenantId, TargetType targetType, String targetId, Pageable pageable) {
        return mongoRepository
                .findByTenantIdAndTargetTypeAndTargetId(tenantId, targetType.name(), targetId, pageable)
                .map(mapper::toModel);
    }

    @Override
    public long countByTenantIdAndTargetTypeAndTargetIdAndLiked(
            String tenantId, TargetType targetType, String targetId, boolean liked) {
        return mongoRepository
                .countByTenantIdAndTargetTypeAndTargetIdAndLiked(tenantId, targetType.name(), targetId, liked);
    }

    @Override
    public long countByTenantIdAndTargetTypeAndTargetIdAndProofTypeAndLiked(
            String tenantId, TargetType targetType, String targetId, RatingProofType proofType, boolean liked) {
        return mongoRepository.countByTenantIdAndTargetTypeAndTargetIdAndProofTypeAndLiked(
                tenantId, targetType.name(), targetId, proofType.name(), liked);
    }

    @Override
    public Rating save(Rating rating) {
        RatingEntity entity = mapper.toEntity(rating);
        RatingEntity saved = mongoRepository.save(entity);
        return mapper.toModel(saved);
    }
}
