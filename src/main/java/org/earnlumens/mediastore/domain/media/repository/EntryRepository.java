package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EntryRepository {

    Optional<Entry> findByTenantIdAndId(String tenantId, String id);

    Page<Entry> findByTenantIdAndStatus(String tenantId, EntryStatus status, Pageable pageable);

    Page<Entry> findByTenantIdAndAuthorUsernameAndStatus(String tenantId, String authorUsername, EntryStatus status, Pageable pageable);

    Page<Entry> findByTenantIdAndAuthorUsernameAndStatusAndType(String tenantId, String authorUsername, EntryStatus status, EntryType type, Pageable pageable);

    List<Entry> findByStatus(EntryStatus status);

    List<Entry> findByStatusAndCreatedAtBefore(EntryStatus status, LocalDateTime cutoff);

    Entry save(Entry entry);

    void deleteById(String id);
}
