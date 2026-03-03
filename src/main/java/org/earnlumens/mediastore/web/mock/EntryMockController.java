package org.earnlumens.mediastore.web.mock;

import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * Mock controller for the main Home feed — a realistic mix of:
 *   • Regular users WITHOUT badge  (~50 %)
 *   • Community creators WITH blue badge u1  (~30 %)
 *   • Ecosystem members WITH gold badge u2  (~20 %)
 */
@RestController
@RequestMapping("/api/mock/entries")
public class EntryMockController {

    private static final int MAX_PAGE_SIZE = 48;
    private static final String[] ENTRY_TYPES = {"video", "audio", "resource", "image"};
    private static final String[] COLLECTION_TYPES = {
        "series", "course", "library", "list", "album",
        "bundle", "catalog", "volume", "archive"
    };

    // ── Tier 1: Plain users (no badge) ────────────────────────────────
    private static final String[] PLAIN_USERNAMES = {
        "lightning", "cinder", "wave_crash", "codeflicker", "solar_loop",
        "phantom", "glitchy", "stormpulse", "riser", "echovibe",
        "blip", "fusion", "skydive", "boltflash", "hazedream",
        "sarah", "alicia", "nadia", "mateo", "nicogon", "jordan",
    };

    // ── Tier 2: Community creators (blue badge u1) ────────────────────
    // { username, gender, portraitIndex }
    private static final String[][] COMMUNITY_USERS = {
        {"cloudvibe",   "men",   "12"},
        {"pixeldust",   "women", "44"},
        {"starbyte",    "men",   "67"},
        {"neonwave",    "women", "22"},
        {"frostbit",    "men",   "33"},
        {"lunar_echo",  "women", "58"},
        {"blazepath",   "men",   "41"},
        {"cosmodrift",  "women", "15"},
        {"sky_bolt",    "men",   "78"},
        {"novaspark",   "women", "31"},
        {"techhaze",    "men",   "55"},
        {"glimmer",     "women", "7"},
        {"voidwisp",    "men",   "92"},
        {"rocketglow",  "women", "63"},
        {"duskreader",  "men",   "19"},
        {"quantum",     "women", "48"},
        {"twist",       "men",   "45"},
        {"radiant",     "women", "36"},
        {"nebulax",     "men",   "51"},
        {"bytestorm",   "women", "66"},
    };

    // ── Tier 3: Ecosystem members (gold badge u2) ─────────────────────
    private static final String GH    = "https://github.com/";
    private static final String GH_SZ = ".png?size=200";
    private static final String[][] ECOSYSTEM_USERS = {
        {"stellarorg",       GH + "stellar" + GH_SZ},
        {"lobstr",           GH + "Lobstrco" + GH_SZ},
        {"circle",           GH + "circlefin" + GH_SZ},
        {"moneygram",        GH + "moneygram" + GH_SZ},
        {"franklintempleton","https://pbs.twimg.com/profile_images/1817831714527334400/zpEkqYrh_400x400.jpg"},
        {"ultrastellar",     "https://pbs.twimg.com/profile_images/1357309147470041090/UeZllGx9_400x400.png"},
        {"aqua",             GH + "AquaToken" + GH_SZ},
        {"stellarx",         "https://pbs.twimg.com/profile_images/1014666925329342465/RN8goCHy_400x400.jpg"},
        {"vesseo",           "https://pbs.twimg.com/profile_images/1914727632547016705/GwEDbRFw_400x400.png"},
        {"soroswap",         GH + "soroswap" + GH_SZ},
        {"script3",          GH + "script3" + GH_SZ},
        {"bitso",            "https://pbs.twimg.com/profile_images/1981835148455723008/Kfo4IqcN_400x400.jpg"},
        {"ledger",           "https://pbs.twimg.com/profile_images/1522608646043181060/QQRjVYhi_400x400.jpg"},
        {"allbridge",        "https://pbs.twimg.com/profile_images/1667191904545505281/bbm43TM7_400x400.jpg"},
        {"airtm",            "https://pbs.twimg.com/profile_images/1911878834493808640/T5v1FSgm_400x400.jpg"},
    };

    /**
     * Get a paginated feed of mixed entries and collections.
     */
    @GetMapping("/feed")
    public Map<String, Object> getFeed(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "24") int size
    ) {
        size = Math.min(size, MAX_PAGE_SIZE);
        int totalElements = 200;
        int totalPages = (int) Math.ceil((double) totalElements / size);

        List<Map<String, Object>> items = new ArrayList<>();
        Random random = new Random();

        for (int i = 1; i <= size; i++) {
            int index = page * size + i;
            if (index > totalElements) break;

            // Randomly decide if it's an entry or a collection (70% entries, 30% collections)
            boolean isEntry = random.nextDouble() < 0.7;

            if (isEntry) {
                items.add(generateRandomEntry(random));
            } else {
                items.add(generateRandomCollection(random));
            }
        }

        return buildPagedResponse(items, page, size, totalElements, totalPages);
    }

    /**
     * Get a paginated list of entries only.
     */
    @GetMapping("/entries")
    public Map<String, Object> getEntries(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "24") int size,
            @RequestParam(name = "type", required = false) String type
    ) {
        size = Math.min(size, MAX_PAGE_SIZE);
        int totalElements = 150;
        int totalPages = (int) Math.ceil((double) totalElements / size);

        List<Map<String, Object>> entries = new ArrayList<>();
        Random random = new Random();

        for (int i = 1; i <= size; i++) {
            int index = page * size + i;
            if (index > totalElements) break;

            Map<String, Object> entry = generateRandomEntry(random);
            
            // Filter by type if specified
            if (type != null && !type.isEmpty()) {
                entry.put("type", type);
            }

            entries.add(entry);
        }

        return buildPagedResponse(entries, page, size, totalElements, totalPages);
    }

    /**
     * Get a paginated list of collections only.
     */
    @GetMapping("/collections")
    public Map<String, Object> getCollections(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "24") int size,
            @RequestParam(name = "collectionType", required = false) String collectionType
    ) {
        size = Math.min(size, MAX_PAGE_SIZE);
        int totalElements = 80;
        int totalPages = (int) Math.ceil((double) totalElements / size);

        List<Map<String, Object>> collections = new ArrayList<>();
        Random random = new Random();

        for (int i = 1; i <= size; i++) {
            int index = page * size + i;
            if (index > totalElements) break;

            Map<String, Object> collection = generateRandomCollection(random);
            
            // Filter by collectionType if specified
            if (collectionType != null && !collectionType.isEmpty()) {
                collection.put("collectionType", collectionType);
            }

            collections.add(collection);
        }

        return buildPagedResponse(collections, page, size, totalElements, totalPages);
    }

    /**
     * Get a single entry by ID (mock).
     * Optionally force a specific type for testing.
     */
    @GetMapping("/entry/{id}")
    public Map<String, Object> getEntryById(
            @PathVariable("id") String id,
            @RequestParam(name = "type", required = false) String type
    ) {
        Random random = new Random(id.hashCode()); // Deterministic based on ID
        Map<String, Object> entry = generateRandomEntry(random);
        entry.put("id", id);
        
        // Allow forcing type for testing purposes
        if (type != null && !type.isEmpty()) {
            entry.put("type", type);
            // Add duration for video/audio if forced
            if (("video".equals(type) || "audio".equals(type)) && !entry.containsKey("durationSec")) {
                entry.put("durationSec", random.nextInt(7200) + 30);
            }
        }
        
        return entry;
    }

    /**
     * Get a single collection by ID (mock).
     */
    @GetMapping("/collection/{id}")
    public Map<String, Object> getCollectionById(@PathVariable("id") String id) {
        Random random = new Random(id.hashCode()); // Deterministic based on ID
        Map<String, Object> collection = generateRandomCollection(random);
        collection.put("id", id);
        return collection;
    }

    // ========== Private Helper Methods ==========

    /**
     * Pick a random author tier:
     *   ~50 % plain user (no badge)
     *   ~30 % community (u1 blue)
     *   ~20 % ecosystem (u2 gold)
     * Returns: { username, avatarUrl|null, badge|null }
     */
    private String[] pickAuthor(Random random) {
        double roll = random.nextDouble();

        if (roll < 0.50) {
            // ── Plain user (no badge) ──
            String username = PLAIN_USERNAMES[random.nextInt(PLAIN_USERNAMES.length)];
            int avatarNum = random.nextInt(99) + 1;
            String gender = random.nextBoolean() ? "men" : "women";
            boolean hasAvatar = random.nextDouble() < 0.75;
            String avatar = hasAvatar
                ? "https://randomuser.me/api/portraits/" + gender + "/" + avatarNum + ".jpg"
                : null;
            return new String[]{username, avatar, null};

        } else if (roll < 0.80) {
            // ── Community creator (blue badge u1) ──
            String[] cu = COMMUNITY_USERS[random.nextInt(COMMUNITY_USERS.length)];
            String avatar = "https://randomuser.me/api/portraits/" + cu[1] + "/" + cu[2] + ".jpg";
            return new String[]{cu[0], avatar, "u1"};

        } else {
            // ── Ecosystem member (gold badge u2) ──
            String[] eu = ECOSYSTEM_USERS[random.nextInt(ECOSYSTEM_USERS.length)];
            return new String[]{eu[0], eu[1], "u2"};
        }
    }

    private Map<String, Object> generateRandomEntry(Random random) {
        Map<String, Object> entry = new LinkedHashMap<>();

        String[] author = pickAuthor(random);
        String entryType = ENTRY_TYPES[random.nextInt(ENTRY_TYPES.length)];
        int imageNum = getValidImageNumber(random);
        boolean locked = random.nextDouble() < 0.15;
        boolean hasThumbnail = random.nextDouble() < 0.85;

        entry.put("kind", "entry");
        entry.put("id", UUID.randomUUID().toString());
        entry.put("type", entryType);
        entry.put("title", getRandomTitle(random, entryType));
        entry.put("authorName", author[0]);
        entry.put("publishedAt", getRandomDate(random));
        entry.put("locked", locked);

        if (author[1] != null) {
            entry.put("authorAvatarUrl", author[1]);
        }
        if (author[2] != null) {
            entry.put("profileBadge", author[2]);
        }

        if (hasThumbnail) {
            entry.put("thumbnailUrl", "https://picsum.photos/500/300?image=" + imageNum);
        }

        if ("video".equals(entryType) || "audio".equals(entryType)) {
            entry.put("durationSec", random.nextInt(7200) + 30);
        }

        return entry;
    }

    private Map<String, Object> generateRandomCollection(Random random) {
        Map<String, Object> collection = new LinkedHashMap<>();

        String[] author = pickAuthor(random);
        String collectionType = COLLECTION_TYPES[random.nextInt(COLLECTION_TYPES.length)];
        int imageNum = getValidImageNumber(random);
        boolean locked = random.nextDouble() < 0.20;
        boolean hasCover = random.nextDouble() < 0.90;

        collection.put("kind", "collection");
        collection.put("id", UUID.randomUUID().toString());
        collection.put("collectionType", collectionType);
        collection.put("title", getRandomCollectionTitle(random, collectionType));
        collection.put("authorName", author[0]);
        collection.put("publishedAt", getRandomDate(random));
        collection.put("locked", locked);

        if (author[1] != null) {
            collection.put("authorAvatarUrl", author[1]);
        }
        if (author[2] != null) {
            collection.put("profileBadge", author[2]);
        }

        if (hasCover) {
            collection.put("coverUrl", "https://picsum.photos/500/300?image=" + imageNum);
        }

        int itemsCount = random.nextInt(99) + 2;
        collection.put("itemsCount", itemsCount);

        if ("series".equals(collectionType) || "course".equals(collectionType)) {
            collection.put("totalDurationSec", random.nextInt(36000) + 1800);
        }

        return collection;
    }

    private Map<String, Object> buildPagedResponse(
            List<Map<String, Object>> content,
            int page,
            int size,
            int totalElements,
            int totalPages
    ) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", content);
        response.put("page", page);
        response.put("size", size);
        response.put("totalElements", totalElements);
        response.put("totalPages", totalPages);
        return response;
    }

    private int getValidImageNumber(Random random) {
        // Avoid known problematic image numbers from picsum.photos
        int[] problematic = {86, 97};
        int imageNum;
        do {
            imageNum = random.nextInt(100) + 1;
        } while (contains(problematic, imageNum));
        return imageNum;
    }

    private boolean contains(int[] arr, int value) {
        for (int i : arr) {
            if (i == value) return true;
        }
        return false;
    }

    private String getRandomDate(Random random) {
        long millisInYear = 1000L * 60 * 60 * 24 * 365;
        long randomMillisAgo = (long) (random.nextDouble() * millisInYear);
        Date date = new Date(System.currentTimeMillis() - randomMillisAgo);
        return String.format("%tFT%<tT.%<tL+00:00", date);
    }

    private String getRandomTitle(Random random, String entryType) {
        String[] videoTitles = {
            "One meets his destiny on the road he takes to avoid it.",
            "First steps: build your creator profile and publish.",
            "Meal prep: 4 lunches in 20 minutes.",
            "Understanding the fundamentals of design systems.",
            "A journey through minimalist architecture.",
            "The art of storytelling in digital media.",
            "Quick tips for better productivity.",
            "Behind the scenes: creative process unveiled."
        };

        String[] audioTitles = {
            "Podcast: Creative Process Unveiled",
            "Interview: Building Sustainable Businesses",
            "Deep Dive: The Future of AI in Design",
            "Weekly Roundup: Tech News and Insights",
            "Ambient Sounds: Focus and Productivity",
            "Lo-fi beats to study and relax.",
            "Meditation guide for busy creators."
        };

        String[] imageTitles = {
            "Morning light on concrete — minimal photo set.",
            "Urban exploration: hidden corners of the city.",
            "Nature's patterns: macro photography collection.",
            "Portrait series: faces of the neighborhood.",
            "Abstract compositions in everyday objects.",
            "Golden hour: landscape photography tips."
        };

        String[] entryTitles = {
            "The Art of Storytelling in Digital Media",
            "Building Your First Design System",
            "A Complete Guide to Modern Web Development",
            "Reflections on Creative Block and Recovery",
            "How to Start Your Creator Journey Today",
            "Understanding Typography Fundamentals",
            "The Future of Remote Work and Collaboration"
        };

        String[] titles;
        switch (entryType) {
            case "video":
                titles = videoTitles;
                break;
            case "audio":
                titles = audioTitles;
                break;
            case "image":
                titles = imageTitles;
                break;
            default:
                titles = entryTitles;
        }

        return titles[random.nextInt(titles.length)];
    }

    private String getRandomCollectionTitle(Random random, String collectionType) {
        String[][] titlesByType = {
            // series
            {"Learn Design Systems (Part 1–8)", "Web Development Fundamentals", "Creative Coding Journey"},
            // course
            {"Modern Web Development Bootcamp", "UI/UX Design Masterclass", "Photography Essentials"},
            // library
            {"Saved Resources & References", "Design Inspiration Collection", "Research Materials"},
            // list
            {"Lo-fi + Focus (Weekly Picks)", "Must-Watch Documentaries", "Top Creator Tutorials"},
            // album
            {"Summer Photography 2025", "Urban Explorations", "Portrait Collection"},
            // bundle
            {"Creator Starter Pack", "Design Tools Bundle", "Complete Learning Kit"},
            // catalog
            {"Templates & UI Kits", "Icon Collections", "Font Pairings Guide"},
            // volume
            {"Photo Essays — Vol. 3", "Design Trends 2025", "Creator Stories"},
            // archive
            {"2024 Highlights", "Best of Last Year", "Legacy Content"}
        };

        int typeIndex = getCollectionTypeIndex(collectionType);
        String[] titles = titlesByType[typeIndex];
        String prefix = capitalize(collectionType) + ": ";
        
        return prefix + titles[random.nextInt(titles.length)];
    }

    private int getCollectionTypeIndex(String type) {
        for (int i = 0; i < COLLECTION_TYPES.length; i++) {
            if (COLLECTION_TYPES[i].equals(type)) return i;
        }
        return 0;
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

}
