package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure search helpers behind the text-index search
 * (SCALABILITY-AUDIT.md Phase 2, task 2.1 — P0-2): the {@code $text} query
 * builder, the relevance merge comparator that must mirror the Mongo-side
 * sort, and the two-list merge used to combine the entries and collections
 * aggregations.
 */
class EntryMongoRepositoryCustomImplSearchTest {

    // ── buildTextSearchQuery ────────────────────────────────────────────────

    @Test
    void textQuery_quotesEveryTokenForAndSemantics() {
        assertEquals("\"guitar\" \"lessons\"",
                EntryMongoRepositoryCustomImpl.buildTextSearchQuery("guitar lessons"));
    }

    @Test
    void textQuery_capsTokensAtSix() {
        String query = EntryMongoRepositoryCustomImpl.buildTextSearchQuery("a b c d e f g h");
        assertEquals("\"a\" \"b\" \"c\" \"d\" \"e\" \"f\"", query);
    }

    @Test
    void textQuery_stripsEmbeddedQuotesSoTokensCannotBreakPhraseSyntax() {
        assertEquals("\"guitar\"",
                EntryMongoRepositoryCustomImpl.buildTextSearchQuery("gui\"tar"));
    }

    @Test
    void textQuery_blankOrQuoteOnlyInputYieldsEmpty() {
        assertEquals("", EntryMongoRepositoryCustomImpl.buildTextSearchQuery(null));
        assertEquals("", EntryMongoRepositoryCustomImpl.buildTextSearchQuery("   "));
        assertEquals("", EntryMongoRepositoryCustomImpl.buildTextSearchQuery("\"\""));
    }

    @Test
    void textQuery_collapsesExtraWhitespace() {
        assertEquals("\"stellar\" \"payments\"",
                EntryMongoRepositoryCustomImpl.buildTextSearchQuery("  stellar   payments  "));
    }

    // ── searchComparator (must mirror buildSearchSortKeys) ─────────────────

    private static Document doc(double score, long views, long dateMillis) {
        return new Document("searchScore", score)
                .append("viewCount", views)
                .append("sortDate", new Date(dateMillis));
    }

    @Test
    void relevance_ordersByWeightedScoreFirst() {
        Document highScore = doc(9.5, 10, 1_000);
        Document lowScore = doc(2.0, 99_999, 9_000);

        Comparator<Document> cmp = EntryMongoRepositoryCustomImpl.searchComparator("relevance");
        assertTrue(cmp.compare(highScore, lowScore) < 0,
                "higher text score must rank first regardless of popularity");
    }

    @Test
    void relevance_breaksScoreTiesByViewsThenDate() {
        Document moreViews = doc(5.0, 500, 1_000);
        Document fewerViews = doc(5.0, 100, 9_000);
        Document newer = doc(5.0, 100, 9_999);

        Comparator<Document> cmp = EntryMongoRepositoryCustomImpl.searchComparator("relevance");
        assertTrue(cmp.compare(moreViews, fewerViews) < 0, "score tie → more views first");
        assertTrue(cmp.compare(newer, fewerViews) < 0, "score+views tie → newer first");
    }

    @Test
    void relevance_missingScoreSortsLast() {
        Document scored = doc(0.5, 0, 0);
        Document unscored = new Document("viewCount", 1_000_000L).append("sortDate", new Date(9_999));

        Comparator<Document> cmp = EntryMongoRepositoryCustomImpl.searchComparator(null);
        assertTrue(cmp.compare(scored, unscored) < 0);
    }

    @Test
    void newestAndOldest_orderBySortDate() {
        Document older = doc(9.0, 9_999, 1_000);
        Document newer = doc(1.0, 0, 9_000);

        assertTrue(EntryMongoRepositoryCustomImpl.searchComparator("newest").compare(newer, older) < 0);
        assertTrue(EntryMongoRepositoryCustomImpl.searchComparator("oldest").compare(older, newer) < 0);
    }

    @Test
    void views_ordersByViewCountThenDate() {
        Document popular = doc(1.0, 500, 1_000);
        Document recent = doc(9.0, 100, 9_000);

        assertTrue(EntryMongoRepositoryCustomImpl.searchComparator("views").compare(popular, recent) < 0);
    }

    @Test
    void missingSortDateSortsLastOnNewest() {
        Document dated = doc(1.0, 0, 1);
        Document undated = new Document("searchScore", 9.0).append("viewCount", 9_999L);

        assertTrue(EntryMongoRepositoryCustomImpl.searchComparator("newest").compare(dated, undated) < 0);
    }

    // ── mergeSorted ─────────────────────────────────────────────────────────

    @Test
    void mergeSorted_interleavesBothListsByComparator() {
        Comparator<Document> cmp = EntryMongoRepositoryCustomImpl.searchComparator("relevance");
        List<Document> entries = List.of(doc(9.0, 0, 0), doc(3.0, 0, 0));
        List<Document> collections = List.of(doc(5.0, 0, 0), doc(1.0, 0, 0));

        List<Document> merged = EntryMongoRepositoryCustomImpl.mergeSorted(entries, collections, cmp);

        assertEquals(List.of(9.0, 5.0, 3.0, 1.0),
                merged.stream().map(d -> d.getDouble("searchScore")).toList());
    }

    @Test
    void mergeSorted_isStable_entriesWinTies() {
        Comparator<Document> cmp = EntryMongoRepositoryCustomImpl.searchComparator("relevance");
        Document entry = doc(5.0, 10, 1_000).append("kind", "entry");
        Document collection = doc(5.0, 10, 1_000).append("kind", "collection");

        List<Document> merged = EntryMongoRepositoryCustomImpl.mergeSorted(
                List.of(entry), List.of(collection), cmp);

        assertEquals("entry", merged.get(0).getString("kind"));
        assertEquals("collection", merged.get(1).getString("kind"));
    }

    @Test
    void mergeSorted_handlesEmptySides() {
        Comparator<Document> cmp = EntryMongoRepositoryCustomImpl.searchComparator("relevance");
        List<Document> only = List.of(doc(1.0, 0, 0));

        assertEquals(only, EntryMongoRepositoryCustomImpl.mergeSorted(only, List.of(), cmp));
        assertEquals(only, EntryMongoRepositoryCustomImpl.mergeSorted(List.of(), only, cmp));
    }

    // ── escapeSimplePrefixRegex ─────────────────────────────────────────────

    @Test
    void prefixEscape_leavesSimplePrefixesUntouched() {
        assertEquals("guitar01_x", EntryMongoRepositoryCustomImpl.escapeSimplePrefixRegex("guitar01_x"));
        assertEquals("música", EntryMongoRepositoryCustomImpl.escapeSimplePrefixRegex("música"));
    }

    @Test
    void prefixEscape_escapesRegexMetacharacters() {
        assertEquals("a\\.b\\*c\\(d", EntryMongoRepositoryCustomImpl.escapeSimplePrefixRegex("a.b*c(d"));
        assertEquals("dj\\ max", EntryMongoRepositoryCustomImpl.escapeSimplePrefixRegex("dj max"));
    }
}
