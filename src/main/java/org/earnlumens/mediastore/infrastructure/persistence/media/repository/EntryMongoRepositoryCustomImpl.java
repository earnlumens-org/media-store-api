package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.EntryEntity;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Repository
public class EntryMongoRepositoryCustomImpl implements EntryMongoRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    public EntryMongoRepositoryCustomImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void incrementViewCount(String tenantId, String entryId) {
        Query query = new Query(Criteria.where("tenantId").is(tenantId).and("_id").is(entryId));
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

    // ── Unified Creator Studio feed ─────────────────────────────────────────

    /**
     * Builds the common $unionWith aggregation pipeline that merges entries + collections
     * into a single sorted, filtered, paginated result set.
     *
     * <p>Pipeline shape:
     * <ol>
     *   <li>$match on entries collection (tenantId + userId)</li>
     *   <li>$addFields: kind="entry", sortDate=createdAt, itemCount=0</li>
     *   <li>$unionWith collections (same $match + $addFields: kind="collection", itemCount=$size(items))</li>
     *   <li>(optional) $match on status</li>
     *   <li>(optional) $match on type (entry type or "collection")</li>
     *   <li>(optional) $match on search (regex on title)</li>
     *   <li>$sort by chosen field</li>
     *   <li>$skip + $limit</li>
     * </ol>
     */
    @Override
    public List<Document> findStudioItems(String tenantId, String userId,
                                          String status, String type, String search,
                                          String sort, int skip, int limit) {
        List<AggregationOperation> ops = buildStudioPipeline(tenantId, userId, status, type, search);

        // Sort
        ops.add(buildSortStage(sort));
        // Pagination
        ops.add(Aggregation.skip((long) skip));
        ops.add(Aggregation.limit(limit));

        // Project only the fields we need (minimize memory / network)
        ops.add(context -> Document.parse("""
            { "$project": {
                "_id": 1, "kind": 1, "type": 1, "title": 1, "description": 1,
                "status": 1, "thumbnailR2Key": 1, "coverR2Key": 1,
                "isPaid": 1, "priceXlm": 1, "priceUsd": 1, "priceCurrency": 1,
                "contentLanguage": 1, "durationSec": 1, "viewCount": 1,
                "itemCount": 1, "createdAt": 1, "updatedAt": 1, "publishedAt": 1,
                "sellerWallet": 1, "sortDate": 1, "moderationFeedback": 1,
                "resourceContent": 1
            }}
            """));

        Aggregation agg = Aggregation.newAggregation(ops);
        return mongoTemplate.aggregate(agg, "entries", Document.class).getMappedResults();
    }

    @Override
    public long countStudioItems(String tenantId, String userId,
                                 String status, String type, String search) {
        List<AggregationOperation> ops = buildStudioPipeline(tenantId, userId, status, type, search);
        ops.add(Aggregation.count().as("total"));

        Aggregation agg = Aggregation.newAggregation(ops);
        Document result = mongoTemplate.aggregate(agg, "entries", Document.class).getUniqueMappedResult();
        return result != null ? toLong(result.get("total")) : 0;
    }

    /**
     * Shared pipeline stages for both findStudioItems and countStudioItems.
     */
    private List<AggregationOperation> buildStudioPipeline(String tenantId, String userId,
                                                           String status, String type, String search) {
        List<AggregationOperation> ops = new ArrayList<>();

        // 1. Match entries for this user/tenant
        ops.add(Aggregation.match(Criteria.where("tenantId").is(tenantId).and("userId").is(userId)));

        // 2. Normalize entry docs: add kind, sortDate, itemCount
        ops.add(context -> Document.parse("""
            { "$addFields": {
                "kind": "entry",
                "sortDate": "$createdAt",
                "itemCount": { "$literal": 0 },
                "coverR2Key": { "$literal": null }
            }}
            """));

        // 3. $unionWith collections — match + normalize in sub-pipeline
        Document collMatch = new Document("$match", new Document("tenantId", tenantId).append("userId", userId));
        Document collAddFields = Document.parse("""
            { "$addFields": {
                "kind": "collection",
                "type": { "$ifNull": [ { "$toLower": "$collectionType" }, "catalog" ] },
                "sortDate": "$createdAt",
                "itemCount": { "$cond": { "if": { "$isArray": "$items" }, "then": { "$size": "$items" }, "else": 0 } },
                "durationSec": { "$literal": null },
                "viewCount": { "$literal": 0 },
                "contentLanguage": { "$literal": null },
                "thumbnailR2Key": { "$literal": null }
            }}
            """);
        ops.add(context -> new Document("$unionWith",
                new Document("coll", "collections")
                        .append("pipeline", List.of(collMatch, collAddFields))));

        // 4. Optional status filter
        if (status != null && !status.isBlank()) {
            ops.add(Aggregation.match(Criteria.where("status").is(status)));
        } else {
            // Default: exclude ARCHIVED
            ops.add(Aggregation.match(Criteria.where("status").ne("ARCHIVED")));
        }

        // 5. Optional type filter
        if (type != null && !type.isBlank()) {
            if ("COLLECTION".equalsIgnoreCase(type)) {
                ops.add(Aggregation.match(Criteria.where("kind").is("collection")));
            } else {
                // Filter by entry type (video, audio, image, resource)
                ops.add(Aggregation.match(
                        Criteria.where("kind").is("entry")
                                .and("type").is(type.toUpperCase())));
            }
        }

        // 6. Optional search (regex on title — case-insensitive)
        if (search != null && !search.isBlank()) {
            String escaped = Pattern.quote(search);
            ops.add(Aggregation.match(Criteria.where("title").regex(escaped, "i")));
        }

        return ops;
    }

    private AggregationOperation buildSortStage(String sort) {
        if (sort == null) sort = "newest";
        return switch (sort) {
            case "oldest" -> Aggregation.sort(org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Direction.ASC, "sortDate"));
            case "title_asc" -> Aggregation.sort(org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Direction.ASC, "title"));
            case "title_desc" -> Aggregation.sort(org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Direction.DESC, "title"));
            default -> Aggregation.sort(org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Direction.DESC, "sortDate"));
        };
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }

    // ── Public profile feed ─────────────────────────────────────────────────

    /**
     * Shared projection for all public feeds (profile, purchased).
     */
    private static final String PUBLIC_FEED_PROJECT = """
            { "$project": {
                "_id": 1, "kind": 1, "type": 1, "title": 1, "description": 1,
                "authorUsername": 1, "authorAvatarUrl": 1, "authorBadge": 1,
                "publishedAt": 1,
                "thumbnailR2Key": 1, "coverR2Key": 1, "durationSec": 1,
                "viewCount": 1, "isPaid": 1, "priceXlm": 1, "priceUsd": 1,
                "priceCurrency": 1, "itemCount": 1, "sortDate": 1
            }}
            """;

    @Override
    public List<Document> findProfileFeedItems(String tenantId, String authorUsername,
                                                String type, String search, String sort,
                                                int skip, int limit) {
        List<AggregationOperation> ops = buildProfileFeedPipeline(tenantId, authorUsername, type, search);
        ops.add(buildSortStage(sort));
        ops.add(Aggregation.skip((long) skip));
        ops.add(Aggregation.limit(limit));
        ops.add(context -> Document.parse(PUBLIC_FEED_PROJECT));

        Aggregation agg = Aggregation.newAggregation(ops);
        return mongoTemplate.aggregate(agg, "entries", Document.class).getMappedResults();
    }

    @Override
    public long countProfileFeedItems(String tenantId, String authorUsername,
                                       String type, String search) {
        List<AggregationOperation> ops = buildProfileFeedPipeline(tenantId, authorUsername, type, search);
        ops.add(Aggregation.count().as("total"));

        Aggregation agg = Aggregation.newAggregation(ops);
        Document result = mongoTemplate.aggregate(agg, "entries", Document.class).getUniqueMappedResult();
        return result != null ? toLong(result.get("total")) : 0;
    }

    private List<AggregationOperation> buildProfileFeedPipeline(String tenantId, String authorUsername,
                                                                  String type, String search) {
        List<AggregationOperation> ops = new ArrayList<>();

        // 1. Match PUBLISHED entries for this author
        String usernamePattern = "^" + Pattern.quote(authorUsername) + "$";
        ops.add(Aggregation.match(Criteria.where("tenantId").is(tenantId)
                .and("authorUsername").regex(usernamePattern, "i")
                .and("status").is("PUBLISHED")));

        // 2. Normalize entry docs
        ops.add(context -> Document.parse("""
            { "$addFields": {
                "kind": "entry",
                "sortDate": "$publishedAt",
                "itemCount": { "$literal": 0 },
                "coverR2Key": { "$literal": null }
            }}
            """));

        // 3. $unionWith PUBLISHED + PUBLIC collections by the same author
        Document collMatch = new Document("$match",
                new Document("tenantId", tenantId)
                        .append("authorUsername",
                                new Document("$regex", "^" + Pattern.quote(authorUsername) + "$")
                                        .append("$options", "i"))
                        .append("status", "PUBLISHED")
                        .append("visibility", "PUBLIC"));
        Document collAddFields = Document.parse("""
            { "$addFields": {
                "kind": "collection",
                "type": { "$ifNull": [ { "$toLower": "$collectionType" }, "catalog" ] },
                "sortDate": "$publishedAt",
                "itemCount": { "$cond": { "if": { "$isArray": "$items" }, "then": { "$size": "$items" }, "else": 0 } },
                "durationSec": { "$literal": null },
                "viewCount": { "$literal": 0 },
                "thumbnailR2Key": { "$literal": null }
            }}
            """);
        ops.add(context -> new Document("$unionWith",
                new Document("coll", "collections")
                        .append("pipeline", List.of(collMatch, collAddFields))));

        // 4. Optional type filter
        addTypeFilter(ops, type);

        // 5. Optional search
        addSearchFilter(ops, search);

        return ops;
    }

    // ── Purchased feed ──────────────────────────────────────────────────────

    @Override
    public List<Document> findPurchasedFeedItems(String tenantId,
                                                  Set<String> entryIds, Set<String> collectionIds,
                                                  String type, String search, String sort,
                                                  int skip, int limit) {
        List<AggregationOperation> ops = buildPurchasedFeedPipeline(tenantId, entryIds, collectionIds, type, search);
        ops.add(buildSortStage(sort));
        ops.add(Aggregation.skip((long) skip));
        ops.add(Aggregation.limit(limit));
        ops.add(context -> Document.parse(PUBLIC_FEED_PROJECT));

        Aggregation agg = Aggregation.newAggregation(ops);
        return mongoTemplate.aggregate(agg, "entries", Document.class).getMappedResults();
    }

    @Override
    public long countPurchasedFeedItems(String tenantId,
                                         Set<String> entryIds, Set<String> collectionIds,
                                         String type, String search) {
        List<AggregationOperation> ops = buildPurchasedFeedPipeline(tenantId, entryIds, collectionIds, type, search);
        ops.add(Aggregation.count().as("total"));

        Aggregation agg = Aggregation.newAggregation(ops);
        Document result = mongoTemplate.aggregate(agg, "entries", Document.class).getUniqueMappedResult();
        return result != null ? toLong(result.get("total")) : 0;
    }

    private List<AggregationOperation> buildPurchasedFeedPipeline(String tenantId,
                                                                    Set<String> entryIds,
                                                                    Set<String> collectionIds,
                                                                    String type, String search) {
        List<AggregationOperation> ops = new ArrayList<>();

        // Convert string IDs to ObjectIds for _id matching
        List<org.bson.types.ObjectId> entryOids = entryIds.stream()
                .map(org.bson.types.ObjectId::new)
                .collect(Collectors.toList());

        // 1. Match purchased entries by ID
        ops.add(Aggregation.match(Criteria.where("tenantId").is(tenantId)
                .and("_id").in(entryOids)));

        // 2. Normalize
        ops.add(context -> Document.parse("""
            { "$addFields": {
                "kind": "entry",
                "sortDate": "$publishedAt",
                "itemCount": { "$literal": 0 },
                "coverR2Key": { "$literal": null }
            }}
            """));

        // 3. $unionWith purchased collections by ID
        if (!collectionIds.isEmpty()) {
            List<org.bson.types.ObjectId> collOids = collectionIds.stream()
                    .map(org.bson.types.ObjectId::new)
                    .collect(Collectors.toList());

            Document collMatch = new Document("$match",
                    new Document("tenantId", tenantId)
                            .append("_id", new Document("$in", collOids)));
            Document collAddFields = Document.parse("""
                { "$addFields": {
                    "kind": "collection",
                    "type": { "$ifNull": [ { "$toLower": "$collectionType" }, "catalog" ] },
                    "sortDate": "$publishedAt",
                    "itemCount": { "$cond": { "if": { "$isArray": "$items" }, "then": { "$size": "$items" }, "else": 0 } },
                    "durationSec": { "$literal": null },
                    "viewCount": { "$literal": 0 },
                    "thumbnailR2Key": { "$literal": null }
                }}
                """);
            ops.add(context -> new Document("$unionWith",
                    new Document("coll", "collections")
                            .append("pipeline", List.of(collMatch, collAddFields))));
        }

        // 4. Optional type filter
        addTypeFilter(ops, type);

        // 5. Optional search
        addSearchFilter(ops, search);

        return ops;
    }

    // ── Shared filter helpers ───────────────────────────────────────────────

    private void addTypeFilter(List<AggregationOperation> ops, String type) {
        if (type != null && !type.isBlank()) {
            if ("COLLECTION".equalsIgnoreCase(type)) {
                ops.add(Aggregation.match(Criteria.where("kind").is("collection")));
            } else {
                ops.add(Aggregation.match(
                        Criteria.where("kind").is("entry")
                                .and("type").is(type.toUpperCase())));
            }
        }
    }

    private void addPricingFilter(List<AggregationOperation> ops, String pricing) {
        if (pricing != null && !pricing.isBlank()) {
            if ("free".equalsIgnoreCase(pricing)) {
                ops.add(Aggregation.match(Criteria.where("isPaid").is(false)));
            } else if ("premium".equalsIgnoreCase(pricing)) {
                ops.add(Aggregation.match(Criteria.where("isPaid").is(true)));
            }
        }
    }

    /**
     * Apply consumer-side content language filter (Phase 4).
     * <p>
     * Builds a {@code contentLanguage IN […]} match where the IN list is
     * the user's preferred languages, optionally augmented with
     * {@code "multi"} so language-free content (instrumental music, images,
     * mixed-language) is included. Skipped entirely when the filter
     * doesn't apply (anonymous user, "show all" mode, or empty preferences
     * without {@code includeMulti} — see {@link org.earnlumens.mediastore.domain.media.model.LanguageFilter#applies()}).
     */
    private void addLanguageFilter(List<AggregationOperation> ops,
                                    org.earnlumens.mediastore.domain.media.model.LanguageFilter filter) {
        if (filter == null || !filter.applies()) {
            return;
        }
        List<String> in = new ArrayList<>(filter.languages());
        if (filter.includeMulti() && !in.contains("multi")) {
            in.add("multi");
        }
        ops.add(Aggregation.match(Criteria.where("contentLanguage").in(in)));
    }

    private void addSearchFilter(List<AggregationOperation> ops, String search) {
        if (search != null && !search.isBlank()) {
            String escaped = Pattern.quote(search);
            ops.add(Aggregation.match(Criteria.where("title").regex(escaped, "i")));
        }
    }

    // ── Explore feed ────────────────────────────────────────────────────────

    @Override
    public Document findExploreFeed(String tenantId, String type, String pricing, String sort,
                                     org.earnlumens.mediastore.domain.media.model.LanguageFilter languageFilter,
                                     int skip, int limit) {
        List<AggregationOperation> ops = buildExploreFeedPipeline(tenantId, type, pricing, languageFilter);

        // Build sort document for use inside $facet
        Document sortDoc = buildSortDocument(sort);

        // $facet: data + count in a single aggregation pass
        ops.add(context -> new Document("$facet", new Document()
                .append("data", List.of(
                        sortDoc,
                        new Document("$skip", skip),
                        new Document("$limit", limit),
                        Document.parse(PUBLIC_FEED_PROJECT)))
                .append("count", List.of(
                        new Document("$count", "total")))));

        Aggregation agg = Aggregation.newAggregation(ops);
        return mongoTemplate.aggregate(agg, "entries", Document.class).getUniqueMappedResult();
    }

    private Document buildSortDocument(String sort) {
        if (sort == null) sort = "newest";
        return switch (sort) {
            case "oldest" -> new Document("$sort", new Document("sortDate", 1));
            case "title_asc" -> new Document("$sort", new Document("title", 1));
            case "title_desc" -> new Document("$sort", new Document("title", -1));
            default -> new Document("$sort", new Document("sortDate", -1));
        };
    }

    private List<AggregationOperation> buildExploreFeedPipeline(String tenantId, String type, String pricing,
                                                                  org.earnlumens.mediastore.domain.media.model.LanguageFilter languageFilter) {
        List<AggregationOperation> ops = new ArrayList<>();

        // 1. Match ALL PUBLISHED entries for this tenant
        ops.add(Aggregation.match(Criteria.where("tenantId").is(tenantId)
                .and("status").is("PUBLISHED")));

        // 2. Normalize entry docs
        ops.add(context -> Document.parse("""
            { "$addFields": {
                "kind": "entry",
                "sortDate": "$publishedAt",
                "itemCount": { "$literal": 0 },
                "coverR2Key": { "$literal": null }
            }}
            """));

        // 3. $unionWith ALL PUBLISHED + PUBLIC collections for this tenant
        Document collMatch = new Document("$match",
                new Document("tenantId", tenantId)
                        .append("status", "PUBLISHED")
                        .append("visibility", "PUBLIC"));
        Document collAddFields = Document.parse("""
            { "$addFields": {
                "kind": "collection",
                "type": { "$ifNull": [ { "$toLower": "$collectionType" }, "catalog" ] },
                "sortDate": "$publishedAt",
                "itemCount": { "$cond": { "if": { "$isArray": "$items" }, "then": { "$size": "$items" }, "else": 0 } },
                "durationSec": { "$literal": null },
                "viewCount": { "$literal": 0 },
                "thumbnailR2Key": { "$literal": null }
            }}
            """);
        ops.add(context -> new Document("$unionWith",
                new Document("coll", "collections")
                        .append("pipeline", List.of(collMatch, collAddFields))));

        // 4. Optional content language filter (Phase 4 — consumer prefs).
        addLanguageFilter(ops, languageFilter);

        // 5. Optional type filter
        addTypeFilter(ops, type);

        // 6. Optional pricing filter
        addPricingFilter(ops, pricing);

        return ops;
    }

    // ── Community feed ────────────────────────────────────────────────────

    @Override
    public Document findCommunityFeed(String tenantId, String badgeKey, String type,
                                       String pricing, String sort,
                                       org.earnlumens.mediastore.domain.media.model.LanguageFilter languageFilter,
                                       int skip, int limit) {
        List<AggregationOperation> ops = buildCommunityFeedPipeline(tenantId, badgeKey, type, pricing, languageFilter);

        Document sortDoc = buildSortDocument(sort);

        ops.add(context -> new Document("$facet", new Document()
                .append("data", List.of(
                        sortDoc,
                        new Document("$skip", skip),
                        new Document("$limit", limit),
                        Document.parse(PUBLIC_FEED_PROJECT)))
                .append("count", List.of(
                        new Document("$count", "total")))));

        Aggregation agg = Aggregation.newAggregation(ops);
        return mongoTemplate.aggregate(agg, "entries", Document.class).getUniqueMappedResult();
    }

    private List<AggregationOperation> buildCommunityFeedPipeline(String tenantId, String badgeKey,
                                                                    String type, String pricing,
                                                                    org.earnlumens.mediastore.domain.media.model.LanguageFilter languageFilter) {
        List<AggregationOperation> ops = new ArrayList<>();

        // 1. Match PUBLISHED entries with the given authorBadge
        ops.add(Aggregation.match(Criteria.where("tenantId").is(tenantId)
                .and("status").is("PUBLISHED")
                .and("authorBadge").is(badgeKey)));

        // 2. Normalize entry docs
        ops.add(context -> Document.parse("""
            { "$addFields": {
                "kind": "entry",
                "sortDate": "$publishedAt",
                "itemCount": { "$literal": 0 },
                "coverR2Key": { "$literal": null }
            }}
            """));

        // 3. $unionWith PUBLISHED + PUBLIC collections with matching authorBadge
        Document collMatch = new Document("$match",
                new Document("tenantId", tenantId)
                        .append("status", "PUBLISHED")
                        .append("visibility", "PUBLIC")
                        .append("authorBadge", badgeKey));
        Document collAddFields = Document.parse("""
            { "$addFields": {
                "kind": "collection",
                "type": { "$ifNull": [ { "$toLower": "$collectionType" }, "catalog" ] },
                "sortDate": "$publishedAt",
                "itemCount": { "$cond": { "if": { "$isArray": "$items" }, "then": { "$size": "$items" }, "else": 0 } },
                "durationSec": { "$literal": null },
                "viewCount": { "$literal": 0 },
                "thumbnailR2Key": { "$literal": null }
            }}
            """);
        ops.add(context -> new Document("$unionWith",
                new Document("coll", "collections")
                        .append("pipeline", List.of(collMatch, collAddFields))));

        // 4. Optional content language filter (Phase 4 — consumer prefs).
        addLanguageFilter(ops, languageFilter);

        // 5. Optional type filter
        addTypeFilter(ops, type);

        // 6. Optional pricing filter
        addPricingFilter(ops, pricing);

        return ops;
    }

    @Override
    public long updateAuthorInfoByUserId(String tenantId, String userId, String newUsername, String newAvatarUrl) {
        Query query = new Query(Criteria.where("tenantId").is(tenantId).and("userId").is(userId));
        Update update = new Update()
                .set("authorUsername", newUsername)
                .set("authorAvatarUrl", newAvatarUrl);

        UpdateResult result = mongoTemplate.updateMulti(query, update, EntryEntity.class);
        return result.getModifiedCount();
    }
}
