package com.solarhelper;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SolarHelperClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("solarhelper");

    private static final String MOD_VERSION = "1.5.0";
    // Change this to your GitHub repo when you create it
    private static final String GITHUB_REPO = "chasemarshall/solar-helper";

    // Matches: [Welcome] Welcome <username> to Solar Skies! (#<number>)
    private static final Pattern WELCOME_PATTERN = Pattern.compile(
        "\\[Welcome]\\s+Welcome\\s+(\\S+)\\s+to Solar Skies!\\s+\\(#\\d+\\)"
    );

    // Matches: "The first to solve 180 + 468 wins!" (supports +, -, *, x, /)
    private static final Pattern MATH_PATTERN = Pattern.compile(
        "The first to solve\\s+(\\d+)\\s*([+\\-*/x])\\s*(\\d+)\\s*wins!"
    );

    // Matches: "The first to unscramble sseirt wins!"
    private static final Pattern UNSCRAMBLE_PATTERN = Pattern.compile(
        "The first to unscramble\\s+(\\S+)\\s+wins!"
    );

    // Matches: "The first to type something wins!"
    private static final Pattern TYPE_PATTERN = Pattern.compile(
        "The first to type\\s+(.+?)\\s+wins!"
    );

    private static volatile boolean inputFrozen = false;
    private static volatile boolean autoFarmActive = false;
    private static volatile boolean autoFarmWasActive = false; // tracks previous state for key release
    private static volatile boolean autoFarmPaused = false;    // brief pause after rotation
    private static long autoFarmPauseEndNs = 0;
    private static final long AUTO_FARM_PAUSE_NS = 150_000_000L; // 150ms pause after each rotation
    private long autoFarmTickCounter = 0;
    // Smooth rotation state (frame-based, not tick-based)
    private static float rotationTarget = 0f;     // total degrees for current rotation
    private static float rotationApplied = 0f;    // degrees applied so far
    private static long rotationStartTimeNs = 0;  // when the rotation started
    private static final long ROTATION_DURATION_NS = 750_000_000L; // 0.75 seconds in nanoseconds

    public static boolean isInputFrozen() {
        return inputFrozen;
    }

    public static boolean isAutoFarmActive() {
        if (autoFarmPaused && System.nanoTime() >= autoFarmPauseEndNs) {
            autoFarmPaused = false;
        }
        // Also pause auto-farm when input is frozen (mod is sending a chat message)
        return autoFarmActive && !autoFarmPaused && !inputFrozen;
    }

    /** Called every frame by GameRendererMixin to apply smooth rotation. */
    public static void tickRotation() {
        if (rotationTarget == 0f) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        long elapsed = System.nanoTime() - rotationStartTimeNs;
        float progress = Math.min(1.0f, (float) elapsed / ROTATION_DURATION_NS);
        // Sine ease-in-out for smooth acceleration and deceleration
        float eased = (float) (-(Math.cos(Math.PI * progress) - 1.0) / 2.0);
        float targetApplied = rotationTarget * eased;
        float step = targetApplied - rotationApplied;
        client.player.setYaw(client.player.getYaw() + step);
        rotationApplied = targetApplied;

        // Done
        if (progress >= 1.0f) {
            // Snap any remaining floating point error
            float correction = rotationTarget - rotationApplied;
            if (Math.abs(correction) > 0.01f) {
                client.player.setYaw(client.player.getYaw() + correction);
            }
            rotationTarget = 0f;
            rotationApplied = 0f;
            // Brief pause so the player doesn't mine the same block it just turned away from
            autoFarmPaused = true;
            autoFarmPauseEndNs = System.nanoTime() + AUTO_FARM_PAUSE_NS;
        }
    }

    /** Stops auto-farm and releases all keys. */
    public static void stopAutoFarm() {
        if (autoFarmActive) {
            autoFarmActive = false;
            autoFarmWasActive = true;
            rotationTarget = 0f;
            rotationApplied = 0f;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.options != null) {
                client.options.attackKey.setPressed(false);
            }
        }
    }

    /** Called by mixin each tick when auto-farm is NOT active, to release stuck keys once. */
    public static void releaseAutoFarmKeys() {
        if (autoFarmWasActive) {
            autoFarmWasActive = false;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.options != null) {
                client.options.attackKey.setPressed(false);
            }
        }
    }

    private long lastSellallTime = 0;
    private long lastWelcomeTime = 0;

    private KeyBinding autoFarmKeybind;

    // Single shared thread for all delayed actions
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "solarhelper-scheduler");
        t.setDaemon(true);
        return t;
    });

    // Dictionary: sorted letters -> list of possible words
    private volatile Map<String, List<String>> anagramMap = null;
    private volatile boolean dictionaryLoaded = false;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Solar Helper v{} loaded!", MOD_VERSION);

        // Register head outline renderer
        HeadOutlineRenderer.register();

        // Register auto-farm keybind (R key, rebindable in Options > Controls > Misc)
        autoFarmKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.solarhelper.autofarm",
            InputUtil.Type.KEYSYM,
            InputUtil.GLFW_KEY_R,
            KeyBinding.Category.MISC
        ));

        // Load dictionary + check for updates in background
        scheduler.execute(this::loadDictionary);
        scheduler.execute(this::checkForUpdates);

        // Listen for game messages (server-sent messages like join notifications)
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            handleMessage(message.getString());
        });

        // Also listen for chat messages in case it comes through as player chat
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            handleMessage(message.getString());
        });

        // Tick handler for auto-farm toggle + rotation + head click dismiss
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            SolarHelperConfig config = SolarHelperConfig.get();

            // Check if player clicked a highlighted head to dismiss it
            HeadOutlineRenderer.checkClickDismiss();

            // Toggle auto-farm on keybind press
            if (autoFarmKeybind.wasPressed()) {
                if (!config.autoFarmEnabled) {
                    sendLocalNotification(Text.empty()
                        .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                        .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("Auto Farm is disabled in settings.").formatted(Formatting.RED))
                    );
                    return;
                }
                boolean wasOn = autoFarmActive;
                autoFarmActive = !autoFarmActive;
                // If we just turned it off, mark wasActive so the mixin releases attack key
                autoFarmWasActive = wasOn;
                autoFarmTickCounter = 0;
                rotationTarget = 0f;
                rotationApplied = 0f;

                if (autoFarmActive) {
                    sendLocalNotification(Text.empty()
                        .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                        .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("Auto Farm ").formatted(Formatting.WHITE))
                        .append(Text.literal("ON").formatted(Formatting.GREEN, Formatting.BOLD))
                    );
                } else {
                    // Immediately release attack key to prevent stuck left click
                    if (client.options != null) {
                        client.options.attackKey.setPressed(false);
                    }
                    sendLocalNotification(Text.empty()
                        .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                        .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("Auto Farm ").formatted(Formatting.WHITE))
                        .append(Text.literal("OFF").formatted(Formatting.RED, Formatting.BOLD))
                    );
                }
            }

            // Rotation timer (triggers rotation; actual smooth movement is in tickRotation per-frame)
            if (autoFarmActive && client.player != null) {
                autoFarmTickCounter++;
                long intervalTicks = config.autoFarmRotationIntervalMs / 50;
                if (intervalTicks < 1) intervalTicks = 1;

                // Only start a new rotation if the previous one is done
                if (autoFarmTickCounter >= intervalTicks && rotationTarget == 0f) {
                    autoFarmTickCounter = 0;
                    rotationTarget = config.autoFarmRotateRight ? 180f : -180f;
                    rotationApplied = 0f;
                    rotationStartTimeNs = System.nanoTime();
                }
            }
        });
    }

    private void checkForUpdates() {
        try {
            HttpClient http = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest"))
                .header("Accept", "application/vnd.github.v3+json")
                .timeout(java.time.Duration.ofSeconds(5))
                .GET()
                .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String body = response.body();
                // Simple JSON parsing for "tag_name":"v1.2.0"
                Pattern tagPattern = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"");
                Matcher tagMatcher = tagPattern.matcher(body);
                if (tagMatcher.find()) {
                    String latestVersion = tagMatcher.group(1);
                    if (isNewerVersion(latestVersion, MOD_VERSION)) {
                        // Notify player after they join a world (delay to make sure player exists)
                        scheduler.schedule(() -> {
                            MinecraftClient client = MinecraftClient.getInstance();
                            client.execute(() -> {
                                if (client.player != null) {
                                    sendLocalNotification(Text.empty()
                                        .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                                        .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                                        .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                                        .append(Text.literal("Solar Helper update available! ").formatted(Formatting.WHITE))
                                        .append(Text.literal("v" + MOD_VERSION).formatted(Formatting.RED))
                                        .append(Text.literal(" -> ").formatted(Formatting.GRAY))
                                        .append(Text.literal("v" + latestVersion).formatted(Formatting.GREEN, Formatting.BOLD))
                                    );
                                }
                            });
                        }, 10, TimeUnit.SECONDS);
                        LOGGER.info("Update available: v{} -> v{}", MOD_VERSION, latestVersion);
                    } else {
                        LOGGER.info("Solar Helper is up to date (v{})", MOD_VERSION);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Could not check for updates: {}", e.getMessage());
        }
    }

    private void loadDictionary() {
        try (InputStream is = getClass().getResourceAsStream("/solarhelper/words.txt")) {
            if (is == null) {
                LOGGER.error("Could not find words.txt dictionary!");
                return;
            }
            Map<String, List<String>> map = new HashMap<>();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.trim().toLowerCase();
                if (word.isEmpty()) continue;
                String key = sortLetters(word);
                map.computeIfAbsent(key, k -> new ArrayList<>()).add(word);
            }
            LOGGER.info("Loaded dictionary with {} unique letter combos", map.size());
            anagramMap = map;
            dictionaryLoaded = true;
        } catch (Exception e) {
            LOGGER.error("Failed to load dictionary", e);
        }
    }

    private String sortLetters(String word) {
        char[] chars = word.toCharArray();
        Arrays.sort(chars);
        return new String(chars);
    }

    private String unscramble(String scrambled) {
        if (!dictionaryLoaded) return null;
        String key = sortLetters(scrambled.toLowerCase());
        List<String> matches = anagramMap.get(key);
        if (matches == null || matches.isEmpty()) return null;
        for (String word : matches) {
            if (word.length() == scrambled.length()) return word;
        }
        return matches.get(0);
    }

    /**
     * Sends a client-only notification with blank lines before and after.
     * This NEVER gets sent to the server — it's purely local.
     */
    private void sendLocalNotification(Text message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.empty(), false);
            client.player.sendMessage(message, false);
            client.player.sendMessage(Text.empty(), false);
        }
    }

    private void delayedAction(long delayMs, Runnable action) {
        MinecraftClient client = MinecraftClient.getInstance();
        SolarHelperConfig config = SolarHelperConfig.get();

        if (config.freezeInputEnabled && delayMs > 0) {
            // Freeze a bit before the message is sent (simulate stopping to type)
            long freezeLeadMs = Math.min(delayMs, 800 + ThreadLocalRandom.current().nextLong(400));
            long freezeAt = delayMs - freezeLeadMs;

            scheduler.schedule(() -> client.execute(() -> inputFrozen = true), freezeAt, TimeUnit.MILLISECONDS);
            scheduler.schedule(() -> client.execute(() -> {
                action.run();
                // Unfreeze shortly after sending
                scheduler.schedule(() -> client.execute(() -> inputFrozen = false),
                    200 + ThreadLocalRandom.current().nextLong(300), TimeUnit.MILLISECONDS);
            }), delayMs, TimeUnit.MILLISECONDS);
        } else {
            scheduler.schedule(() -> client.execute(action), delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void handleMessage(String text) {
        SolarHelperConfig config = SolarHelperConfig.get();
        long now = System.currentTimeMillis();

        // If someone mentions our username in chat, stop auto-farm for safety
        if (autoFarmActive) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) {
                String username = mc.player.getNameForScoreboard();
                if (text.contains(username)) {
                    stopAutoFarm();
                    sendLocalNotification(Text.empty()
                        .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                        .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("Auto Farm stopped — your name was mentioned in chat.").formatted(Formatting.RED))
                    );
                }
            }
        }

        // Quick pre-checks before expensive regex
        if (text.contains("[Welcome]") && config.welcomeEnabled) {
            Matcher matcher = WELCOME_PATTERN.matcher(text);
            if (matcher.find()) {
                if (now - lastWelcomeTime < 2000) return;
                lastWelcomeTime = now;

                String username = matcher.group(1);
                long delayMs = config.randomWelcomeDelay();
                delayedAction(delayMs, () -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client.player != null) {
                        client.player.networkHandler.sendChatMessage("welcome");
                        sendLocalNotification(Text.empty()
                            .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                            .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                            .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                            .append(Text.literal("Automatically welcomed ").formatted(Formatting.WHITE))
                            .append(Text.literal(username).formatted(Formatting.GREEN, Formatting.ITALIC))
                            .append(Text.literal("!").formatted(Formatting.WHITE))
                        );
                    }
                });
                return;
            }
        }

        if (text.contains("Your inventory is full") && config.sellallEnabled) {
            if (text.contains("/sellall")) {
                if (now - lastSellallTime < config.sellallCooldownMs) return;
                lastSellallTime = now;

                long sellDelay = 800 + ThreadLocalRandom.current().nextLong(400);
                delayedAction(sellDelay, () -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client.player != null) {
                        client.player.networkHandler.sendChatCommand("sellall");
                        sendLocalNotification(Text.empty()
                            .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                            .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                            .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                            .append(Text.literal("Automatically ran ").formatted(Formatting.WHITE))
                            .append(Text.literal("/sellall").formatted(Formatting.GREEN, Formatting.ITALIC))
                            .append(Text.literal("!").formatted(Formatting.WHITE))
                        );
                    }
                });
                return;
            }
        }

        if (text.contains("The first to")) {
            // Math: "The first to solve 180 + 468 wins!"
            if (text.contains("solve") && config.mathSolverEnabled) {
                Matcher mathMatcher = MATH_PATTERN.matcher(text);
                if (mathMatcher.find()) {
                    long a = Long.parseLong(mathMatcher.group(1));
                    String op = mathMatcher.group(2);
                    long b = Long.parseLong(mathMatcher.group(3));

                    Long correctAnswer = solveMath(a, op, b);
                    if (correctAnswer == null) return;

                    // ~20% chance to be off by 1-3 to look more human
                    long finalAnswer = correctAnswer;
                    if (config.mathFuzzEnabled && ThreadLocalRandom.current().nextInt(5) == 0) {
                        int fuzz = ThreadLocalRandom.current().nextInt(1, 4);
                        if (ThreadLocalRandom.current().nextBoolean()) fuzz = -fuzz;
                        finalAnswer += fuzz;
                    }
                    long answer = finalAnswer;

                    long delayMs = mathDelay(a, op, b, config);
                    delayedAction(delayMs, () -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player != null) {
                            client.player.networkHandler.sendChatMessage(String.valueOf(answer));
                            sendLocalNotification(Text.empty()
                                .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                                .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                                .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                                .append(Text.literal("Solved: ").formatted(Formatting.WHITE))
                                .append(Text.literal(a + " " + op + " " + b + " = " + answer).formatted(Formatting.GREEN, Formatting.ITALIC))
                                .append(Text.literal("!").formatted(Formatting.WHITE))
                            );
                        }
                    });
                    return;
                }
            }

            // Unscramble: "The first to unscramble sseirt wins!"
            if (text.contains("unscramble") && config.unscrambleEnabled) {
                Matcher unscrambleMatcher = UNSCRAMBLE_PATTERN.matcher(text);
                if (unscrambleMatcher.find()) {
                    String scrambled = unscrambleMatcher.group(1);
                    String answer = unscramble(scrambled);

                    if (answer != null) {
                        sendUnscrambleAnswer(scrambled, answer, config);
                    } else if (!config.openRouterApiKey.isBlank()) {
                        // Dictionary failed — try AI fallback in background
                        scheduler.execute(() -> {
                            String aiAnswer = aiUnscramble(scrambled, config.openRouterApiKey);
                            if (aiAnswer != null) {
                                sendUnscrambleAnswer(scrambled, aiAnswer, config);
                            } else {
                                MinecraftClient c = MinecraftClient.getInstance();
                                c.execute(() -> sendLocalNotification(Text.empty()
                                    .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                                    .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                                    .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                                    .append(Text.literal("Could not unscramble: ").formatted(Formatting.WHITE))
                                    .append(Text.literal(scrambled).formatted(Formatting.RED, Formatting.ITALIC))
                                ));
                            }
                        });
                    } else {
                        delayedAction(0, () -> sendLocalNotification(Text.empty()
                            .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                            .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                            .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                            .append(Text.literal("Could not unscramble: ").formatted(Formatting.WHITE))
                            .append(Text.literal(scrambled).formatted(Formatting.RED, Formatting.ITALIC))
                        ));
                    }
                    return;
                }
            }

            // Type: "The first to type something wins!"
            if (text.contains("type") && config.autoTypeEnabled) {
                Matcher typeMatcher = TYPE_PATTERN.matcher(text);
                if (typeMatcher.find()) {
                    String phrase = typeMatcher.group(1).trim();

                    long typeDelay = typeDelay(phrase, config);

                    // ~25% chance to introduce a typo for phrases longer than 4 chars
                    String typed = phrase;
                    if (config.typosEnabled && phrase.length() > 4 && ThreadLocalRandom.current().nextInt(4) == 0) {
                        typed = addTypo(phrase);
                    }
                    String finalTyped = typed;

                    delayedAction(typeDelay, () -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player != null) {
                            client.player.networkHandler.sendChatMessage(finalTyped);
                            sendLocalNotification(Text.empty()
                                .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                                .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                                .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                                .append(Text.literal("Typed: ").formatted(Formatting.WHITE))
                                .append(Text.literal(finalTyped).formatted(Formatting.GREEN, Formatting.ITALIC))
                                .append(Text.literal("!").formatted(Formatting.WHITE))
                            );
                        }
                    });
                }
            }
        }
    }

    /**
     * Human-like delay for math based on difficulty.
     * Small + simple (5+3) = quick. Big numbers or multiply/divide = slower.
     */
    private long mathDelay(long a, String op, long b, SolarHelperConfig config) {
        ThreadLocalRandom rand = ThreadLocalRandom.current();
        long base = config.challengeMinDelayMs;
        long range = Math.max(0, config.challengeMaxDelayMs - config.challengeMinDelayMs);

        // Digit complexity: more digits = harder to compute mentally
        int digits = String.valueOf(a).length() + String.valueOf(b).length();
        // 2 digits total (e.g. 5+3) → 0 extra, 4 digits (e.g. 12+34) → ~800ms, 6 digits → ~1600ms
        long digitPenalty = Math.max(0, (digits - 2)) * (300 + rand.nextLong(100));

        // Operation complexity
        long opPenalty = switch (op) {
            case "+", "-" -> 0;
            case "*", "x" -> 800 + rand.nextLong(600);   // multiplication takes a sec
            case "/" -> 1000 + rand.nextLong(800);         // division is hardest
            default -> 0;
        };

        // Also simulate typing the answer — more digits in answer = more time
        long answerLen = String.valueOf(Math.abs(a + b)).length(); // rough estimate
        long typePenalty = answerLen * (60 + rand.nextLong(40));

        return base + (range > 0 ? rand.nextLong(range) : 0) + digitPenalty + opPenalty + typePenalty;
    }

    /**
     * Human-like delay for unscrambling based on word length.
     * Most people solve even longer words (trapdoor, difference) in ~2-3 seconds.
     */
    private long unscrambleDelay(String scrambled, SolarHelperConfig config) {
        ThreadLocalRandom rand = ThreadLocalRandom.current();

        int len = scrambled.length();
        long thinkTime;
        if (len <= 4) {
            thinkTime = 1200 + rand.nextLong(400);
        } else if (len <= 6) {
            thinkTime = 1500 + rand.nextLong(500);
        } else if (len <= 9) {
            thinkTime = 2000 + rand.nextLong(800);
        } else {
            thinkTime = 2500 + rand.nextLong(900);
        }

        // Typing the answer
        long typePenalty = len * (40 + rand.nextLong(30));

        return thinkTime + typePenalty;
    }

    /**
     * Human-like delay for typing challenges.
     * Reading time + per-character typing speed that varies.
     */
    private long typeDelay(String phrase, SolarHelperConfig config) {
        ThreadLocalRandom rand = ThreadLocalRandom.current();
        long base = config.challengeMinDelayMs;
        long range = Math.max(0, config.challengeMaxDelayMs - config.challengeMinDelayMs);

        // Reading time — longer phrases take longer to read before you start typing
        int words = phrase.split("\\s+").length;
        long readTime = words * (150 + rand.nextLong(100)); // ~150-250ms per word to read

        // Typing speed: ~40-80ms per character with occasional pauses
        long typingTime = 0;
        for (int i = 0; i < phrase.length(); i++) {
            typingTime += 40 + rand.nextLong(40);
            // ~10% chance of a small pause mid-typing (like hesitating or looking back)
            if (rand.nextInt(10) == 0) {
                typingTime += 200 + rand.nextLong(300);
            }
        }

        return base + (range > 0 ? rand.nextLong(range) : 0) + readTime + typingTime;
    }

    private void sendUnscrambleAnswer(String scrambled, String answer, SolarHelperConfig config) {
        long delay = unscrambleDelay(scrambled, config);
        delayedAction(delay, () -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.networkHandler.sendChatMessage(answer);
                sendLocalNotification(Text.empty()
                    .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                    .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal("Unscrambled: ").formatted(Formatting.WHITE))
                    .append(Text.literal(scrambled).formatted(Formatting.GRAY, Formatting.ITALIC))
                    .append(Text.literal(" -> ").formatted(Formatting.WHITE))
                    .append(Text.literal(answer).formatted(Formatting.GREEN, Formatting.ITALIC))
                    .append(Text.literal("!").formatted(Formatting.WHITE))
                );
            }
        });
    }

    private String aiUnscramble(String scrambled, String apiKey) {
        try {
            HttpClient http = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();

            String jsonBody = "{\"model\":\"google/gemini-2.0-flash-001\","
                + "\"messages\":["
                + "{\"role\":\"system\",\"content\":\"You are a word unscrambling tool. "
                + "You MUST respond with exactly ONE word and NOTHING else. "
                + "No punctuation, no explanation, no extra text. Just the single unscrambled English word.\"},"
                + "{\"role\":\"user\",\"content\":\"" + scrambled + "\"}"
                + "],\"max_tokens\":16,\"temperature\":0}";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://openrouter.ai/api/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(8))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                // Grab the last "content" match — that's the assistant's reply
                Pattern contentPattern = Pattern.compile("\"content\"\\s*:\\s*\"([^\"]+)\"");
                Matcher contentMatcher = contentPattern.matcher(response.body());
                String lastContent = null;
                while (contentMatcher.find()) {
                    lastContent = contentMatcher.group(1);
                }
                if (lastContent != null) {
                    String result = lastContent.trim().toLowerCase().replaceAll("[^a-z]", "");
                    if (!result.isEmpty()) {
                        LOGGER.info("AI unscrambled '{}' -> '{}'", scrambled, result);
                        return result;
                    }
                }
            } else {
                LOGGER.warn("OpenRouter API returned status {}", response.statusCode());
            }
        } catch (Exception e) {
            LOGGER.warn("AI unscramble failed: {}", e.getMessage());
        }
        return null;
    }

    // Keyboard neighbors for realistic fat-finger typos
    private static final Map<Character, String> NEARBY_KEYS = Map.ofEntries(
        Map.entry('q', "wa"), Map.entry('w', "qeas"), Map.entry('e', "wrds"),
        Map.entry('r', "etdf"), Map.entry('t', "ryfg"), Map.entry('y', "tugh"),
        Map.entry('u', "yijh"), Map.entry('i', "uokj"), Map.entry('o', "iplk"),
        Map.entry('p', "ol"), Map.entry('a', "qwsz"), Map.entry('s', "wedxza"),
        Map.entry('d', "erfcxs"), Map.entry('f', "rtgvcd"), Map.entry('g', "tyhbvf"),
        Map.entry('h', "yujnbg"), Map.entry('j', "uikmnh"), Map.entry('k', "iolmj"),
        Map.entry('l', "opk"), Map.entry('z', "asx"), Map.entry('x', "sdcz"),
        Map.entry('c', "dfvx"), Map.entry('v', "fgbc"), Map.entry('b', "ghnv"),
        Map.entry('n', "hjmb"), Map.entry('m', "jkn")
    );

    private String addTypo(String input) {
        char[] chars = input.toCharArray();
        ThreadLocalRandom rand = ThreadLocalRandom.current();

        // Pick a random letter position (skip spaces)
        List<Integer> letterPositions = new ArrayList<>();
        for (int i = 0; i < chars.length; i++) {
            if (Character.isLetter(chars[i])) letterPositions.add(i);
        }
        if (letterPositions.isEmpty()) return input;

        int pos = letterPositions.get(rand.nextInt(letterPositions.size()));
        char original = Character.toLowerCase(chars[pos]);

        int typoType = rand.nextInt(3);
        switch (typoType) {
            case 0 -> {
                // Swap with neighbor key
                String neighbors = NEARBY_KEYS.getOrDefault(original, "");
                if (!neighbors.isEmpty()) {
                    char replacement = neighbors.charAt(rand.nextInt(neighbors.length()));
                    chars[pos] = Character.isUpperCase(chars[pos]) ? Character.toUpperCase(replacement) : replacement;
                }
            }
            case 1 -> {
                // Swap two adjacent characters
                if (pos + 1 < chars.length && chars[pos + 1] != ' ') {
                    char tmp = chars[pos];
                    chars[pos] = chars[pos + 1];
                    chars[pos + 1] = tmp;
                }
            }
            case 2 -> {
                // Double a letter (e.g., "helllo")
                String s = new String(chars);
                return s.substring(0, pos + 1) + chars[pos] + s.substring(pos + 1);
            }
        }
        return new String(chars);
    }

    private Long solveMath(long a, String op, long b) {
        return switch (op) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*", "x" -> a * b;
            case "/" -> b != 0 ? a / b : null;
            default -> null;
        };
    }

    /** Returns true if remote is a newer semver than current (e.g. "1.3.0" > "1.2.0"). */
    private static boolean isNewerVersion(String remote, String current) {
        try {
            int[] r = parseVersion(remote);
            int[] c = parseVersion(current);
            for (int i = 0; i < 3; i++) {
                if (r[i] > c[i]) return true;
                if (r[i] < c[i]) return false;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static int[] parseVersion(String version) {
        String[] parts = version.split("\\.");
        int[] nums = new int[3];
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            nums[i] = Integer.parseInt(parts[i]);
        }
        return nums;
    }
}
