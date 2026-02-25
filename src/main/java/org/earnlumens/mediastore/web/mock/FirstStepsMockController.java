package org.earnlumens.mediastore.web.mock;

import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * Mock controller for the "First Steps" page.
 * Returns exactly 8 public tutorial videos authored by the official
 * "earnlumens" account with a gold (u2) verified badge.
 *
 * Endpoint: /api/mock/entries/firststeps/feed
 */
@RestController
@RequestMapping("/api/mock/entries/firststeps")
public class FirstStepsMockController {

    private static final String AUTHOR      = "earnlumens";
    private static final String AVATAR_URL  = "https://pbs.twimg.com/profile_images/1758468234158678016/uJvEidK0_400x400.jpg";
    private static final String BADGE       = "u2"; // gold

    private static final String[][] VIDEOS = {
        // { title, durationSec, picsum image id }
        {"What Is Digital Money and How Does It Work?",        "287",  "1"},   // 4:47
        {"What Is a Wallet and Why Do You Need One?",          "312",  "3"},   // 5:12
        {"How to Create a Wallet Step by Step",                "265",  "24"},  // 4:25
        {"How to Add Funds to Your Wallet for the First Time", "348",  "36"},  // 5:48
        {"How to Connect Your Wallet to EarnLumens",           "294",  "48"},  // 4:54
        {"How to Buy Content on EarnLumens",                   "410",  "60"},  // 6:50
        {"How to Sell Content and Get Paid on EarnLumens",     "455",  "75"},  // 7:35
        {"Common Wallet and Payment Mistakes to Avoid",        "378",  "83"},  // 6:18
    };

    @GetMapping("/feed")
    public Map<String, Object> getFirstStepsFeed(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "24") int size
    ) {
        simulateLatency(200, 150);

        List<Map<String, Object>> items = new ArrayList<>();

        // Only page 0 has content; there are exactly 8 items
        if (page == 0) {
            for (int i = 0; i < VIDEOS.length; i++) {
                items.add(buildVideo(i));
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", items);
        response.put("page", page);
        response.put("size", size);
        response.put("totalElements", VIDEOS.length);
        response.put("totalPages", 1);
        return response;
    }

    private Map<String, Object> buildVideo(int index) {
        Map<String, Object> entry = new LinkedHashMap<>();
        String[] v = VIDEOS[index];

        entry.put("kind", "entry");
        entry.put("id", UUID.nameUUIDFromBytes(("firststeps-" + index).getBytes()).toString());
        entry.put("type", "video");
        entry.put("title", v[0]);
        entry.put("authorName", AUTHOR);
        entry.put("publishedAt", "2026-01-15T10:00:00.000+00:00");
        entry.put("locked", false);
        entry.put("authorAvatarUrl", AVATAR_URL);
        entry.put("profileBadge", BADGE);
        entry.put("thumbnailUrl", "https://picsum.photos/500/300?image=" + v[2]);
        entry.put("durationSec", Integer.parseInt(v[1]));

        return entry;
    }

    private void simulateLatency(int minMs, int rangeMs) {
        try {
            Thread.sleep(minMs + new Random().nextInt(rangeMs + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
