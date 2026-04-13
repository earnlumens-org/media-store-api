package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import org.earnlumens.mediastore.domain.media.model.ModerationJob;
import org.earnlumens.mediastore.domain.media.model.ModerationJobStatus;
import org.earnlumens.mediastore.domain.media.repository.ModerationJobRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.ModerationJobMapper;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.ModerationJobMongoRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ModerationJobRepositoryImpl implements ModerationJobRepository {

    private final ModerationJobMongoRepository mongoRepository;
    private final ModerationJobMapper mapper;

    public ModerationJobRepositoryImpl(ModerationJobMongoRepository mongoRepository,
                                       ModerationJobMapper mapper) {
        this.mongoRepository = mongoRepository;
        this.mapper = mapper;
    }

    @Override
    public ModerationJob save(ModerationJob job) {
        var entity = mapper.toEntity(job);
        var saved = mongoRepository.save(entity);
        return mapper.toModel(saved);
    }

    @Override
    public Optional<ModerationJob> findByTenantIdAndId(String tenantId, String id) {
        return mongoRepository.findById(id)
                .filter(entity -> tenantId.equals(entity.getTenantId()))
                .map(mapper::toModel);
    }

    @Override
    public List<ModerationJob> findAllByStatus(ModerationJobStatus status, int limit) {
        return mongoRepository.findByStatusOrderByCreatedAtAsc(status.name(), PageRequest.of(0, limit))
                .stream()
                .map(mapper::toModel)
                .toList();
    }

    @Override
    public List<ModerationJob> findAllStaleJobs(LocalDateTime heartbeatBefore, int limit) {
        return mongoRepository.findStaleJobs(heartbeatBefore, PageRequest.of(0, limit))
                .stream()
                .map(mapper::toModel)
                .toList();
    }

    @Override
    public Optional<ModerationJob> findActiveByTenantIdAndEntryId(String tenantId, String entryId) {
        return mongoRepository.findActiveByTenantIdAndEntryId(tenantId, entryId).map(mapper::toModel);
    }
}
