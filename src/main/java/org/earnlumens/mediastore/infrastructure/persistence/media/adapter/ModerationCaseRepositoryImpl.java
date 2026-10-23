package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import org.earnlumens.mediastore.domain.media.model.ModerationCase;
import org.earnlumens.mediastore.domain.media.repository.ModerationCaseRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.ModerationCaseMapper;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.ModerationCaseMongoRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ModerationCaseRepositoryImpl implements ModerationCaseRepository {

    private final ModerationCaseMongoRepository mongoRepository;
    private final ModerationCaseMapper mapper;

    public ModerationCaseRepositoryImpl(ModerationCaseMongoRepository mongoRepository,
                                        ModerationCaseMapper mapper) {
        this.mongoRepository = mongoRepository;
        this.mapper = mapper;
    }

    @Override
    public ModerationCase save(ModerationCase moderationCase) {
        return mapper.toModel(mongoRepository.save(mapper.toEntity(moderationCase)));
    }

    @Override
    public Optional<ModerationCase> findByTenantIdAndId(String tenantId, String id) {
        return mongoRepository.findByTenantIdAndId(tenantId, id).map(mapper::toModel);
    }

    @Override
    public List<ModerationCase> findByTenantId(String tenantId, int limit) {
        return mongoRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, PageRequest.of(0, limit))
                .stream().map(mapper::toModel).toList();
    }

    @Override
    public List<ModerationCase> findAllUnresolved(int limit) {
        return mongoRepository.findByResolvedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, limit))
                .stream().map(mapper::toModel).toList();
    }
}
