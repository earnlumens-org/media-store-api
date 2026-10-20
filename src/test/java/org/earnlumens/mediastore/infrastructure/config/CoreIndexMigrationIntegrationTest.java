package org.earnlumens.mediastore.infrastructure.config;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.IndexOptions;
import de.flapdoodle.embed.mongo.commands.ServerAddress;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
import de.flapdoodle.reverse.TransitionWalker;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H9 acceptance matrix (MYCELIUM-RESILIENCE.md) against a real mongod.
 */
class CoreIndexMigrationIntegrationTest {

    private static final List<String> COLLECTIONS =
            List.of("entries", "collections", "assets", "orders", "entitlements", "favorites");

    private static TransitionWalker.ReachedState<RunningMongodProcess> mongod;
    private static MongoClient client;

    private MongoTemplate template;
    private CoreIndexMigration migration;

    @BeforeAll
    static void startMongod() {
        mongod = Mongod.instance().start(Version.Main.V7_0);
        ServerAddress address = mongod.current().getServerAddress();
        client = MongoClients.create("mongodb://" + address.getHost() + ":" + address.getPort());
    }

    @AfterAll
    static void stopMongod() {
        if (client != null) client.close();
        if (mongod != null) mongod.close();
    }

    @BeforeEach
    void freshDatabase() {
        template = new MongoTemplate(client, "h9_" + UUID.randomUUID().toString().replace("-", ""));
        migration = new CoreIndexMigration(template);
    }

    @AfterEach
    void dropDatabase() {
        template.getDb().drop();
    }

    @Test
    void createsEveryCoreIndexWithTheCorrectedDefinitions() {
        migration.run(null);

        for (CoreIndexMigration.IndexSpec spec : CoreIndexMigration.specs()) {
            Document index = indexesByName(spec.collection()).get(spec.name());
            assertThat(index).as("%s.%s exists", spec.collection(), spec.name()).isNotNull();
            assertThat(index.get("key", Document.class)).isEqualTo(spec.keys());
        }

        // orders: per-target indexes are NOT unique (retries + repeatable tips)
        Map<String, Document> orders = indexesByName("orders");
        assertThat(orders.get("idx_order_tenant_user_target_entry").getBoolean("unique", false)).isFalse();
        assertThat(orders.get("idx_order_tenant_user_target_coll").getBoolean("unique", false)).isFalse();

        // entitlements: unique AND partial on targetType
        Map<String, Document> ents = indexesByName("entitlements");
        Document entryIdx = ents.get("idx_ent_tenant_user_entry");
        assertThat(entryIdx.getBoolean("unique")).isTrue();
        assertThat(entryIdx.get("partialFilterExpression", Document.class))
                .isEqualTo(new Document("targetType", "ENTRY"));
        Document collIdx = ents.get("idx_ent_tenant_user_coll");
        assertThat(collIdx.getBoolean("unique")).isTrue();
        assertThat(collIdx.get("partialFilterExpression", Document.class))
                .isEqualTo(new Document("targetType", "COLLECTION"));

        // the old, wrong definitions must not be present
        assertThat(ents).doesNotContainKeys("idx_ent_tenant_user_target_entry", "idx_ent_tenant_user_target_coll");

        // remaining unique constraints
        assertThat(indexesByName("entries").get("idx_tenant_id").getBoolean("unique")).isTrue();
        assertThat(indexesByName("collections").get("idx_coll_tenant_id").getBoolean("unique")).isTrue();
        assertThat(indexesByName("assets").get("idx_tenant_r2key").getBoolean("unique")).isTrue();
        assertThat(indexesByName("favorites").get("idx_tenant_user_item").getBoolean("unique")).isTrue();
    }

    @Test
    void oneUserCanHoldTwoEntryAndTwoCollectionEntitlements() {
        migration.run(null);

        template.insert(entitlement("ENTRY", "e1", null), "entitlements");
        template.insert(entitlement("ENTRY", "e2", null), "entitlements");
        template.insert(entitlement("COLLECTION", null, "c1"), "entitlements");
        template.insert(entitlement("COLLECTION", null, "c2"), "entitlements");

        assertThat(template.getCollection("entitlements").countDocuments(
                new Document("tenantId", "t1").append("userId", "u1").append("status", "ACTIVE")))
                .isEqualTo(4);
    }

    @Test
    void duplicateActiveEntitlementForTheSameTargetIsRejected() {
        migration.run(null);

        template.insert(entitlement("ENTRY", "e1", null), "entitlements");
        assertThatThrownBy(() -> template.insert(entitlement("ENTRY", "e1", null), "entitlements"))
                .isInstanceOf(DuplicateKeyException.class);

        template.insert(entitlement("COLLECTION", null, "c1"), "entitlements");
        assertThatThrownBy(() -> template.insert(entitlement("COLLECTION", null, "c1"), "entitlements"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void retriesAfterFailedPaymentAndRepeatedTipsCreateSeveralOrdersPerTarget() {
        migration.run(null);

        // purchase of e1: FAILED → EXPIRED → fresh PENDING
        template.insert(order("ENTRY", "e1", null, "FAILED"), "orders");
        template.insert(order("ENTRY", "e1", null, "EXPIRED"), "orders");
        template.insert(order("ENTRY", "e1", null, "PENDING"), "orders");
        // two completed tips on the same content
        template.insert(order("TIP", "e1", null, "COMPLETED"), "orders");
        template.insert(order("TIP", "e1", null, "COMPLETED"), "orders");
        // two collection purchases (collectionId mirror never collides either)
        template.insert(order("COLLECTION", null, "c1", "COMPLETED"), "orders");
        template.insert(order("COLLECTION", null, "c2", "COMPLETED"), "orders");

        assertThat(template.getCollection("orders").countDocuments(
                new Document("tenantId", "t1").append("userId", "u1"))).isEqualTo(7);
        assertThat(template.getCollection("orders").countDocuments(
                new Document("tenantId", "t1").append("userId", "u1")
                        .append("targetType", "TIP").append("status", "COMPLETED"))).isEqualTo(2);
    }

    @Test
    void secondRunIsIdempotent() {
        migration.run(null);
        Map<String, List<Document>> before = snapshot();

        migration.run(null);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void divergentHomonymFailsLoudlyAndIsNotReplaced() {
        Document divergentKeys = new Document("tenantId", 1).append("userId", 1);
        template.getCollection("entitlements").createIndex(divergentKeys,
                new IndexOptions().name("idx_ent_tenant_user_entry").unique(true));

        assertThatThrownBy(() -> migration.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idx_ent_tenant_user_entry")
                .hasMessageContaining("entitlements")
                .hasMessageContaining("different definition");

        Document stillThere = indexesByName("entitlements").get("idx_ent_tenant_user_entry");
        assertThat(stillThere.get("key", Document.class)).isEqualTo(divergentKeys);
        assertThat(stillThere.containsKey("partialFilterExpression")).isFalse();
    }

    // ── helpers ──

    private Map<String, Document> indexesByName(String collection) {
        return template.getCollection(collection).listIndexes()
                .into(new ArrayList<>()).stream()
                .collect(Collectors.toMap(d -> d.getString("name"), Function.identity()));
    }

    private Map<String, List<Document>> snapshot() {
        return COLLECTIONS.stream().collect(Collectors.toMap(Function.identity(),
                c -> template.getCollection(c).listIndexes().into(new ArrayList<>())));
    }

    private static Document entitlement(String targetType, String entryId, String collectionId) {
        Document d = new Document("tenantId", "t1").append("userId", "u1")
                .append("targetType", targetType).append("grantType", "PURCHASE")
                .append("orderId", UUID.randomUUID().toString()).append("status", "ACTIVE");
        // mirror Spring Data: null properties are omitted, not written as null
        if (entryId != null) d.append("entryId", entryId);
        if (collectionId != null) d.append("collectionId", collectionId);
        return d;
    }

    private static Document order(String targetType, String entryId, String collectionId, String status) {
        Document d = new Document("tenantId", "t1").append("userId", "u1")
                .append("targetType", targetType).append("sellerId", "s1")
                .append("status", status).append("stellarTxHash", UUID.randomUUID().toString());
        if (entryId != null) d.append("entryId", entryId);
        if (collectionId != null) d.append("collectionId", collectionId);
        return d;
    }
}
