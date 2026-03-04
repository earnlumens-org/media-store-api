package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import com.mongodb.client.result.UpdateResult;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.EntryEntity;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class EntryMongoRepositoryCustomImpl implements EntryMongoRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    public EntryMongoRepositoryCustomImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public long updateAuthorInfoByUserId(String userId, String newUsername, String newAvatarUrl) {
        Query query = new Query(Criteria.where("userId").is(userId));
        Update update = new Update()
                .set("authorUsername", newUsername)
                .set("authorAvatarUrl", newAvatarUrl);

        UpdateResult result = mongoTemplate.updateMulti(query, update, EntryEntity.class);
        return result.getModifiedCount();
    }
}
