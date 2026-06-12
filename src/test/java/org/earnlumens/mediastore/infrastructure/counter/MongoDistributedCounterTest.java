package org.earnlumens.mediastore.infrastructure.counter;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.util.Date;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MongoDistributedCounter}: the pure helpers (id format,
 * count extraction) and the atomic increment contract — upsert semantics,
 * duplicate-key retry under racing upserts, and the "report failure as empty,
 * never throw" rule that the fail-open/fail-closed call sites rely on.
 */
class MongoDistributedCounterTest {

    private MongoTemplate mongoTemplate;
    private MongoDistributedCounter counter;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        counter = new MongoDistributedCounter(mongoTemplate);
    }

    private static Document doc(long count) {
        return new Document("count", count);
    }

    @Nested
    class PureHelpers {

        @Test
        void counterId_isScopeKeyWindow() {
            assertEquals("auth:1.2.3.4:29671234",
                    MongoDistributedCounter.counterId("auth", "1.2.3.4", 29671234L));
        }

        @Test
        void extractCount_readsIntegerAndLongCounts() {
            assertEquals(OptionalLong.of(7),
                    MongoDistributedCounter.extractCount(new Document("count", 7)));
            assertEquals(OptionalLong.of(7),
                    MongoDistributedCounter.extractCount(new Document("count", 7L)));
        }

        @Test
        void extractCount_emptyOnNullOrMalformedDocument() {
            assertTrue(MongoDistributedCounter.extractCount(null).isEmpty());
            assertTrue(MongoDistributedCounter.extractCount(new Document()).isEmpty());
            assertTrue(MongoDistributedCounter.extractCount(new Document("count", "oops")).isEmpty());
        }
    }

    @Nested
    class IncrementContract {

        @Test
        void returnsPostIncrementCount() {
            when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                    any(FindAndModifyOptions.class), eq(Document.class),
                    eq(MongoDistributedCounter.COLLECTION)))
                    .thenReturn(doc(3));

            OptionalLong result = counter.incrementAndGet("auth", "1.2.3.4", 100, Duration.ofMinutes(2));

            assertEquals(OptionalLong.of(3), result);
        }

        @Test
        void targetsTheCompositeIdWithUpsertAndInc() {
            when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                    any(FindAndModifyOptions.class), eq(Document.class),
                    eq(MongoDistributedCounter.COLLECTION)))
                    .thenReturn(doc(1));

            counter.incrementAndGet("search", "5.6.7.8", 42, Duration.ofHours(1));

            var queryCaptor = org.mockito.ArgumentCaptor.forClass(Query.class);
            var updateCaptor = org.mockito.ArgumentCaptor.forClass(Update.class);
            verify(mongoTemplate).findAndModify(queryCaptor.capture(), updateCaptor.capture(),
                    any(FindAndModifyOptions.class), eq(Document.class),
                    eq(MongoDistributedCounter.COLLECTION));

            assertEquals("search:5.6.7.8:42",
                    queryCaptor.getValue().getQueryObject().get("_id"));
            Document updateDoc = updateCaptor.getValue().getUpdateObject();
            assertEquals(1, ((Document) updateDoc.get("$inc")).get("count"));
            assertInstanceOf(Date.class, ((Document) updateDoc.get("$setOnInsert")).get("expiresAt"));
        }

        @Test
        void retriesOnceOnDuplicateKeyRace() {
            when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                    any(FindAndModifyOptions.class), eq(Document.class),
                    eq(MongoDistributedCounter.COLLECTION)))
                    .thenThrow(new DuplicateKeyException("raced upsert"))
                    .thenReturn(doc(2));

            OptionalLong result = counter.incrementAndGet("auth", "1.2.3.4", 100, Duration.ofMinutes(2));

            assertEquals(OptionalLong.of(2), result);
            verify(mongoTemplate, times(2)).findAndModify(any(Query.class), any(Update.class),
                    any(FindAndModifyOptions.class), eq(Document.class),
                    eq(MongoDistributedCounter.COLLECTION));
        }

        @Test
        void emptyWhenBackendFails_neverThrows() {
            when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                    any(FindAndModifyOptions.class), eq(Document.class),
                    eq(MongoDistributedCounter.COLLECTION)))
                    .thenThrow(new RuntimeException("mongo down"));

            OptionalLong result = assertDoesNotThrow(
                    () -> counter.incrementAndGet("auth", "1.2.3.4", 100, Duration.ofMinutes(2)));

            assertTrue(result.isEmpty());
        }

        @Test
        void emptyWhenRetryAlsoFails() {
            when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                    any(FindAndModifyOptions.class), eq(Document.class),
                    eq(MongoDistributedCounter.COLLECTION)))
                    .thenThrow(new DuplicateKeyException("raced upsert"))
                    .thenThrow(new RuntimeException("mongo down"));

            OptionalLong result = assertDoesNotThrow(
                    () -> counter.incrementAndGet("auth", "1.2.3.4", 100, Duration.ofMinutes(2)));

            assertTrue(result.isEmpty());
        }
    }
}
