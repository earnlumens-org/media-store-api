package org.earnlumens.mediastore.infrastructure.persistence.space.adapter;

import org.earnlumens.mediastore.domain.space.Space;
import org.earnlumens.mediastore.domain.space.repository.SpaceRepository;
import org.earnlumens.mediastore.infrastructure.persistence.space.mapper.SpaceMapper;
import org.earnlumens.mediastore.infrastructure.persistence.space.repository.SpaceMongoRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class SpaceRepositoryImpl implements SpaceRepository {

    private final SpaceMongoRepository mongoRepository;
    private final SpaceMapper mapper;

    public SpaceRepositoryImpl(SpaceMongoRepository mongoRepository, SpaceMapper mapper) {
        this.mongoRepository = mongoRepository;
        this.mapper = mapper;
    }

    @Override
    public Optional<Space> findByTenantIdAndId(String tenantId, String id) {
        return mongoRepository.findByTenantIdAndId(tenantId, id).map(mapper::toModel);
    }

    @Override
    public List<Space> findByTenantIdAndIdIn(String tenantId, List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return mongoRepository.findByTenantIdAndIdIn(tenantId, ids).stream()
                .map(mapper::toModel)
                .toList();
    }

    @Override
    public List<Space> findSidebarSpaces(String tenantId) {
        return mongoRepository
                .findByTenantIdAndStatusAndShowInSidebarTrueOrderBySortOrderAsc(
                        tenantId, org.earnlumens.mediastore.domain.space.SpaceStatus.ACTIVE.name())
                .stream()
                .map(mapper::toModel)
                .toList();
    }
}
