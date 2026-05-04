package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import org.earnlumens.mediastore.domain.media.model.ThumbnailJob;
import org.earnlumens.mediastore.domain.media.model.ThumbnailJobKind;
import org.earnlumens.mediastore.domain.media.model.ThumbnailJobStatus;
import org.earnlumens.mediastore.domain.media.repository.ThumbnailJobRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.ThumbnailJobMapper;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.ThumbnailJobMongoRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ThumbnailJobRepositoryImpl implements ThumbnailJobRepository {

    private final ThumbnailJobMongoRepository mongoRepository;
    private final ThumbnailJobMapper mapper;

    public ThumbnailJobRepositoryImpl(ThumbnailJobMongoRepository mongoRepository,
                                      ThumbnailJobMapper mapper) {
        this.mongoRepository = mongoRepository;
        this.mapper = mapper;
    }

    @Override
    public ThumbnailJob save(ThumbnailJob job) {
        var entity = mapper.toEntity(job);
        var saved = mongoRepository.save(entity);
        return mapper.toModel(saved);
    }

    @Override
    public Optional<ThumbnailJob> findByTenantIdAndId(String tenantId, String id) {
        return mongoRepository.findById(id)
                .filter(entity -> tenantId.equals(entity.getTenantId()))
                .map(mapper::toModel);
    }

    @Override
    public List<ThumbnailJob> findAllByStatus(ThumbnailJobStatus status, int limit) {
        return mongoRepository.findByStatusOrderByCreatedAtAsc(status.name(), PageRequest.of(0, limit))
                .stream()
                .map(mapper::toModel)
                .toList();
    }

    @Override
    public List<ThumbnailJob> findAllStaleJobs(LocalDateTime heartbeatBefore, int limit) {
        return mongoRepository.findStaleJobs(heartbeatBefore, PageRequest.of(0, limit))
                .stream()
                .map(mapper::toModel)
                .toList();
    }

    @Override
    public Optional<ThumbnailJob> findActiveByTenantIdAndOwnerIdAndKind(
            String tenantId, String ownerId, ThumbnailJobKind kind) {
        return mongoRepository.findActiveByTenantIdAndOwnerIdAndKind(tenantId, ownerId, kind.name())
                .map(mapper::toModel);
    }
}
