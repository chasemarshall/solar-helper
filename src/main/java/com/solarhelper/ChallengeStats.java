package com.solarhelper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks challenge results (unscramble, math, type) — who won, times, win/loss stats.
 * Persists to ~/.config/solarhelper-stats.json.
 */
public class ChallengeStats {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path STATS_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("solarhelper-stats.json");

    // Result patterns: "[player] unscrambled [word] in [time]s!"
    // "[player] solved [equation] in [time]s!"
    // "[player] typed [phrase] in [time]s!"
    static final Pattern UNSCRAMBLE_RESULT = Pattern.compile(
        "(\\S+) unscrambled (\\S+) in ([\\d.]+)s!"
    );
    static final Pattern UNREVERSE_RESULT = Pattern.compile(
        "(\\S+) unreversed (\\S+) in ([\\d.]+)s!"
    );
    static final Pattern READ_RESULT = Pattern.compile(
        "(\\S+) read (\\S+) in ([\\d.]+)s!"
    );
    static final Pattern MATH_RESULT = Pattern.compile(
        "(\\S+) solved .+ in ([\\d.]+)s!"
    );
    static final Pattern TYPE_RESULT = Pattern.compile(
        "(\\S+) typed .+ in ([\\d.]+)s!"
    );

    private static ChallengeStats INSTANCE;

    // Pending challenge info (set when we attempt a challenge, resolved when result comes in)
    private transient String pendingType = null;       // "unscramble", "math", "type"
    private transient long pendingOurDelayMs = 0;      // delay we chose
    private transient long pendingPingMs = 0;          // our ping at the time
    private transient long pendingTimestamp = 0;       // when challenge started

    // Persisted stats
    public List<ChallengeResult> results = new ArrayList<>();

    public static class ChallengeResult {
        public long timestamp;
        public String type;          // "unscramble", "math", "type"
        public String winner;        // who won
        public double winnerTimeS;   // winner's time in seconds
        public double ourEstTimeS;   // our estimated server time in seconds (delay + ping)
        public long ourDelayMs;      // delay we chose
        public long ourPingMs;       // our ping
        public boolean weWon;        // did we win?
        public boolean shouldHaveWon; // was our estimated time faster?
        public String challenge;     // the word/equation/phrase

        public ChallengeResult() {}
    }

    public static ChallengeStats get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    /** Call when we submit a challenge answer, to track our delay. */
    public void setPending(String type, long delayMs, long pingMs, String challenge) {
        this.pendingType = type;
        this.pendingOurDelayMs = delayMs;
        this.pendingPingMs = pingMs;
        this.pendingTimestamp = System.currentTimeMillis();
        this.pendingChallenge = challenge;
    }

    private transient String pendingChallenge = null;

    /**
     * Try to parse a chat message as a challenge result.
     * Returns a result if matched, null otherwise.
     */
    public ChallengeResult parseResult(String text, String ourUsername) {
        // Try each pattern
        String type = null;
        String winner = null;
        double winnerTime = 0;
        String challenge = "";

        Matcher m = UNSCRAMBLE_RESULT.matcher(text);
        if (m.find()) {
            type = "unscramble";
            winner = m.group(1);
            challenge = m.group(2);
            winnerTime = Double.parseDouble(m.group(3));
        }

        if (type == null) {
            m = UNREVERSE_RESULT.matcher(text);
            if (m.find()) {
                type = "unreverse";
                winner = m.group(1);
                challenge = m.group(2);
                winnerTime = Double.parseDouble(m.group(3));
            }
        }

        if (type == null) {
            m = READ_RESULT.matcher(text);
            if (m.find()) {
                type = "read";
                winner = m.group(1);
                challenge = m.group(2);
                winnerTime = Double.parseDouble(m.group(3));
            }
        }

        if (type == null) {
            m = MATH_RESULT.matcher(text);
            if (m.find()) {
                type = "math";
                winner = m.group(1);
                winnerTime = Double.parseDouble(m.group(2));
            }
        }

        if (type == null) {
            m = TYPE_RESULT.matcher(text);
            if (m.find()) {
                type = "type";
                winner = m.group(1);
                winnerTime = Double.parseDouble(m.group(2));
            }
        }

        if (type == null) return null;

        ChallengeResult result = new ChallengeResult();
        result.timestamp = System.currentTimeMillis();
        result.type = type;
        result.winner = winner;
        result.winnerTimeS = winnerTime;
        result.weWon = winner.equalsIgnoreCase(ourUsername);
        result.challenge = challenge.isEmpty() ? (pendingChallenge != null ? pendingChallenge : "?") : challenge;

        // Match with pending data if we had an active challenge
        if (pendingType != null && (System.currentTimeMillis() - pendingTimestamp) < 30_000) {
            result.ourDelayMs = pendingOurDelayMs;
            result.ourPingMs = pendingPingMs;
            result.ourEstTimeS = (pendingOurDelayMs + pendingPingMs) / 1000.0;
            result.shouldHaveWon = result.ourEstTimeS < winnerTime && !result.weWon;
        } else {
            result.ourDelayMs = -1;
            result.ourPingMs = -1;
            result.ourEstTimeS = -1;
            result.shouldHaveWon = false;
        }

        // Clear pending
        pendingType = null;
        pendingChallenge = null;

        results.add(result);
        save();

        return result;
    }

    /** Summary stats for a given type (or all if type is null). */
    public Stats getStats(String type) {
        Stats s = new Stats();
        for (ChallengeResult r : results) {
            if (type != null && !type.equals(r.type)) continue;
            s.total++;
            if (r.weWon) s.wins++;
            else s.losses++;
            if (r.ourEstTimeS > 0) {
                s.totalOurTime += r.ourEstTimeS;
                s.ourTimeCount++;
            }
            s.totalWinnerTime += r.winnerTimeS;
            if (r.shouldHaveWon) s.shouldHaveWon++;
        }
        return s;
    }

    public static class Stats {
        public int total, wins, losses, shouldHaveWon, ourTimeCount;
        public double totalOurTime, totalWinnerTime;

        public double avgOurTime() { return ourTimeCount > 0 ? totalOurTime / ourTimeCount : 0; }
        public double avgWinnerTime() { return total > 0 ? totalWinnerTime / total : 0; }
        public double winRate() { return total > 0 ? (wins * 100.0) / total : 0; }
    }

    private static ChallengeStats load() {
        if (Files.exists(STATS_PATH)) {
            try {
                String json = Files.readString(STATS_PATH);
                ChallengeStats stats = GSON.fromJson(json, ChallengeStats.class);
                if (stats != null && stats.results != null) return stats;
            } catch (Exception e) {
                SolarHelperClient.LOGGER.error("Failed to load challenge stats", e);
            }
        }
        return new ChallengeStats();
    }

    public void save() {
        try {
            Files.createDirectories(STATS_PATH.getParent());
            Files.writeString(STATS_PATH, GSON.toJson(this));
        } catch (IOException e) {
            SolarHelperClient.LOGGER.error("Failed to save challenge stats", e);
        }
    }
}
