package org.earnlumens.mediastore.web.mock;

import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * Mock controller for generating random entries and collections
 * for the media-store-ui frontend.
 * 
 * Note: showAuthor is NOT included in the response as it is a UI design decision,
 * not server data.
 */
@RestController
@RequestMapping("/api/mock/entries")
public class EntryMockController {

    private static final int MAX_PAGE_SIZE = 48;
    private static final String[] ENTRY_TYPES = {"video", "audio", "entry", "image"};
    private static final String[] COLLECTION_TYPES = {
        "series", "course", "library", "list", "album", 
        "bundle", "catalog", "volume", "archive"
    };
    private static final String[] PROFILE_BADGES = {"u1", "u2"};

    /**
     * Get a paginated feed of mixed entries and collections.
     */
    @GetMapping("/feed")
    public Map<String, Object> getFeed(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "24") int size
    ) {
        // Simulate latency between 250ms and 600ms
        simulateLatency(250, 350);

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
        simulateLatency(250, 350);

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
        simulateLatency(250, 350);

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
     */
    @GetMapping("/entry/{id}")
    public Map<String, Object> getEntryById(@PathVariable("id") String id) {
        simulateLatency(250, 350);
        
        Random random = new Random(id.hashCode()); // Deterministic based on ID
        Map<String, Object> entry = generateRandomEntry(random);
        entry.put("id", id);
        return entry;
    }

    /**
     * Get a single collection by ID (mock).
     */
    @GetMapping("/collection/{id}")
    public Map<String, Object> getCollectionById(@PathVariable("id") String id) {
        simulateLatency(250, 350);
        
        Random random = new Random(id.hashCode()); // Deterministic based on ID
        Map<String, Object> collection = generateRandomCollection(random);
        collection.put("id", id);
        return collection;
    }

    // ========== Private Helper Methods ==========

    private Map<String, Object> generateRandomEntry(Random random) {
        Map<String, Object> entry = new LinkedHashMap<>();
        
        String entryType = ENTRY_TYPES[random.nextInt(ENTRY_TYPES.length)];
        String username = getRandomUsername(random);
        int avatarNum = random.nextInt(99) + 1;
        String gender = random.nextBoolean() ? "men" : "women";
        int imageNum = getValidImageNumber(random);
        boolean locked = random.nextDouble() < 0.15; // 15% locked
        boolean hasAvatar = random.nextDouble() < 0.8; // 80% have avatar
        boolean hasBadge = random.nextDouble() < 0.4; // 40% have badge
        boolean hasThumbnail = random.nextDouble() < 0.85; // 85% have thumbnail

        entry.put("kind", "entry");
        entry.put("id", UUID.randomUUID().toString());
        entry.put("type", entryType);
        entry.put("title", getRandomTitle(random, entryType));
        entry.put("authorName", username);
        entry.put("publishedAt", getRandomDate(random));
        entry.put("locked", locked);

        if (hasAvatar) {
            entry.put("authorAvatarUrl", "https://randomuser.me/api/portraits/" + gender + "/" + avatarNum + ".jpg");
        }

        if (hasBadge) {
            entry.put("profileBadge", PROFILE_BADGES[random.nextInt(PROFILE_BADGES.length)]);
        }

        if (hasThumbnail) {
            entry.put("thumbnailUrl", "https://picsum.photos/500/300?image=" + imageNum);
        }

        // Duration for video and audio
        if ("video".equals(entryType) || "audio".equals(entryType)) {
            int durationSec = random.nextInt(7200) + 30; // 30 seconds to 2 hours
            entry.put("durationSec", durationSec);
        }

        return entry;
    }

    private Map<String, Object> generateRandomCollection(Random random) {
        Map<String, Object> collection = new LinkedHashMap<>();
        
        String collectionType = COLLECTION_TYPES[random.nextInt(COLLECTION_TYPES.length)];
        String username = getRandomUsername(random);
        int avatarNum = random.nextInt(99) + 1;
        String gender = random.nextBoolean() ? "men" : "women";
        int imageNum = getValidImageNumber(random);
        boolean locked = random.nextDouble() < 0.2; // 20% locked
        boolean hasAvatar = random.nextDouble() < 0.75; // 75% have avatar
        boolean hasBadge = random.nextDouble() < 0.35; // 35% have badge
        boolean hasCover = random.nextDouble() < 0.9; // 90% have cover

        collection.put("kind", "collection");
        collection.put("id", UUID.randomUUID().toString());
        collection.put("collectionType", collectionType);
        collection.put("title", getRandomCollectionTitle(random, collectionType));
        collection.put("authorName", username);
        collection.put("publishedAt", getRandomDate(random));
        collection.put("locked", locked);

        if (hasAvatar) {
            collection.put("authorAvatarUrl", "https://randomuser.me/api/portraits/" + gender + "/" + avatarNum + ".jpg");
        }

        if (hasBadge) {
            collection.put("profileBadge", PROFILE_BADGES[random.nextInt(PROFILE_BADGES.length)]);
        }

        if (hasCover) {
            collection.put("coverUrl", "https://picsum.photos/500/300?image=" + imageNum);
        }

        // Items count (2-100)
        int itemsCount = random.nextInt(99) + 2;
        collection.put("itemsCount", itemsCount);

        // Optional total duration for series/course types
        if ("series".equals(collectionType) || "course".equals(collectionType)) {
            int totalDurationSec = random.nextInt(36000) + 1800; // 30 min to 10 hours
            collection.put("totalDurationSec", totalDurationSec);
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

    private void simulateLatency(int minMs, int rangeMs) {
        try {
            int delay = minMs + new Random().nextInt(rangeMs + 1);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    private String getRandomUsername(Random random) {
        String[] usernames = {
            "cloudvibe", "pixeldust", "starbyte", "neonwave", "frostbit", 
            "lunar_echo", "blazepath", "cosmodrift", "sky_bolt", "novaspark",
            "techhaze", "glimmer", "voidwisp", "rocketglow", "duskreader", 
            "quantum", "shadowbit", "orbitz", "ember_flow", "zipstream",
            "galaxydrop", "holo", "surge", "vapor_trail", "comet_x", 
            "dashcore", "twist", "radiant", "flux_zone", "sparkvoyage",
            "nebulax", "bytestorm", "chillpixel", "driftspace", "zest", 
            "cinder", "wave_crash", "lightning", "codeflicker", "solar_loop",
            "phantom", "glitchy", "stormpulse", "riser", "echovibe", 
            "blip", "fusion", "skydive", "boltflash", "hazedream",
            "sarah", "alicia", "nadia", "mateo", "nicogon", "jordan", "interstellar"
        };
        return usernames[random.nextInt(usernames.length)];
    }
}
