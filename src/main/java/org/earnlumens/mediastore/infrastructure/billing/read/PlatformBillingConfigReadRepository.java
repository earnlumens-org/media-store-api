package org.earnlumens.mediastore.infrastructure.billing.read;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface PlatformBillingConfigReadRepository
        extends MongoRepository<PlatformBillingConfigReadModel, String> {
}
