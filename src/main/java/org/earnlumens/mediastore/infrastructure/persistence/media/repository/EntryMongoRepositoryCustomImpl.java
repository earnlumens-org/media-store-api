package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.EntryEntity;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class EntryMongoRepositoryCustomImpl implements EntryMongoRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    public EntryMongoRepositoryCustomImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void incrementViewCount(String entryId) {
        Query query = new Query(Criteria.where("_id").is(entryId));
        Update update = new Update().inc("viewCount", 1);
        mongoTemplate.updateFirst(query, update, EntryEntity.class);
    }

    @Override
    public Map<String, Long> getOwnerStats(String tenantId, String userId) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("tenantId").is(tenantId).and("userId").is(userId)),
                Aggregation.facet()
                        .and(Aggregation.group().count().as("count").sum("viewCount").as("views"))
                        .as("totals")
                        .and(Aggregation.group("status").count().as("count"))
                        .as("byStatus")
        );

        AggregationResults<Document> results = mongoTemplate.aggregate(
                aggregation, mongoTemplate.getCollectionName(EntryEntity.class), Document.class);

        Document doc = results.getUniqueMappedResult();
        Map<String, Long> stats = new HashMap<>();
        stats.put("totalEntries", 0L);
        stats.put("totalViews", 0L);
        stats.put("published", 0L);
        stats.put("drafts", 0L);
        stats.put("inReview", 0L);
        stats.put("rejected", 0L);
        stats.put("archived", 0L);

        if (doc == null) {
            return stats;
        }

        List<Document> totals = doc.getList("totals", Document.class);
        if (totals != null && !totals.isEmpty()) {
            Document t = totals.get(0);
            stats.put("totalEntries", toLong(t.get("count")));
            stats.put("totalViews", toLong(t.get("views")));
        }

        List<Document> byStatus = doc.getList("byStatus", Document.class);
        if (byStatus != null) {
            for (Document s : byStatus) {
                String status = s.getString("_id");
                long count = toLong(s.get("count"));
                switch (status) {
                    case "PUBLISHED" -> stats.put("published", count);
                    case "DRAFT" -> stats.put("drafts", count);
                    case "IN_REVIEW" -> stats.put("inReview", count);
                    case "REJECTED" -> stats.put("rejected", count);
                    case "ARCHIVED" -> stats.put("archived", count);
                    default -> { /* ignore unknown statuses */ }
                }
            }
        }

        return stats;
    }

    private static long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return 0L;
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
