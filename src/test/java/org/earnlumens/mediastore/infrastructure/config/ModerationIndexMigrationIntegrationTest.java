package org.earnlumens.mediastore.infrastructure.config;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import de.flapdoodle.embed.mongo.commands.ServerAddress;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
import de.flapdoodle.reverse.TransitionWalker;
import org.bson.Document;
import org.earnlumens.mediastore.domain.media.model.Dispute;
import org.earnlumens.mediastore.domain.media.model.DisputeStatus;
import org.earnlumens.mediastore.infrastructure.persistence.media.adapter.DisputeRepositoryImpl;
import org.earnlumens.mediastore.infrastructure.persistence.media.entity.DisputeEntity;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.DisputeMapper;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.DisputeMongoRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H3 / D-15 acceptance against a real mongod: the moderation indexes exist
 * with the right definitions, the dispute unique key is enforced, and the
 * {@code openIfAbsent} upsert is idempotent and race-safe without ever
 * overwriting an administrator's resolution.
 */
class ModerationIndexMigrationIntegrationTest {

    private static TransitionWalker.ReachedState<RunningMongodProcess> mongod;
    private static MongoClient client;

    private MongoTemplate template;
    private ModerationIndexMigration migration;
    private DisputeRepositoryImpl disputes;

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
        template = new MongoTemplate(client, "h3_" + UUID.randomUUID().toString().replace("-", ""));
        migration = new ModerationIndexMigration(template);
        DisputeMongoRepository mongoRepo = new MongoRepositoryFactory(template).getRepository(DisputeMongoRepository.class);
        disputes = new DisputeRepositoryImpl(mongoRepo, template, Mappers.getMapper(DisputeMapper.class));
    }

    @AfterEach
    void dropDatabase() {
        template.getDb().drop();
    }

    @Test
    void createsEveryModerationIndexAndIsIdempotent() {
        migration.run(null);
        migration.run(null);

        for (ModerationIndexMigration.IndexSpec spec : ModerationIndexMigration.specs()) {
            Document index = indexesByName(spec.collection()).get(spec.name());
            assertThat(index).as("%s.%s exists", spec.collection(), spec.name()).isNotNull();
            assertThat(index.get("key", Document.class)).isEqualTo(spec.keys());
        }
        Document unique = indexesByName("disputes").get("idx_dispute_case_entitlement");
        assertThat(unique.getBoolean("unique")).isTrue();
        Document pending = indexesByName("entries").get("idx_tenant_pending_submitted");
        assertThat(pending.get("partialFilterExpression", Document.class))
                .isEqualTo(new Document("pendingVersion", new Document("$exists", true)));
    }

    @Test
    void divergentHomonymousIndexAbortsStartup() {
        template.getCollection("disputes").createIndex(
                new Document("tenantId", 1).append("moderationCaseId", 1).append("entitlementId", 1),
                new com.mongodb.client.model.IndexOptions().name("idx_dispute_case_entitlement")); // not unique

        assertThatThrownBy(() -> migration.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idx_dispute_case_entitlement");
    }

    @Test
    void uniqueKeyRejectsASecondDisputeForTheSamePurchaseAndCase() {
        migration.run(null);
        template.insert(rawDispute("t1", "case-1", "ent-1"), "disputes");
        assertThatThrownBy(() -> template.insert(rawDispute("t1", "case-1", "ent-1"), "disputes"))
                .isInstanceOf(DuplicateKeyException.class);
        // Same purchase, different case ⇒ a new dispute is legitimate
        template.insert(rawDispute("t1", "case-2", "ent-1"), "disputes");
        // Same case, same entitlement id, other tenant ⇒ isolated
        template.insert(rawDispute("t2", "case-1", "ent-1"), "disputes");
        assertThat(template.getCollection("disputes").countDocuments()).isEqualTo(3);
    }

    @Test
    void openIfAbsentIsIdempotentAndNeverOverwritesAResolution() {
        migration.run(null);
        Dispute d = dispute("t1", "case-1", "ent-1", List.of("A"));

        assertThat(disputes.openIfAbsent(d)).isTrue();
        assertThat(disputes.openIfAbsent(dispute("t1", "case-1", "ent-1", List.of("A", "B")))).isFalse();

        List<Dispute> stored = disputes.findByTenantIdAndModerationCaseId("t1", "case-1");
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getAffectedEntryIds()).containsExactly("A");
        assertThat(stored.get(0).getStatus()).isEqualTo(DisputeStatus.OPEN);

        // Administrator resolves it; a later re-run (sweep) must not reopen it
        template.updateFirst(new Query(Criteria.where("_id").is(new org.bson.types.ObjectId(stored.get(0).getId()))),
                new org.springframework.data.mongodb.core.query.Update().set("status", "DISMISSED"), DisputeEntity.class);
        assertThat(disputes.openIfAbsent(dispute("t1", "case-1", "ent-1", List.of("A")))).isFalse();
        assertThat(disputes.findByTenantIdAndModerationCaseId("t1", "case-1").get(0).getStatus())
                .isEqualTo(DisputeStatus.DISMISSED);
        assertThat(disputes.findByTenantIdAndUserIdAndStatus("t1", "buyer", DisputeStatus.OPEN)).isEmpty();
    }

    @Test
    void concurrentOpenIfAbsentCreatesExactlyOneDocument() throws Exception {
        migration.run(null);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                tasks.add(() -> disputes.openIfAbsent(dispute("t1", "case-race", "ent-race", List.of("A"))));
            }
            int created = 0;
            for (Future<Boolean> f : pool.invokeAll(tasks)) {
                if (f.get()) created++;
            }
            assertThat(created).isEqualTo(1);
            assertThat(template.getCollection("disputes")
                    .countDocuments(new Document("moderationCaseId", "case-race"))).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private static Dispute dispute(String tenant, String caseId, String entitlementId, List<String> affected) {
        Dispute d = new Dispute();
        d.setTenantId(tenant);
        d.setModerationCaseId(caseId);
        d.setEntitlementId(entitlementId);
        d.setOrderId("order-" + entitlementId);
        d.setUserId("buyer");
        d.setEntryId(affected.get(0));
        d.setAffectedEntryIds(new ArrayList<>(affected));
        d.setReason("reason");
        d.setStatus(DisputeStatus.OPEN);
        d.setCreatedAt(LocalDateTime.now());
        return d;
    }

    private static Document rawDispute(String tenant, String caseId, String entitlementId) {
        return new Document("tenantId", tenant)
                .append("moderationCaseId", caseId)
                .append("entitlementId", entitlementId)
                .append("userId", "buyer")
                .append("status", "OPEN");
    }

    private Map<String, Document> indexesByName(String collection) {
        return template.getCollection(collection).listIndexes().into(new ArrayList<>()).stream()
                .collect(Collectors.toMap(d -> d.getString("name"), Function.identity()));
    }
}
