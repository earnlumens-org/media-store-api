package org.earnlumens.mediastore.infrastructure.persistence.media.adapter;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import de.flapdoodle.embed.mongo.commands.ServerAddress;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
import de.flapdoodle.reverse.TransitionWalker;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.earnlumens.mediastore.domain.media.model.EntryType;
import org.earnlumens.mediastore.domain.media.model.ModerationJob;
import org.earnlumens.mediastore.domain.media.model.ModerationJobStatus;
import org.earnlumens.mediastore.infrastructure.persistence.media.mapper.ModerationJobMapperImpl;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.ModerationJobMongoRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MYCELIUM-RESILIENCE.md rev. 28 — legacy {@code String} {@code _id} on
 * {@code moderation_jobs}.
 *
 * <p>Production had one job written in 2026-04 with a 24-hex {@code String}
 * {@code _id}. {@code MongoRepository.save} re-encoded that id as an
 * {@code ObjectId} and upserted a second document, so the watchdog re-found
 * the untouched legacy one on every sweep and logged a DEAD error every
 * ~90 s. These tests run {@link ModerationJobRepositoryImpl} against an
 * embedded mongod with both id representations and pin:
 * <ul>
 *   <li>save() of an existing job replaces it in place — same {@code _id},
 *       same type, same document count — for both representations;</li>
 *   <li>after the watchdog closes a stale job it is no longer stale
 *       (idempotent sweep);</li>
 *   <li>findByTenantIdAndId / claimCompletion reach both representations;</li>
 *   <li>a new job is inserted once with an {@code ObjectId};</li>
 *   <li>a job whose id matches nothing is not resurrected.</li>
 * </ul>
 */
class ModerationJobRepositoryImplIntegrationTest {

    /** How the {@code _id} of a pre-existing job is stored. */
    enum StoredId { OBJECT_ID, LEGACY_STRING }

    private static final String T = "earnlumens";
    private static final LocalDateTime APRIL = LocalDateTime.of(2026, 4, 13, 17, 9, 26);

    private static TransitionWalker.ReachedState<RunningMongodProcess> mongod;
    private static MongoClient client;

    private MongoTemplate template;
    private ModerationJobRepositoryImpl repository;
    private MongoCollection<Document> raw;

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
        template = new MongoTemplate(client, "modjobs_" + UUID.randomUUID().toString().replace("-", ""));
        ModerationJobMongoRepository mongoRepository =
                new MongoRepositoryFactory(template).getRepository(ModerationJobMongoRepository.class);
        repository = new ModerationJobRepositoryImpl(mongoRepository, new ModerationJobMapperImpl(), template);
        raw = template.getCollection(ModerationJobRepositoryImpl.COLLECTION);
    }

    @AfterEach
    void dropDatabase() {
        template.getDb().drop();
    }

    // ── fixtures ─────────────────────────────────────────────────

    /** Writes a stale PROCESSING job directly, with the requested _id type; returns its hex id. */
    private String seedStaleJob(StoredId storedId) {
        ObjectId oid = new ObjectId();
        Object id = storedId == StoredId.LEGACY_STRING ? oid.toHexString() : oid;
        raw.insertOne(new Document("_id", id)
                .append("tenantId", T)
                .append("entryId", new ObjectId().toHexString())
                .append("entryType", EntryType.VIDEO.name())
                .append("status", ModerationJobStatus.PROCESSING.name())
                .append("retryCount", 0)
                .append("maxRetries", 2)
                .append("lastHeartbeat", null)
                .append("createdAt", toDate(APRIL))
                .append("updatedAt", toDate(APRIL)));
        return oid.toHexString();
    }

    private Date toDate(LocalDateTime t) {
        return template.getConverter().getConversionService().convert(t, Date.class);
    }

    private Document storedById(String hexId) {
        List<Document> matches = raw.find(ModerationJobRepositoryImpl.idFilter(hexId)).into(new java.util.ArrayList<>());
        assertThat(matches).as("exactly one document for id %s", hexId).hasSize(1);
        return matches.get(0);
    }

    private static void assertIdType(Document doc, StoredId expected) {
        Object id = doc.get("_id");
        if (expected == StoredId.LEGACY_STRING) {
            assertThat(id).isInstanceOf(String.class);
        } else {
            assertThat(id).isInstanceOf(ObjectId.class);
        }
    }

    // ── save() replaces in place for both id types ───────────────

    @ParameterizedTest
    @EnumSource(StoredId.class)
    void save_ofExistingJob_replacesInPlace_keepingStoredIdType(StoredId storedId) {
        String id = seedStaleJob(storedId);
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(1);

        List<ModerationJob> stale = repository.findAllStaleJobs(cutoff, 10);
        assertThat(stale).hasSize(1);
        ModerationJob job = stale.get(0);
        assertThat(job.getId()).isEqualTo(id);

        // What the watchdog's killJob does.
        job.setStatus(ModerationJobStatus.DEAD);
        job.setErrorMessage("orphan job closed by watchdog");
        job.setCompletedAt(LocalDateTime.now());
        ModerationJob saved = repository.save(job);

        assertThat(saved.getId()).isEqualTo(id);
        assertThat(raw.countDocuments()).as("no duplicate document").isEqualTo(1);
        Document stored = storedById(id);
        assertIdType(stored, storedId);
        assertThat(stored.getString("status")).isEqualTo("DEAD");
        assertThat(stored.getString("errorMessage")).isEqualTo("orphan job closed by watchdog");
        assertThat(stored.get("completedAt")).isNotNull();
        assertThat(stored.getDate("createdAt")).isEqualTo(toDate(APRIL));
        assertThat(stored.getDate("updatedAt")).isAfter(toDate(APRIL));

        // Idempotent sweep: the job is no longer stale.
        assertThat(repository.findAllStaleJobs(cutoff, 10)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(StoredId.class)
    void save_retryPath_requeuesInPlace(StoredId storedId) {
        String id = seedStaleJob(storedId);
        ModerationJob job = repository.findAllStaleJobs(LocalDateTime.now(), 10).get(0);

        // What the watchdog's retryJob does.
        job.setStatus(ModerationJobStatus.PENDING);
        job.setRetryCount(1);
        job.setLastHeartbeat(null);
        repository.save(job);

        assertThat(raw.countDocuments()).isEqualTo(1);
        Document stored = storedById(id);
        assertIdType(stored, storedId);
        assertThat(stored.getString("status")).isEqualTo("PENDING");
        assertThat(stored.getInteger("retryCount")).isEqualTo(1);
        assertThat(repository.findAllByStatus(ModerationJobStatus.PENDING, 10))
                .extracting(ModerationJob::getId).containsExactly(id);
    }

    // ── id-based reads and CAS reach both representations ────────

    @ParameterizedTest
    @EnumSource(StoredId.class)
    void findByTenantIdAndId_findsBothRepresentations_andIsTenantScoped(StoredId storedId) {
        String id = seedStaleJob(storedId);

        assertThat(repository.findByTenantIdAndId(T, id)).isPresent()
                .get().extracting(ModerationJob::getId).isEqualTo(id);
        assertThat(repository.findByTenantIdAndId("other-tenant", id)).isEmpty();
        assertThat(repository.findByTenantIdAndId(T, new ObjectId().toHexString())).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(StoredId.class)
    void claimCompletion_appliesOnce_forBothRepresentations(StoredId storedId) {
        String id = seedStaleJob(storedId);

        assertThat(repository.claimCompletion(T, id)).as("first callback wins").isTrue();
        assertThat(repository.claimCompletion(T, id)).as("duplicate callback is a no-op").isFalse();

        Document stored = storedById(id);
        assertIdType(stored, storedId);
        assertThat(stored.getString("status")).isEqualTo("COMPLETED");
        assertThat(raw.countDocuments()).isEqualTo(1);
    }

    // ── inserts and non-resurrection ─────────────────────────────

    @Test
    void save_ofNewJob_insertsOnce_withObjectId() {
        ModerationJob job = new ModerationJob();
        job.setTenantId(T);
        job.setEntryId(new ObjectId().toHexString());
        job.setEntryType(EntryType.IMAGE);
        job.setStatus(ModerationJobStatus.PENDING);
        job.setMaxRetries(2);

        ModerationJob saved = repository.save(job);

        assertThat(saved.getId()).isNotNull();
        assertThat(ObjectId.isValid(saved.getId())).isTrue();
        assertThat(raw.countDocuments()).isEqualTo(1);
        assertIdType(storedById(saved.getId()), StoredId.OBJECT_ID);

        // A second save of the same (now existing) job updates in place.
        saved.setStatus(ModerationJobStatus.DISPATCHED);
        repository.save(saved);
        assertThat(raw.countDocuments()).isEqualTo(1);
        assertThat(storedById(saved.getId()).getString("status")).isEqualTo("DISPATCHED");
    }

    @Test
    void save_ofJobWhoseIdMatchesNothing_doesNotResurrectIt() {
        ModerationJob ghost = new ModerationJob();
        ghost.setId(new ObjectId().toHexString());
        ghost.setTenantId(T);
        ghost.setEntryId(new ObjectId().toHexString());
        ghost.setStatus(ModerationJobStatus.DEAD);

        ModerationJob returned = repository.save(ghost);

        assertThat(returned).isSameAs(ghost);
        assertThat(raw.countDocuments()).isZero();
    }

    @Test
    void idFilter_matchesEitherRepresentation_andPlainStringsAsIs() {
        ObjectId oid = new ObjectId();
        raw.insertOne(new Document("_id", oid).append("tenantId", T));
        raw.insertOne(new Document("_id", "not-an-object-id").append("tenantId", T));

        assertThat(raw.countDocuments(ModerationJobRepositoryImpl.idFilter(oid.toHexString()))).isEqualTo(1);
        assertThat(raw.countDocuments(ModerationJobRepositoryImpl.idFilter("not-an-object-id"))).isEqualTo(1);
        assertThat(raw.countDocuments(ModerationJobRepositoryImpl.idFilter(new ObjectId().toHexString()))).isZero();
    }
}
