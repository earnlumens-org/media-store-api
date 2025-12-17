package org.earnlumens.mediastore.infrastructure.persistence.waitlist.repository;

import org.earnlumens.mediastore.infrastructure.persistence.waitlist.entity.FounderEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface FounderMongoRepository extends MongoRepository<FounderEntity, String> {

    Optional<FounderEntity> findByEmail(String email);

    Boolean existsByEmail(String email);

    long countByEntryDateBetween(LocalDateTime start, LocalDateTime end);
}
