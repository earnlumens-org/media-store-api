package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.EntryEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.EntryMapper;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.EntryMongoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
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
    public Page<Entry> findByTenantIdAndStatus(String tenantId, EntryStatus status, Pageable pageable) {
        return entryMongoRepository.findByTenantIdAndStatusOrderByPublishedAtDesc(tenantId, status.name(), pageable)
                .map(entryMapper::toModel);
    }

    @Override
    public Page<Entry> findByTenantIdAndAuthorUsernameAndStatus(String tenantId, String authorUsername, EntryStatus status, Pageable pageable) {
        return entryMongoRepository.findByTenantIdAndAuthorUsernameIgnoreCaseAndStatusOrderByPublishedAtDesc(tenantId, authorUsername, status.name(), pageable)
                .map(entryMapper::toModel);
    }

    @Override
    public Page<Entry> findByTenantIdAndAuthorUsernameAndStatusAndType(String tenantId, String authorUsername, EntryStatus status, EntryType type, Pageable pageable) {
        return entryMongoRepository.findByTenantIdAndAuthorUsernameIgnoreCaseAndStatusAndTypeOrderByPublishedAtDesc(tenantId, authorUsername, status.name(), type.name(), pageable)
                .map(entryMapper::toModel);
    }

    @Override
    public Page<Entry> findByTenantIdAndUserId(String tenantId, String userId, Pageable pageable) {
        return entryMongoRepository.findByTenantIdAndUserIdOrderByCreatedAtDesc(tenantId, userId, pageable)
                .map(entryMapper::toModel);
    }

    @Override
    public Page<Entry> findByTenantIdAndUserIdAndStatusNot(String tenantId, String userId, EntryStatus excludeStatus, Pageable pageable) {
        return entryMongoRepository.findByTenantIdAndUserIdAndStatusNotOrderByCreatedAtDesc(tenantId, userId, excludeStatus.name(), pageable)
                .map(entryMapper::toModel);
    }

    @Override
    public Page<Entry> findByTenantIdAndUserIdAndStatus(String tenantId, String userId, EntryStatus status, Pageable pageable) {
        return entryMongoRepository.findByTenantIdAndUserIdAndStatusOrderByCreatedAtDesc(tenantId, userId, status.name(), pageable)
                .map(entryMapper::toModel);
    }

    @Override
    public Page<Entry> findByTenantIdAndUserIdAndType(String tenantId, String userId, EntryType type, Pageable pageable) {
        return entryMongoRepository.findByTenantIdAndUserIdAndTypeOrderByCreatedAtDesc(tenantId, userId, type.name(), pageable)
                .map(entryMapper::toModel);
    }

    @Override
    public Page<Entry> findByTenantIdAndUserIdAndStatusNotAndType(String tenantId, String userId, EntryStatus excludeStatus, EntryType type, Pageable pageable) {
        return entryMongoRepository.findByTenantIdAndUserIdAndStatusNotAndTypeOrderByCreatedAtDesc(tenantId, userId, excludeStatus.name(), type.name(), pageable)
                .map(entryMapper::toModel);
    }

    @Override
    public Page<Entry> findByTenantIdAndUserIdAndStatusAndType(String tenantId, String userId, EntryStatus status, EntryType type, Pageable pageable) {
        return entryMongoRepository.findByTenantIdAndUserIdAndStatusAndTypeOrderByCreatedAtDesc(tenantId, userId, status.name(), type.name(), pageable)
                .map(entryMapper::toModel);
    }

    @Override
    public List<Entry> findByTenantIdAndIdIn(String tenantId, List<String> ids) {
        return entryMongoRepository.findByTenantIdAndIdIn(tenantId, ids)
                .stream()
                .map(entryMapper::toModel)
                .toList();
    }

    @Override
    public List<Entry> findByStatus(EntryStatus status) {
        return entryMongoRepository.findByStatus(status.name())
                .stream()
                .map(entryMapper::toModel)
                .toList();
    }

    @Override
    public List<Entry> findByStatusAndCreatedAtBefore(EntryStatus status, LocalDateTime cutoff) {
        return entryMongoRepository.findByStatusAndCreatedAtBefore(status.name(), cutoff)
                .stream()
                .map(entryMapper::toModel)
                .toList();
    }

    @Override
    public void incrementViewCount(String entryId) {
        entryMongoRepository.incrementViewCount(entryId);
    }

    @Override
    public java.util.Map<String, Long> getOwnerStats(String tenantId, String userId) {
        return entryMongoRepository.getOwnerStats(tenantId, userId);
    }

    @Override
    public Entry save(Entry entry) {
        EntryEntity entity = entryMapper.toEntity(entry);
        EntryEntity saved = entryMongoRepository.save(entity);
        return entryMapper.toModel(saved);
    }

    @Override
    public void deleteById(String id) {
        entryMongoRepository.deleteById(id);
    }

    @Override
    public long updateAuthorInfoByUserId(String userId, String newUsername, String newAvatarUrl) {
        return entryMongoRepository.updateAuthorInfoByUserId(userId, newUsername, newAvatarUrl);
    }
}
