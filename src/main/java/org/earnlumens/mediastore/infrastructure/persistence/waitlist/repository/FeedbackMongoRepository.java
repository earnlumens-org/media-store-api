package org.earnlumens.mediastore.infrastructure.persistence.waitlist.repository;

import org.earnlumens.mediastore.infrastructure.persistence.waitlist.entity.FeedbackEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface FeedbackMongoRepository extends MongoRepository<FeedbackEntity, String> {
    // Native MongoDB CRUD is implemented for FeedbackEntity.
}
