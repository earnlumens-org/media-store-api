package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import de.flapdoodle.embed.mongo.commands.ServerAddress;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
import de.flapdoodle.reverse.TransitionWalker;
import org.bson.Document;
import org.earnlumens.mediastore.domain.media.model.Asset;
import org.earnlumens.mediastore.domain.media.model.AssetStatus;
import org.earnlumens.mediastore.domain.media.model.MediaKind;
import org.earnlumens.mediastore.infrastructure.config.CoreIndexMigration;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.AssetMapperImpl;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.AssetMongoRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H8 (MYCELIUM-RESILIENCE.md): the FULL-asset lookup shared by the six
 * call-sites (CDN, public page, moderation, transcode ×2, upload) must resolve
 * <b>deterministically</b> when an entry holds two READY FULL assets — the
 * previous derived {@code Optional} query threw
 * {@code IncorrectResultSizeDataAccessException} ⇒ 5xx everywhere.
 */
class AssetRepositoryImplIntegrationTest {

    private static final String T = "t1";
    private static final String E = "e1";

    private static TransitionWalker.ReachedState<RunningMongodProcess> mongod;
    private static MongoClient client;

    private MongoTemplate template;
    private AssetRepositoryImpl repository;

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
        template = new MongoTemplate(client, "h8_" + UUID.randomUUID().toString().replace("-", ""));
        new CoreIndexMigration(template).run(null);
        AssetMongoRepository mongoRepository =
                new MongoRepositoryFactory(template).getRepository(AssetMongoRepository.class);
        repository = new AssetRepositoryImpl(mongoRepository, new AssetMapperImpl());
    }

    @AfterEach
    void dropDatabase() {
        template.getDb().drop();
    }

    private Document asset(String id, String kind, String status, LocalDateTime createdAt) {
        return new Document("_id", id)
                .append("tenantId", T)
                .append("entryId", E)
                .append("r2Key", "private/media/" + E + "/" + kind.toLowerCase() + "/" + id + ".bin")
                .append("contentType", "application/octet-stream")
                .append("fileName", id + ".bin")
                .append("kind", kind)
                .append("status", status)
                .append("createdAt", createdAt);
    }

    @Test
    void twoReadyFullAssets_resolveToTheMostRecent_withoutThrowing() {
        LocalDateTime t0 = LocalDateTime.of(2026, 1, 1, 0, 0);
        template.insert(asset("old", "FULL", "READY", t0), "assets");
        template.insert(asset("new", "FULL", "READY", t0.plusDays(1)), "assets");
        template.insert(asset("newer-but-uploaded", "FULL", "UPLOADED", t0.plusDays(2)), "assets");
        template.insert(asset("thumb", "THUMBNAIL", "READY", t0.plusDays(3)), "assets");

        Optional<Asset> resolved = repository.findByTenantIdAndEntryIdAndKindAndStatus(
                T, E, MediaKind.FULL, AssetStatus.READY);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getId()).isEqualTo("new");
        // and it is stable across calls
        assertThat(repository.findByTenantIdAndEntryIdAndKindAndStatus(T, E, MediaKind.FULL, AssetStatus.READY)
                .get().getId()).isEqualTo("new");
    }

    @Test
    void singleAssetAndNoAssetKeepWorking() {
        assertThat(repository.findByTenantIdAndEntryIdAndKindAndStatus(T, E, MediaKind.FULL, AssetStatus.READY))
                .isEmpty();
        template.insert(asset("only", "FULL", "READY", LocalDateTime.of(2026, 1, 1, 0, 0)), "assets");
        assertThat(repository.findByTenantIdAndEntryIdAndKindAndStatus(T, E, MediaKind.FULL, AssetStatus.READY)
                .map(Asset::getId)).contains("only");
        // other tenant sees nothing (scoping unchanged)
        assertThat(repository.findByTenantIdAndEntryIdAndKindAndStatus("t2", E, MediaKind.FULL, AssetStatus.READY))
                .isEmpty();
    }
}
