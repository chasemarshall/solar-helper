package com.solarhelper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class SolarHelperConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("solarhelper.json");

    private static SolarHelperConfig INSTANCE;

    // Feature toggles
    public boolean welcomeEnabled = true;
    public boolean sellallEnabled = true;
    public boolean mathSolverEnabled = true;
    public boolean unscrambleEnabled = true;
    public boolean autoTypeEnabled = true;

    // Human-like behavior
    public boolean freezeInputEnabled = true;
    public boolean typosEnabled = false;
    public boolean mathFuzzEnabled = true;

    // OpenRouter API key (optional, used as fallback for unscrambling)
    public String openRouterApiKey = "";

    // Delay settings (ms)
    public int welcomeMinDelayMs = 1000;
    public int welcomeMaxDelayMs = 2000;
    public int challengeMinDelayMs = 1500;
    public int challengeMaxDelayMs = 3000;
    public int sellallCooldownMs = 1500;

    public static SolarHelperConfig get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public static SolarHelperConfig load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH);
                INSTANCE = GSON.fromJson(json, SolarHelperConfig.class);
                if (INSTANCE == null) INSTANCE = new SolarHelperConfig();
                return INSTANCE;
            } catch (Exception e) {
                SolarHelperClient.LOGGER.error("Failed to load config, using defaults", e);
            }
        }
        INSTANCE = new SolarHelperConfig();
        INSTANCE.save();
        return INSTANCE;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(this));
        } catch (IOException e) {
            SolarHelperClient.LOGGER.error("Failed to save config", e);
        }
    }

    /** Returns a random delay between welcomeMinDelayMs and welcomeMaxDelayMs. */
    public long randomWelcomeDelay() {
        int range = Math.max(0, welcomeMaxDelayMs - welcomeMinDelayMs);
        return welcomeMinDelayMs + (range > 0 ? java.util.concurrent.ThreadLocalRandom.current().nextLong(range) : 0);
    }

    /** Returns a random delay between challengeMinDelayMs and challengeMaxDelayMs. */
    public long randomChallengeDelay() {
        int range = Math.max(0, challengeMaxDelayMs - challengeMinDelayMs);
        return challengeMinDelayMs + (range > 0 ? java.util.concurrent.ThreadLocalRandom.current().nextLong(range) : 0);
    }
}
