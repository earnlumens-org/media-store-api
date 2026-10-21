package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import de.flapdoodle.embed.mongo.commands.ServerAddress;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
import de.flapdoodle.reverse.TransitionWalker;
import org.bson.Document;
import org.earnlumens.mediastore.domain.media.model.Order;
import org.earnlumens.mediastore.domain.media.model.OrderStatus;
import org.earnlumens.mediastore.domain.media.model.TargetType;
import org.earnlumens.mediastore.infrastructure.config.CoreIndexMigration;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.OrderMapperImpl;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.OrderMongoRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H10 (MYCELIUM-RESILIENCE.md): the previous-order lookups used by
 * {@code PaymentService.prepare} must return purchase orders only. Runs the
 * real derived Spring Data queries against an embedded mongod, with the H9
 * indexes in place, so the {@code targetType} filter is proven at the
 * persistence boundary rather than assumed.
 */
class OrderRepositoryImplIntegrationTest {

    private static final String T = "t1";
    private static final String U = "u1";

    private static TransitionWalker.ReachedState<RunningMongodProcess> mongod;
    private static MongoClient client;

    private MongoTemplate template;
    private OrderRepositoryImpl repository;

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
        template = new MongoTemplate(client, "h10_" + UUID.randomUUID().toString().replace("-", ""));
        new CoreIndexMigration(template).run(null);
        OrderMongoRepository mongoRepository =
                new MongoRepositoryFactory(template).getRepository(OrderMongoRepository.class);
        repository = new OrderRepositoryImpl(mongoRepository, new OrderMapperImpl(), template);
    }

    @AfterEach
    void dropDatabase() {
        template.getDb().drop();
    }

    @Test
    void entryLookupReturnsPurchaseOrdersOnlyAndIgnoresTips() {
        template.insert(order("ENTRY", "e1", null, "COMPLETED"), "orders");
        template.insert(order("ENTRY", "e1", null, "EXPIRED"), "orders");
        template.insert(order("TIP", "e1", null, "COMPLETED"), "orders");
        template.insert(order("TIP", "e1", null, "PENDING"), "orders");
        template.insert(order("ENTRY", "e2", null, "PENDING"), "orders");   // other entry
        template.insert(order("ENTRY", "e1", null, "PENDING").append("userId", "u2"), "orders"); // other buyer

        List<Order> purchases = repository.findAllByTenantIdAndUserIdAndTargetTypeAndEntryId(
                T, U, TargetType.ENTRY, "e1");

        assertThat(purchases).hasSize(2)
                .allSatisfy(o -> {
                    assertThat(o.getTargetType()).isEqualTo(TargetType.ENTRY);
                    assertThat(o.getEntryId()).isEqualTo("e1");
                    assertThat(o.getUserId()).isEqualTo(U);
                });
        assertThat(purchases).extracting(Order::getStatus)
                .containsExactlyInAnyOrder(OrderStatus.COMPLETED, OrderStatus.EXPIRED);

        // the same ledger, asked for tips, yields exactly the tips
        assertThat(repository.findAllByTenantIdAndUserIdAndTargetTypeAndEntryId(T, U, TargetType.TIP, "e1"))
                .hasSize(2)
                .allMatch(o -> o.getTargetType() == TargetType.TIP);
    }

    @Test
    void collectionLookupReturnsPurchaseOrdersOnlyAndIgnoresTips() {
        template.insert(order("COLLECTION", null, "c1", "PENDING"), "orders");
        template.insert(order("TIP", null, "c1", "COMPLETED"), "orders");
        template.insert(order("TIP", null, "c1", "PENDING"), "orders");
        template.insert(order("COLLECTION", null, "c2", "COMPLETED"), "orders"); // other collection

        List<Order> purchases = repository.findAllByTenantIdAndUserIdAndTargetTypeAndCollectionId(
                T, U, TargetType.COLLECTION, "c1");

        assertThat(purchases).hasSize(1);
        assertThat(purchases.get(0).getTargetType()).isEqualTo(TargetType.COLLECTION);
        assertThat(purchases.get(0).getCollectionId()).isEqualTo("c1");
        assertThat(purchases.get(0).getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void entryLookupDoesNotMatchCollectionOrdersThroughTheNullEntryId() {
        // A COLLECTION order has no entryId; asking for ENTRY orders of any entry must not see it.
        template.insert(order("COLLECTION", null, "c1", "COMPLETED"), "orders");

        assertThat(repository.findAllByTenantIdAndUserIdAndTargetTypeAndEntryId(T, U, TargetType.ENTRY, "e1"))
                .isEmpty();
        assertThat(repository.findAllByTenantIdAndUserIdAndTargetTypeAndCollectionId(T, U, TargetType.COLLECTION, "c1"))
                .hasSize(1);
    }

    private static Document order(String targetType, String entryId, String collectionId, String status) {
        Document d = new Document("tenantId", T).append("userId", U)
                .append("targetType", targetType).append("sellerId", "s1")
                .append("status", status).append("stellarTxHash", UUID.randomUUID().toString())
                .append("unsignedXdr", "xdr-" + targetType + "-" + status);
        // mirror Spring Data: null properties are omitted, not written as null
        if (entryId != null) d.append("entryId", entryId);
        if (collectionId != null) d.append("collectionId", collectionId);
        return d;
    }
}
