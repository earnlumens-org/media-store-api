package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EntryRepository {

    Optional<Entry> findByTenantIdAndId(String tenantId, String id);

    List<Entry> findByStatusAndCreatedAtBefore(EntryStatus status, LocalDateTime cutoff);

    Entry save(Entry entry);

    void deleteById(String id);
}
