package org.earnlumens.mediastore.web.mock;

import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * Mock controller that simulates the Community feed — regular end-users
 * (creators, photographers, musicians, etc.) publishing content on EarnLumens.
 *
 * Every author carries the u1 (blue) verified badge. No ecosystem members
 * (exchanges, wallets, protocols) appear here.
 *
 * Endpoint: /api/mock/entries/community
 */
@RestController
@RequestMapping("/api/mock/entries/community")
public class CommunityMockController {

    private static final int MAX_PAGE_SIZE = 48;

    private static final String[] ENTRY_TYPES = {"video", "audio", "resource", "image"};
    private static final String[] COLLECTION_TYPES = {
        "series", "course", "library", "list", "album",
        "bundle", "catalog", "volume", "archive"
    };

    // ── Community members (regular end-users / creators) ──────────────
    // Every user gets a randomuser.me portrait avatar and a blue badge (u1).
    private static final String[][] COMMUNITY_USERS = {
        // { username, gender (men/women), portraitIndex }
        {"cloudvibe",    "men",   "12"},
        {"pixeldust",    "women", "44"},
        {"starbyte",     "men",   "67"},
        {"neonwave",     "women", "22"},
        {"frostbit",     "men",   "33"},
        {"lunar_echo",   "women", "58"},
        {"blazepath",    "men",   "41"},
        {"cosmodrift",   "women", "15"},
        {"sky_bolt",     "men",   "78"},
        {"novaspark",    "women", "31"},
        {"techhaze",     "men",   "55"},
        {"glimmer",      "women", "7"},
        {"voidwisp",     "men",   "92"},
        {"rocketglow",   "women", "63"},
        {"duskreader",   "men",   "19"},
        {"quantum",      "women", "48"},
        {"shadowbit",    "men",   "26"},
        {"orbitz",       "women", "71"},
        {"ember_flow",   "men",   "84"},
        {"zipstream",    "women", "3"},
        {"galaxydrop",   "men",   "37"},
        {"holo",         "women", "52"},
        {"surge",        "men",   "61"},
        {"vapor_trail",  "women", "29"},
        {"comet_x",      "men",   "14"},
        {"dashcore",     "women", "88"},
        {"twist",        "men",   "45"},
        {"radiant",      "women", "36"},
        {"flux_zone",    "men",   "73"},
        {"sparkvoyage",  "women", "9"},
        {"nebulax",      "men",   "51"},
        {"bytestorm",    "women", "66"},
        {"chillpixel",   "men",   "82"},
        {"driftspace",   "women", "17"},
        {"zest",         "men",   "23"},
        {"cinder",       "women", "40"},
        {"wave_crash",   "men",   "59"},
        {"codeflicker",  "women", "75"},
        {"solar_loop",   "men",   "8"},
        {"phantom",      "women", "54"},
        {"glitchy",      "men",   "96"},
        {"stormpulse",   "women", "21"},
        {"riser",        "men",   "47"},
        {"echovibe",     "women", "34"},
        {"blip",         "men",   "69"},
        {"fusion",       "women", "11"},
        {"skydive",      "men",   "86"},
        {"boltflash",    "women", "43"},
        {"hazedream",    "men",   "57"},
        {"sarah",        "women", "28"},
        {"alicia",       "women", "62"},
        {"nadia",        "women", "5"},
        {"mateo",        "men",   "30"},
        {"nicogon",      "men",   "74"},
        {"jordan",       "men",   "18"},
    };

    // ── Content titles for community creators ─────────────────────────

    private static final String[] VIDEO_TITLES = {
        "One meets his destiny on the road he takes to avoid it.",
        "First steps: build your creator profile and publish.",
        "Meal prep: 4 lunches in 20 minutes.",
        "Understanding the fundamentals of design systems.",
        "A journey through minimalist architecture.",
        "The art of storytelling in digital media.",
        "Quick tips for better productivity.",
        "Behind the scenes: creative process unveiled.",
        "How I edit my videos — full workflow breakdown.",
        "Street photography tips for beginners.",
        "Travel vlog: hidden gems in Southeast Asia.",
        "My morning routine as a full-time creator.",
        "Building a home studio on a budget.",
        "Top 5 mistakes new creators make.",
        "How to grow your audience organically.",
        "Cinematic B-Roll techniques anyone can learn.",
    };

    private static final String[] AUDIO_TITLES = {
        "Podcast: Creative Process Unveiled",
        "Interview: Building Sustainable Businesses",
        "Deep Dive: The Future of AI in Design",
        "Weekly Roundup: Tech News and Insights",
        "Ambient Sounds: Focus and Productivity",
        "Lo-fi beats to study and relax.",
        "Meditation guide for busy creators.",
        "Podcast: Lessons from a freelance journey.",
        "Chill electronica — late night session.",
        "Field recordings: city at dawn.",
        "Podcast: Monetizing your creative work.",
        "Acoustic covers — 30-minute set.",
    };

    private static final String[] IMAGE_TITLES = {
        "Morning light on concrete — minimal photo set.",
        "Urban exploration: hidden corners of the city.",
        "Nature's patterns: macro photography collection.",
        "Portrait series: faces of the neighborhood.",
        "Abstract compositions in everyday objects.",
        "Golden hour: landscape photography tips.",
        "Analog vs digital: a side-by-side comparison.",
        "Double exposure experiments.",
        "Street art around the world.",
        "Night sky: long-exposure astrophotography.",
    };

    private static final String[] ENTRY_TITLES = {
        "The Art of Storytelling in Digital Media",
        "Building Your First Design System",
        "A Complete Guide to Modern Web Development",
        "Reflections on Creative Block and Recovery",
        "How to Start Your Creator Journey Today",
        "Understanding Typography Fundamentals",
        "The Future of Remote Work and Collaboration",
        "Pricing your work: a no-nonsense guide.",
        "10 free tools every creator should know.",
        "Why consistency beats perfection.",
        "My favorite resources for learning design.",
        "How I organize my creative projects.",
    };

    // ── Collection titles per type ────────────────────────────────────
    private static final String[][] COLLECTION_TITLES_BY_TYPE = {
        // series
        {"Learn Design Systems (Part 1–8)", "Web Development Fundamentals", "Creative Coding Journey", "Photography Masterclass"},
        // course
        {"Modern Web Development Bootcamp", "UI/UX Design Masterclass", "Photography Essentials", "Music Production 101"},
        // library
        {"Saved Resources & References", "Design Inspiration Collection", "Research Materials", "Bookmarks for Creators"},
        // list
        {"Lo-fi + Focus (Weekly Picks)", "Must-Watch Documentaries", "Top Creator Tutorials", "Recommended Reads"},
        // album
        {"Summer Photography 2025", "Urban Explorations", "Portrait Collection", "Travel Memories"},
        // bundle
        {"Creator Starter Pack", "Design Tools Bundle", "Complete Learning Kit", "First Month Content"},
        // catalog
        {"Templates & UI Kits", "Icon Collections", "Font Pairings Guide", "Preset Pack"},
        // volume
        {"Photo Essays — Vol. 3", "Design Trends 2025", "Creator Stories", "Community Highlights"},
        // archive
        {"2024 Highlights", "Best of Last Year", "Legacy Content", "Throwback Collection"},
    };

    /**
     * Paginated community feed: entries + collections from regular end-users.
     * All authors carry the u1 (blue) verified badge.
     */
    @GetMapping("/feed")
    public Map<String, Object> getCommunityFeed(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "24") int size
    ) {
        size = Math.min(size, MAX_PAGE_SIZE);
        int totalElements = 240;
        int totalPages = (int) Math.ceil((double) totalElements / size);

        List<Map<String, Object>> items = new ArrayList<>();
        // Deterministic per page so reload gives same results
        Random random = new Random(page * 2000L + size);

        for (int i = 1; i <= size; i++) {
            int index = page * size + i;
            if (index > totalElements) break;

            boolean isEntry = random.nextDouble() < 0.70;
            if (isEntry) {
                items.add(generateCommunityEntry(random));
            } else {
                items.add(generateCommunityCollection(random));
            }
        }

        return buildPagedResponse(items, page, size, totalElements, totalPages);
    }

    // ========== Private Helper Methods ==========

    private Map<String, Object> generateCommunityEntry(Random random) {
        Map<String, Object> entry = new LinkedHashMap<>();

        String[] user = COMMUNITY_USERS[random.nextInt(COMMUNITY_USERS.length)];
        String username = user[0];
        String gender = user[1];
        String portraitIndex = user[2];

        String entryType = ENTRY_TYPES[random.nextInt(ENTRY_TYPES.length)];
        int imageNum = getValidImageNumber(random);
        boolean locked = random.nextDouble() < 0.12;
        boolean hasThumbnail = random.nextDouble() < 0.85;

        entry.put("kind", "entry");
        entry.put("id", UUID.randomUUID().toString());
        entry.put("type", entryType);
        entry.put("title", getTitle(entryType, random));
        entry.put("authorName", username);
        entry.put("publishedAt", getRandomDate(random));
        entry.put("locked", locked);
        entry.put("authorAvatarUrl",
            "https://randomuser.me/api/portraits/" + gender + "/" + portraitIndex + ".jpg");
        entry.put("profileBadge", "u1"); // Always blue badge for community users

        if (hasThumbnail) {
            entry.put("thumbnailUrl", "https://picsum.photos/500/300?image=" + imageNum);
        }

        if ("video".equals(entryType) || "audio".equals(entryType)) {
            int durationSec = random.nextInt(7200) + 30;
            entry.put("durationSec", durationSec);
        }

        return entry;
    }

    private Map<String, Object> generateCommunityCollection(Random random) {
        Map<String, Object> collection = new LinkedHashMap<>();

        String[] user = COMMUNITY_USERS[random.nextInt(COMMUNITY_USERS.length)];
        String username = user[0];
        String gender = user[1];
        String portraitIndex = user[2];

        String collectionType = COLLECTION_TYPES[random.nextInt(COLLECTION_TYPES.length)];
        int imageNum = getValidImageNumber(random);
        boolean locked = random.nextDouble() < 0.15;
        boolean hasCover = random.nextDouble() < 0.90;

        collection.put("kind", "collection");
        collection.put("id", UUID.randomUUID().toString());
        collection.put("collectionType", collectionType);
        collection.put("title", getCollectionTitle(collectionType, random));
        collection.put("authorName", username);
        collection.put("publishedAt", getRandomDate(random));
        collection.put("locked", locked);
        collection.put("authorAvatarUrl",
            "https://randomuser.me/api/portraits/" + gender + "/" + portraitIndex + ".jpg");
        collection.put("profileBadge", "u1"); // Always blue badge

        if (hasCover) {
            collection.put("coverUrl", "https://picsum.photos/500/300?image=" + imageNum);
        }

        int itemsCount = random.nextInt(99) + 2;
        collection.put("itemsCount", itemsCount);

        if ("series".equals(collectionType) || "course".equals(collectionType)) {
            int totalDurationSec = random.nextInt(36000) + 1800;
            collection.put("totalDurationSec", totalDurationSec);
        }

        return collection;
    }

    private String getTitle(String entryType, Random random) {
        switch (entryType) {
            case "video": return VIDEO_TITLES[random.nextInt(VIDEO_TITLES.length)];
            case "audio": return AUDIO_TITLES[random.nextInt(AUDIO_TITLES.length)];
            case "image": return IMAGE_TITLES[random.nextInt(IMAGE_TITLES.length)];
            default:      return ENTRY_TITLES[random.nextInt(ENTRY_TITLES.length)];
        }
    }

    private String getCollectionTitle(String collectionType, Random random) {
        int typeIndex = getCollectionTypeIndex(collectionType);
        String[] titles = COLLECTION_TITLES_BY_TYPE[typeIndex];
        return capitalize(collectionType) + ": " + titles[random.nextInt(titles.length)];
    }

    private int getCollectionTypeIndex(String type) {
        for (int i = 0; i < COLLECTION_TYPES.length; i++) {
            if (COLLECTION_TYPES[i].equals(type)) return i;
        }
        return 0;
    }

    private Map<String, Object> buildPagedResponse(
            List<Map<String, Object>> content, int page, int size,
            int totalElements, int totalPages) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", content);
        response.put("page", page);
        response.put("size", size);
        response.put("totalElements", totalElements);
        response.put("totalPages", totalPages);
        return response;
    }

    private int getValidImageNumber(Random random) {
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

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}
