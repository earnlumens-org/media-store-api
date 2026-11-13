package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import de.flapdoodle.embed.mongo.commands.ServerAddress;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
import de.flapdoodle.reverse.TransitionWalker;
import org.bson.Document;
import org.earnlumens.mediastore.domain.media.model.Entitlement;
import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;
import org.earnlumens.mediastore.domain.media.model.GrantType;
import org.earnlumens.mediastore.domain.media.model.TargetType;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository.UpsertOutcome;
import org.earnlumens.mediastore.infrastructure.config.CoreIndexMigration;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.EntitlementMapperImpl;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.EntitlementMongoRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H11 / S-06 / R-30 (MYCELIUM-RESILIENCE.md): one entitlement per right.
 * Runs {@link EntitlementRepositoryImpl#upsertPurchase} against an embedded
 * mongod with the H9 unique partial indexes in place.
 */
class EntitlementRepositoryImplIntegrationTest {

    private static final String T = "t1";
    private static final String U = "u1";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 0, 0);

    private static TransitionWalker.ReachedState<RunningMongodProcess> mongod;
    private static MongoClient client;

    private MongoTemplate template;
    private EntitlementRepositoryImpl repository;

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
        template = new MongoTemplate(client, "h11_" + UUID.randomUUID().toString().replace("-", ""));
        new CoreIndexMigration(template).run(null);
        EntitlementMongoRepository mongoRepository =
                new MongoRepositoryFactory(template).getRepository(EntitlementMongoRepository.class);
        repository = new EntitlementRepositoryImpl(mongoRepository, new EntitlementMapperImpl(), template);
    }

    @AfterEach
    void dropDatabase() {
        template.getDb().drop();
    }

    private static Entitlement purchase(TargetType type, String targetId, String orderId, LocalDateTime grantedAt) {
        Entitlement e = new Entitlement();
        e.setTenantId(T);
        e.setUserId(U);
        e.setTargetType(type);
        if (type == TargetType.COLLECTION) e.setCollectionId(targetId); else e.setEntryId(targetId);
        e.setGrantType(GrantType.PURCHASE);
        e.setOrderId(orderId);
        e.setStatus(EntitlementStatus.ACTIVE);
        e.setGrantedAt(grantedAt);
        return e;
    }

    private List<Document> all() {
        return template.findAll(Document.class, "entitlements");
    }

    @Test
    void firstPurchaseInsertsExactlyAsBefore() {
        assertThat(repository.upsertPurchase(purchase(TargetType.ENTRY, "e1", "o1", T0))).isEqualTo(UpsertOutcome.CREATED);

        List<Document> docs = all();
        assertThat(docs).hasSize(1);
        Document d = docs.get(0);
        assertThat(d.getString("tenantId")).isEqualTo(T);
        assertThat(d.getString("userId")).isEqualTo(U);
        assertThat(d.getString("targetType")).isEqualTo("ENTRY");
        assertThat(d.getString("entryId")).isEqualTo("e1");
        assertThat(d.getString("grantType")).isEqualTo("PURCHASE");
        assertThat(d.getString("orderId")).isEqualTo("o1");
        assertThat(d.getString("status")).isEqualTo("ACTIVE");
        assertThat(d.get("grantedAt")).isNotNull();
        assertThat(d.getString("_class")).isEqualTo(
                org.earnlumens.mediastore.infrastructure.persistence.media.entity.EntitlementEntity.class.getName());
        // and the read side sees it
        assertThat(repository.findByTenantIdAndUserIdAndEntryIdAndStatus(T, U, "e1", EntitlementStatus.ACTIVE)).isPresent();
    }

    @Test
    void repeatedCallForTheSameOrderIsANoOp_grantedAtPreserved_r30() {
        repository.upsertPurchase(purchase(TargetType.ENTRY, "e1", "o1", T0));

        UpsertOutcome again = repository.upsertPurchase(purchase(TargetType.ENTRY, "e1", "o1", T0.plusDays(3)));

        assertThat(again).isEqualTo(UpsertOutcome.UNCHANGED);
        assertThat(all()).hasSize(1);
        Entitlement e = repository.findByTenantIdAndUserIdAndEntryIdAndStatus(T, U, "e1", EntitlementStatus.ACTIVE).get();
        assertThat(e.getGrantedAt()).isEqualTo(T0);
        assertThat(e.getOrderId()).isEqualTo("o1");
    }

    @Test
    void activeRightWithALaterOrderIsAlsoLeftUntouched() {
        repository.upsertPurchase(purchase(TargetType.ENTRY, "e1", "o1", T0));
        assertThat(repository.upsertPurchase(purchase(TargetType.ENTRY, "e1", "o2", T0.plusDays(1))))
                .isEqualTo(UpsertOutcome.UNCHANGED);
        Entitlement e = repository.findByTenantIdAndUserIdAndEntryIdAndStatus(T, U, "e1", EntitlementStatus.ACTIVE).get();
        assertThat(e.getOrderId()).isEqualTo("o1");
        assertThat(e.getGrantedAt()).isEqualTo(T0);
    }

    @Test
    void repurchaseAfterRefundReactivatesTheSameDocumentWithTheNewOrder_h11() {
        repository.upsertPurchase(purchase(TargetType.ENTRY, "e1", "o1", T0));
        Object id = all().get(0).get("_id");
        template.getCollection("entitlements").updateOne(
                new Document("_id", id), new Document("$set", new Document("status", "REVOKED")));
        assertThat(repository.findByTenantIdAndUserIdAndEntryIdAndStatus(T, U, "e1", EntitlementStatus.ACTIVE)).isEmpty();

        UpsertOutcome outcome = repository.upsertPurchase(purchase(TargetType.ENTRY, "e1", "o2", T0.plusDays(30)));

        assertThat(outcome).isEqualTo(UpsertOutcome.REACTIVATED);
        assertThat(all()).hasSize(1);
        Document d = all().get(0);
        assertThat(d.get("_id")).isEqualTo(id);
        assertThat(d.getString("status")).isEqualTo("ACTIVE");
        assertThat(d.getString("orderId")).isEqualTo("o2");
        // right is evaluated from the new purchase (D-20 / S-07)
        Entitlement e = repository.findByTenantIdAndUserIdAndEntryIdAndStatus(T, U, "e1", EntitlementStatus.ACTIVE).get();
        assertThat(e.getGrantedAt()).isEqualTo(T0.plusDays(30));
    }

    @Test
    void collectionsAreKeyedByCollectionId_andEntriesByEntryId() {
        assertThat(repository.upsertPurchase(purchase(TargetType.COLLECTION, "c1", "o1", T0))).isEqualTo(UpsertOutcome.CREATED);
        assertThat(repository.upsertPurchase(purchase(TargetType.COLLECTION, "c1", "o9", T0))).isEqualTo(UpsertOutcome.UNCHANGED);
        assertThat(repository.upsertPurchase(purchase(TargetType.COLLECTION, "c2", "o2", T0))).isEqualTo(UpsertOutcome.CREATED);
        assertThat(repository.upsertPurchase(purchase(TargetType.ENTRY, "e1", "o3", T0))).isEqualTo(UpsertOutcome.CREATED);
        assertThat(all()).hasSize(3);
        assertThat(repository.existsByTenantIdAndCollectionId(T, "c1")).isTrue();
        assertThat(repository.existsByTenantIdAndCollectionId(T, "c3")).isFalse();
        assertThat(repository.findAllCollectionEntitlements(T, U, EntitlementStatus.ACTIVE))
                .extracting(Entitlement::getCollectionId).containsExactlyInAnyOrder("c1", "c2");
    }

    @Test
    void existsByCollectionCountsRevokedRightsToo() {
        repository.upsertPurchase(purchase(TargetType.COLLECTION, "c1", "o1", T0));
        template.getCollection("entitlements").updateMany(new Document(), new Document("$set", new Document("status", "REVOKED")));
        assertThat(repository.existsByTenantIdAndCollectionId(T, "c1")).isTrue();
    }

    @Test
    void concurrentUpsertsOfTheSameRightProduceOneDocument() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<UpsertOutcome>> results = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return repository.upsertPurchase(purchase(TargetType.ENTRY, "e1", "o1", T0));
            }));
        }
        start.countDown();
        long created = 0;
        for (Future<UpsertOutcome> f : results) {
            if (f.get() == UpsertOutcome.CREATED) created++;
        }
        pool.shutdown();
        assertThat(created).isEqualTo(1);
        assertThat(all()).hasSize(1);
    }
}
