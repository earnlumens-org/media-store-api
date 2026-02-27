package org.earnlumens.mediastore.web.mock;

import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * Mock controller that simulates Stellar ecosystem members publishing content
 * on EarnLumens. Every author carries the u2 (gold/yellow) verified badge
 * and uses their project logo as avatar.
 *
 * Endpoint: /api/mock/entries/ecosystem
 */
@RestController
@RequestMapping("/api/mock/entries/ecosystem")
public class EcosystemMockController {

    private static final int MAX_PAGE_SIZE = 48;

    private static final String[] ENTRY_TYPES = {"video", "audio", "resource", "image"};
    private static final String[] COLLECTION_TYPES = {
        "series", "course", "library", "list", "album",
        "bundle", "catalog", "volume", "archive"
    };

    // ── Stellar ecosystem members ─────────────────────────────────────
    // GitHub .png → org avatars (verified 302). Google favicons as fallback (128×128).
    private static final String GH = "https://github.com/";
    private static final String GF = "https://www.google.com/s2/favicons?domain=";
    private static final String GH_SZ = ".png?size=200";
    private static final String GF_SZ = "&sz=128";

    private static final String[][] MEMBERS = {
        // { username, displayName, avatarUrl }
        {"stellarorg",       "Stellar Development Foundation", GH + "stellar" + GH_SZ},
        {"lobstr",           "LOBSTR Wallet",                  GH + "Lobstrco" + GH_SZ},
        {"circle",           "Circle",                         GH + "circlefin" + GH_SZ},
        {"moneygram",        "MoneyGram",                      GH + "moneygram" + GH_SZ},
        {"franklintempleton", "Franklin Templeton",            "https://pbs.twimg.com/profile_images/1817831714527334400/zpEkqYrh_400x400.jpg"},
        {"ultrastellar",     "Ultra Stellar",                  "https://pbs.twimg.com/profile_images/1357309147470041090/UeZllGx9_400x400.png"},
        {"aqua",             "Aquarius",                       GH + "AquaToken" + GH_SZ},
        {"stellarx",         "StellarX",                       "https://pbs.twimg.com/profile_images/1014666925329342465/RN8goCHy_400x400.jpg"},
        {"vesseo",           "Vesseo",                         "https://pbs.twimg.com/profile_images/1914727632547016705/GwEDbRFw_400x400.png"},
        {"beansapp",         "Beans App",                      GF + "beansapp.com" + GF_SZ},
        {"flutterwave",      "Flutterwave",                    GH + "Flutterwave" + GH_SZ},
        {"script3",          "Script3",                        GH + "script3" + GH_SZ},
        {"soroswap",         "Soroswap",                       GH + "soroswap" + GH_SZ},
        {"litemint",         "Litemint",                        GH + "Litemint" + GH_SZ},
        {"freighterwallet",  "Freighter Wallet",               GF + "freighter.app" + GF_SZ},
        {"stellarexpert",    "Stellar Expert",                  GH + "stellar-expert" + GH_SZ},
        {"arf",              "Arf Financial",                   "https://pbs.twimg.com/profile_images/1089854268729901056/YUBK21GF_400x400.jpg"},
        {"bitso",            "Bitso",                           "https://pbs.twimg.com/profile_images/1981835148455723008/Kfo4IqcN_400x400.jpg"},
        {"pendulum",         "Pendulum",                        GH + "pendulum-chain" + GH_SZ},
        {"wirex",            "Wirex",                           GH + "wirexapp" + GH_SZ},
        {"clickpesa",        "ClickPesa",                       GH + "clickpesa" + GH_SZ},
        {"ledger",           "Ledger",                          "https://pbs.twimg.com/profile_images/1522608646043181060/QQRjVYhi_400x400.jpg"},
        {"allbridge",        "Allbridge",                       "https://pbs.twimg.com/profile_images/1667191904545505281/bbm43TM7_400x400.jpg"},
        {"kado",             "Kado",                            GF + "kado.money" + GF_SZ},
        {"paysend",          "Paysend",                         GH + "paysend" + GH_SZ},
        {"airtm",            "Airtm",                           "https://pbs.twimg.com/profile_images/1911878834493808640/T5v1FSgm_400x400.jpg"},
        {"veloprotocol",     "Velo Protocol",                   GH + "velo-labs" + GH_SZ},
        {"saldo",            "Saldo",                           "https://pbs.twimg.com/profile_images/1256403254004465665/TgGn3zn6_400x400.jpg"},
        {"smartlands",       "Smartlands",                      GH + "smartlands" + GH_SZ},
        {"centus",           "Centus",                          GF + "centus.one" + GF_SZ},
    };

    // ── Titles per member (contextual to the project) ─────────────────
    private static final Map<String, String[][]> MEMBER_TITLES = new LinkedHashMap<>();

    static {
        // key = username → [video[], audio[], entry[], image[]]
        MEMBER_TITLES.put("stellarorg", new String[][] {
            {"Introduction to the Stellar Network", "Stellar Consensus Protocol Explained", "Building on Soroban: Getting Started", "Stellar Meridian 2025 Keynote"},
            {"Podcast: The Future of Stellar", "Fireside Chat with SDF Leadership", "Stellar Ecosystem Roundup"},
            {"What is Stellar? A Beginner's Guide", "Stellar vs Traditional Finance: A Deep Dive", "2025 Stellar Roadmap Update"},
            {"Stellar Network Architecture Diagram", "Soroban Smart Contracts Overview", "Global Stellar Meetup Highlights"}
        });
        MEMBER_TITLES.put("lobstr", new String[][] {
            {"How to Set Up Your LOBSTR Wallet", "Swap Assets on Stellar with LOBSTR", "LOBSTR Advanced Trading Features", "Securing Your Stellar Wallet"},
            {"Podcast: Wallet Security Best Practices", "Interview: CEO on LOBSTR's Growth"},
            {"Getting Started with LOBSTR in 5 Minutes", "How to Trade XLM/USDC on LOBSTR", "Understanding Stellar Trustlines"},
            {"LOBSTR Wallet Interface Tour", "New Features: Soroban Token Support"}
        });
        MEMBER_TITLES.put("circle", new String[][] {
            {"USDC on Stellar: Why It Matters", "Circle and Stellar: Powering Global Payments", "How to Use USDC for Cross-Border Transfers"},
            {"Podcast: Stablecoins and Financial Inclusion", "Digital Dollar Deep Dive"},
            {"Understanding USDC on Multiple Chains", "Circle's Vision for Programmable Money", "USDC Stellar Integration Guide"},
            {"USDC Ecosystem Overview", "Cross-Chain USDC Architecture"}
        });
        MEMBER_TITLES.put("moneygram", new String[][] {
            {"MoneyGram Access: Cash to Crypto Made Easy", "How to Send Money with Stellar and MoneyGram", "Digital Wallet Cash-In Tutorial"},
            {"Podcast: Reimagining Remittances", "MoneyGram + Stellar Partnership Update"},
            {"MoneyGram Access: Bridging Cash and DeFi", "How MoneyGram Uses Stellar for Settlements"},
            {"MoneyGram Access App Walkthrough", "Global Remittance Flow on Stellar"}
        });
        MEMBER_TITLES.put("franklintempleton", new String[][] {
            {"Tokenized Funds on Stellar Explained", "Franklin Templeton's Blockchain Strategy", "On-Chain Government Money Fund Overview"},
            {"Podcast: Institutional DeFi on Stellar", "The Future of Tokenized Assets"},
            {"Why Franklin Templeton Chose Stellar", "Understanding Tokenized Money Market Funds"},
            {"Benji Investments Platform Preview", "Stellar-Based Fund Architecture"}
        });
        MEMBER_TITLES.put("ultrastellar", new String[][] {
            {"Ultra Stellar Platform Tutorial", "How to Manage Stellar Assets", "StellarTerm DEX Advanced Guide"},
            {"Podcast: Building DEX Infrastructure on Stellar"},
            {"Ultra Stellar's Contribution to Stellar DEX", "StellarTerm Trading Guide"},
            {"Ultra Stellar Dashboard Overview", "DEX Orderbook Visualization"}
        });
        MEMBER_TITLES.put("aqua", new String[][] {
            {"Aquarius: Liquidity Rewards on Stellar", "How to Vote for Liquidity Pairs", "AQUA Token Staking Guide", "Understanding AMM Voting"},
            {"Podcast: Decentralized Liquidity on Stellar", "Aquarius Governance Explained"},
            {"AQUA Token: What You Need to Know", "How Aquarius Incentivizes Stellar Liquidity", "Aquarius Voting System Deep Dive"},
            {"Aquarius Protocol Dashboard", "AQUA Liquidity Pools Overview"}
        });
        MEMBER_TITLES.put("stellarx", new String[][] {
            {"Trading on StellarX: Complete Guide", "StellarX Zero-Fee Trading Demo", "How to Use StellarX Order Types"},
            {"Podcast: The Vision Behind StellarX"},
            {"StellarX: The Free Trading Interface for Stellar", "Advanced Order Types on Stellar DEX"},
            {"StellarX Trading Interface", "Market Depth Charts Explained"}
        });
        MEMBER_TITLES.put("vesseo", new String[][] {
            {"Save in Digital Dollars with Vesseo", "Vesseo App Setup Tutorial", "How to Protect Your Savings from Inflation"},
            {"Podcast: Dollar Savings in Latin America"},
            {"Vesseo: Your Digital Dollar Account", "How Vesseo Uses Stellar for Savings"},
            {"Vesseo App Interface Tour", "USDC Savings Growth Chart"}
        });
        MEMBER_TITLES.put("beansapp", new String[][] {
            {"Send Money Instantly with Beans App", "Beans App: Peer-to-Peer Payments Tutorial"},
            {"Podcast: Simple Payments on Stellar"},
            {"Beans App: Payments Made Simple", "How Beans Uses Stellar for Instant Transfers"},
            {"Beans App Payment Flow", "QR Code Payments Demo"}
        });
        MEMBER_TITLES.put("flutterwave", new String[][] {
            {"Flutterwave + Stellar: Africa's Payment Solution", "How to Accept Payments with Flutterwave"},
            {"Podcast: Fintech Innovation in Africa"},
            {"Flutterwave's Integration with Stellar", "Cross-Border Payments in Africa Explained"},
            {"Flutterwave Dashboard Overview", "African Payment Corridors Map"}
        });
        MEMBER_TITLES.put("script3", new String[][] {
            {"Blend Protocol: Lending on Stellar", "How to Borrow and Lend on Soroban", "Script3 DeFi Toolkit Overview"},
            {"Podcast: DeFi on Soroban with Script3", "Building Lending Protocols on Stellar"},
            {"Blend Protocol Deep Dive", "Understanding Soroban Smart Contracts for DeFi"},
            {"Script3 Protocol Architecture", "Blend Lending Dashboard"}
        });
        MEMBER_TITLES.put("soroswap", new String[][] {
            {"Soroswap: First AMM on Soroban", "How to Swap Tokens on Soroswap", "Providing Liquidity on Soroswap"},
            {"Podcast: Building an AMM from Scratch on Soroban"},
            {"Soroswap Protocol Explained", "AMM vs Order Book: Soroswap's Approach"},
            {"Soroswap Interface Tour", "Liquidity Pool Visualization"}
        });
        MEMBER_TITLES.put("litemint", new String[][] {
            {"Litemint: NFTs on Stellar", "How to Create and Sell NFTs on Litemint", "Litemint Games Overview"},
            {"Podcast: NFT Gaming on Stellar"},
            {"Litemint Marketplace Guide", "Understanding NFTs on the Stellar Network"},
            {"Litemint NFT Collection Gallery", "Game Assets on Stellar"}
        });
        MEMBER_TITLES.put("freighterwallet", new String[][] {
            {"Freighter Wallet Setup Guide", "How to Interact with Soroban dApps", "Freighter Security Features Explained"},
            {"Podcast: Browser Wallets and Soroban"},
            {"Freighter: The Official Stellar Browser Wallet", "Connecting to Soroban dApps with Freighter"},
            {"Freighter Extension Interface", "dApp Connection Flow"}
        });
        MEMBER_TITLES.put("stellarexpert", new String[][] {
            {"How to Read Stellar Transactions on StellarExpert", "Stellar Network Analytics Overview", "Tracking Assets on the Stellar Ledger"},
            {"Podcast: On-Chain Analytics for Stellar"},
            {"StellarExpert: Your Blockchain Explorer Guide", "Understanding Stellar Network Metrics"},
            {"Stellar Network Stats Dashboard", "Transaction Flow Visualization"}
        });
        MEMBER_TITLES.put("arf", new String[][] {
            {"Arf: Eliminating Pre-Funding for MTOs", "How Arf Uses USDC on Stellar"},
            {"Podcast: Settlement Innovation on Stellar"},
            {"Arf Financial: Revolutionizing Cross-Border Settlement", "USDC-Based Settlement Explained"},
            {"Arf Settlement Architecture", "Cross-Border Payment Flow"}
        });
        MEMBER_TITLES.put("bitso", new String[][] {
            {"Bitso + Stellar: Remittances Made Easy", "How to Buy XLM on Bitso", "Bitso Exchange Feature Tour"},
            {"Podcast: Crypto Exchange Growth in Latin America"},
            {"Bitso's Role in the Stellar Ecosystem", "US-Mexico Remittance Corridor on Stellar"},
            {"Bitso Platform Overview", "XLM Trading Volume Analytics"}
        });
        MEMBER_TITLES.put("pendulum", new String[][] {
            {"Pendulum: Bridging Stellar and Polkadot", "Forex Optimization with Pendulum"},
            {"Podcast: Cross-Chain DeFi Bridges"},
            {"Understanding the Pendulum Bridge", "Forex on Blockchain: Pendulum's Approach"},
            {"Pendulum Network Architecture", "Bridge Flow Diagram"}
        });
        MEMBER_TITLES.put("wirex", new String[][] {
            {"Wirex: Crypto Meets Traditional Banking", "How to Use Wirex Card with Stellar Assets"},
            {"Podcast: Bridging Crypto and Everyday Spending"},
            {"Wirex Platform Overview", "Using the Stellar 26 Token"},
            {"Wirex Card Features", "Multi-Currency Wallet Interface"}
        });
        MEMBER_TITLES.put("clickpesa", new String[][] {
            {"ClickPesa: Business Payments in East Africa", "How ClickPesa Uses Stellar for Settlement"},
            {"Podcast: Fintech Solutions for East Africa"},
            {"ClickPesa's Stellar Integration", "B2B Payments on the Stellar Network"},
            {"ClickPesa Payment Dashboard", "East Africa Payment Corridors"}
        });
        MEMBER_TITLES.put("ledger", new String[][] {
            {"Securing Your XLM with Ledger Nano X", "Ledger Live: Managing Stellar Assets", "Hardware Wallet Setup Guide"},
            {"Podcast: Cold Storage Best Practices", "Ledger and the Future of Self-Custody"},
            {"Why You Need a Hardware Wallet for Stellar", "Connecting Ledger to Stellar DEXs", "Ledger Security Explained"},
            {"Ledger Live Dashboard Tour", "Blind Signing on Stellar"}
        });
        MEMBER_TITLES.put("allbridge", new String[][] {
            {"Allbridge: Cross-Chain Swaps on Stellar", "How to Bridge Assets to Stellar with Allbridge", "Allbridge Core Explained"},
            {"Podcast: The Future of Cross-Chain Messaging", "Allbridge and Stellar Integration"},
            {"Understanding Allbridge Architecture", "Bridging Stablecoins to the Stellar Network", "Cross-Chain Liquidity Explained"},
            {"Allbridge Interface Walkthrough", "Supported Chains Overview"}
        });
        MEMBER_TITLES.put("kado", new String[][] {
            {"Kado: Buy Crypto to Your Stellar Wallet", "On-Ramp and Off-Ramp with Kado"},
            {"Podcast: Simplifying Crypto On-Ramps"},
            {"Kado: The Easiest Way to Buy Stellar Assets", "Fiat to XLM in Minutes with Kado"},
            {"Kado Widget Integration", "On-Ramp Flow Walkthrough"}
        });
        MEMBER_TITLES.put("paysend", new String[][] {
            {"Paysend: Global Money Transfers on Stellar", "How Paysend Reaches 170+ Countries"},
            {"Podcast: Scaling Global Remittances"},
            {"Paysend's Stellar-Powered Infrastructure", "Instant Settlement Across Borders"},
            {"Paysend Global Coverage Map", "Transfer Speed Comparison"}
        });
        MEMBER_TITLES.put("airtm", new String[][] {
            {"Airtm: Your Peer-to-Peer Dollar Account", "How to Save in Digital Dollars with Airtm"},
            {"Podcast: Dollarization in Latin America"},
            {"Airtm: Protecting Savings with Digital Dollars", "P2P Dollar Trading on Stellar"},
            {"Airtm Platform Overview", "Dollar Account Dashboard"}
        });
        MEMBER_TITLES.put("veloprotocol", new String[][] {
            {"Velo: Digital Credit on Stellar", "Velo Protocol for Southeast Asian Markets"},
            {"Podcast: Digital Credit Systems in Asia"},
            {"Understanding Velo's Credit Protocol", "Borderless Asset Transfers with Velo"},
            {"Velo Network Architecture", "Southeast Asia Payment Map"}
        });
        MEMBER_TITLES.put("realio", new String[][] {
            {"Realio: Tokenizing Real Estate on Stellar", "How to Invest in Tokenized Assets"},
            {"Podcast: Real World Assets on Blockchain"},
            {"Real Estate Tokenization with Realio", "RWA on Stellar: A New Paradigm"},
            {"Realio Investment Dashboard", "Tokenized Property Overview"}
        });
        MEMBER_TITLES.put("saldo", new String[][] {
            {"Saldo: US-Mexico Remittances on Stellar", "How to Send Money to Mexico with Saldo"},
            {"Podcast: Remittance Innovation in Mexico"},
            {"Saldo: Fast and Affordable Transfers", "How Saldo Uses Stellar for Settlement"},
            {"Saldo App Interface", "US-Mexico Corridor Stats"}
        });
        MEMBER_TITLES.put("decaf", new String[][] {
            {"Decaf: USDC for Everyday Payments", "How to Use Decaf Wallet on Stellar"},
            {"Podcast: Making Crypto Payments Normal"},
            {"Decaf Wallet: Simple Crypto Payments", "Multichain USDC Made Easy"},
            {"Decaf Wallet Tour", "Payment QR Flow"}
        });
        MEMBER_TITLES.put("smartlands", new String[][] {
            {"Smartlands: Real Estate Tokenization Platform", "How to Invest Through Smartlands"},
            {"Podcast: Property Investment on Blockchain"},
            {"Smartlands Platform Overview", "Tokenized Real Estate Investing"},
            {"Smartlands Dashboard Preview", "Property Token Performance"}
        });
        MEMBER_TITLES.put("centus", new String[][] {
            {"Centus: Stable Income on Stellar", "How Centus Stablecoin Works"},
            {"Podcast: Earning Stable Returns on Stellar"},
            {"Understanding the Centus Protocol", "Stable Returns Through Stellar DeFi"},
            {"Centus Earnings Dashboard", "Stablecoin Yield Comparison"}
        });
    }

    // Fallback titles when a member has no specific titles
    private static final String[][] FALLBACK_TITLES = {
        {"Getting Started with Our Platform", "Platform Features Walkthrough", "Advanced Tutorial for Stellar Users"},
        {"Podcast: Our Vision for Stellar", "Interview: Building on Stellar"},
        {"Our Journey in the Stellar Ecosystem", "How We Leverage the Stellar Network"},
        {"Platform Dashboard Overview", "Integration Architecture"}
    };

    /**
     * Paginated ecosystem feed: entries + collections from Stellar ecosystem members.
     * All authors carry the u2 (gold) verified badge.
     */
    @GetMapping("/feed")
    public Map<String, Object> getEcosystemFeed(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "24") int size
    ) {
        simulateLatency(250, 350);

        size = Math.min(size, MAX_PAGE_SIZE);
        int totalElements = 240;
        int totalPages = (int) Math.ceil((double) totalElements / size);

        List<Map<String, Object>> items = new ArrayList<>();
        // Deterministic per page so reload gives same results
        Random random = new Random(page * 1000L + size);

        for (int i = 1; i <= size; i++) {
            int index = page * size + i;
            if (index > totalElements) break;

            boolean isEntry = random.nextDouble() < 0.75;
            if (isEntry) {
                items.add(generateEcosystemEntry(random));
            } else {
                items.add(generateEcosystemCollection(random));
            }
        }

        return buildPagedResponse(items, page, size, totalElements, totalPages);
    }

    // ========== Private Helper Methods ==========

    private Map<String, Object> generateEcosystemEntry(Random random) {
        Map<String, Object> entry = new LinkedHashMap<>();

        String[] member = MEMBERS[random.nextInt(MEMBERS.length)];
        String username = member[0];
        String displayName = member[1];
        String avatarUrl = member[2];

        String entryType = ENTRY_TYPES[random.nextInt(ENTRY_TYPES.length)];
        int imageNum = getValidImageNumber(random);
        boolean locked = random.nextDouble() < 0.12;
        boolean hasThumbnail = random.nextDouble() < 0.90;

        entry.put("kind", "entry");
        entry.put("id", UUID.randomUUID().toString());
        entry.put("type", entryType);
        entry.put("title", getMemberTitle(username, entryType, random));
        entry.put("authorName", username);
        entry.put("publishedAt", getRandomDate(random));
        entry.put("locked", locked);
        entry.put("authorAvatarUrl", avatarUrl);
        entry.put("profileBadge", "u2"); // Always gold badge for ecosystem members

        if (hasThumbnail) {
            entry.put("thumbnailUrl", "https://picsum.photos/500/300?image=" + imageNum);
        }

        if ("video".equals(entryType) || "audio".equals(entryType)) {
            int durationSec = random.nextInt(7200) + 30;
            entry.put("durationSec", durationSec);
        }

        return entry;
    }

    private Map<String, Object> generateEcosystemCollection(Random random) {
        Map<String, Object> collection = new LinkedHashMap<>();

        String[] member = MEMBERS[random.nextInt(MEMBERS.length)];
        String username = member[0];
        String displayName = member[1];
        String avatarUrl = member[2];

        String collectionType = COLLECTION_TYPES[random.nextInt(COLLECTION_TYPES.length)];
        int imageNum = getValidImageNumber(random);
        boolean locked = random.nextDouble() < 0.15;
        boolean hasCover = random.nextDouble() < 0.90;

        collection.put("kind", "collection");
        collection.put("id", UUID.randomUUID().toString());
        collection.put("collectionType", collectionType);
        collection.put("title", getCollectionTitle(username, collectionType, random));
        collection.put("authorName", username);
        collection.put("publishedAt", getRandomDate(random));
        collection.put("locked", locked);
        collection.put("authorAvatarUrl", avatarUrl);
        collection.put("profileBadge", "u2"); // Always gold badge

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

    private String getMemberTitle(String username, String entryType, Random random) {
        String[][] titles = MEMBER_TITLES.getOrDefault(username, FALLBACK_TITLES);

        int typeIndex;
        switch (entryType) {
            case "video":  typeIndex = 0; break;
            case "audio":  typeIndex = 1; break;
            case "entry":  typeIndex = 2; break;
            case "image":  typeIndex = 3; break;
            default:       typeIndex = 2; break;
        }

        if (typeIndex >= titles.length) typeIndex = 0;
        String[] pool = titles[typeIndex];
        return pool[random.nextInt(pool.length)];
    }

    private String getCollectionTitle(String username, String collectionType, Random random) {
        String[] collectionTitles = {
            "Complete Guide Series",
            "Getting Started Course",
            "Resource Library",
            "Curated Links & Tools",
            "Media Album",
            "Starter Bundle",
            "Full Catalog",
            "Volume 1",
            "Archive: Early Content"
        };

        String[][] memberTitles = MEMBER_TITLES.getOrDefault(username, FALLBACK_TITLES);
        // Pick a base title from the member's entry titles and prefix with collection type
        String[] baseTitles = memberTitles[0];
        String base = baseTitles[random.nextInt(baseTitles.length)];
        String prefix = capitalize(collectionType) + ": ";

        return prefix + base;
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

    private void simulateLatency(int minMs, int rangeMs) {
        try {
            int delay = minMs + new Random().nextInt(rangeMs + 1);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
