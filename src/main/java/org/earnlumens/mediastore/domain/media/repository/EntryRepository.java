package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.Entry;

import java.util.Optional;

public interface EntryRepository {

    Optional<Entry> findByTenantIdAndId(String tenantId, String id);

    Entry save(Entry entry);
}
