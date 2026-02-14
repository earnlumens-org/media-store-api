package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.EntryEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.EntryMapper;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.EntryMongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class EntryRepositoryImpl implements EntryRepository {

    private final EntryMongoRepository entryMongoRepository;
    private final EntryMapper entryMapper;

    public EntryRepositoryImpl(EntryMongoRepository entryMongoRepository, EntryMapper entryMapper) {
        this.entryMongoRepository = entryMongoRepository;
        this.entryMapper = entryMapper;
    }

    @Override
    public Optional<Entry> findByTenantIdAndId(String tenantId, String id) {
        return entryMongoRepository.findByTenantIdAndId(tenantId, id)
                .map(entryMapper::toModel);
    }

    @Override
    public Entry save(Entry entry) {
        EntryEntity entity = entryMapper.toEntity(entry);
        EntryEntity saved = entryMongoRepository.save(entity);
        return entryMapper.toModel(saved);
    }
}
