package org.earnlumens.mediastore.domain.waitlist.repository;

import org.earnlumens.mediastore.domain.waitlist.model.Founder;
import org.earnlumens.mediastore.domain.waitlist.model.FounderCountByDate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FounderRepository {

    Optional<Founder> findByEmail(String email);

    Boolean existsByEmail(String email);

    long countByEntryDateBetween(LocalDateTime start, LocalDateTime end);

    Founder save(Founder founder);

    long count();

    List<FounderCountByDate> countFoundersGroupedByEntryDate(LocalDateTime start, LocalDateTime end);
}
