package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import org.earnlumens.mediastore.domain.media.model.TranscodingJob;
import org.earnlumens.mediastore.domain.media.model.TranscodingJobStatus;
import org.earnlumens.mediastore.domain.media.repository.TranscodingJobRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.TranscodingJobMapper;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.TranscodingJobMongoRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class TranscodingJobRepositoryImpl implements TranscodingJobRepository {

    private final TranscodingJobMongoRepository mongoRepository;
    private final TranscodingJobMapper mapper;

    public TranscodingJobRepositoryImpl(TranscodingJobMongoRepository mongoRepository,
                                        TranscodingJobMapper mapper) {
        this.mongoRepository = mongoRepository;
        this.mapper = mapper;
    }

    @Override
    public TranscodingJob save(TranscodingJob job) {
        var entity = mapper.toEntity(job);
        var saved = mongoRepository.save(entity);
        return mapper.toModel(saved);
    }

    @Override
    public Optional<TranscodingJob> findByTenantIdAndId(String tenantId, String id) {
        return mongoRepository.findById(id)
                .filter(entity -> tenantId.equals(entity.getTenantId()))
                .map(mapper::toModel);
    }

    @Override
    public Optional<TranscodingJob> findByTenantIdAndAssetId(String tenantId, String assetId) {
        return mongoRepository.findByTenantIdAndAssetId(tenantId, assetId).map(mapper::toModel);
    }

    @Override
    public List<TranscodingJob> findByTenantIdAndStatus(String tenantId, TranscodingJobStatus status, int limit) {
        return mongoRepository.findByTenantIdAndStatusOrderByCreatedAtAsc(tenantId, status.name(), PageRequest.of(0, limit))
                .stream()
                .map(mapper::toModel)
                .toList();
    }

    @Override
    public List<TranscodingJob> findAllByStatus(TranscodingJobStatus status, int limit) {
        return mongoRepository.findByStatusOrderByCreatedAtAsc(status.name(), PageRequest.of(0, limit))
                .stream()
                .map(mapper::toModel)
                .toList();
    }

    @Override
    public List<TranscodingJob> findAllStaleJobs(LocalDateTime heartbeatBefore, int limit) {
        return mongoRepository.findStaleJobs(heartbeatBefore, PageRequest.of(0, limit))
                .stream()
                .map(mapper::toModel)
                .toList();
    }

    @Override
    public Optional<TranscodingJob> findActiveByTenantIdAndEntryId(String tenantId, String entryId) {
        return mongoRepository.findActiveByTenantIdAndEntryId(tenantId, entryId).map(mapper::toModel);
    }
}
