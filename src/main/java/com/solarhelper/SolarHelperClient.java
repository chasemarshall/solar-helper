package com.solarhelper;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
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

    private static final String MOD_VERSION = "1.2.0";
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

    private long lastSellallTime = 0;
    private long lastWelcomeTime = 0;

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
                    if (!latestVersion.equals(MOD_VERSION)) {
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
        scheduler.schedule(() -> client.execute(action), delayMs, TimeUnit.MILLISECONDS);
    }

    private void handleMessage(String text) {
        SolarHelperConfig config = SolarHelperConfig.get();
        long now = System.currentTimeMillis();

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

                delayedAction(0, () -> {
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
                    if (ThreadLocalRandom.current().nextInt(5) == 0) {
                        int fuzz = ThreadLocalRandom.current().nextInt(1, 4);
                        if (ThreadLocalRandom.current().nextBoolean()) fuzz = -fuzz;
                        finalAnswer += fuzz;
                    }
                    long answer = finalAnswer;

                    long delayMs = config.randomChallengeDelay();
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

                    if (answer == null) {
                        delayedAction(0, () -> sendLocalNotification(Text.empty()
                            .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                            .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                            .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                            .append(Text.literal("Could not unscramble: ").formatted(Formatting.WHITE))
                            .append(Text.literal(scrambled).formatted(Formatting.RED, Formatting.ITALIC))
                        ));
                        return;
                    }

                    long unscrambleDelay = config.randomChallengeDelay();
                    delayedAction(unscrambleDelay, () -> {
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
                    return;
                }
            }

            // Type: "The first to type something wins!"
            if (text.contains("type") && config.autoTypeEnabled) {
                Matcher typeMatcher = TYPE_PATTERN.matcher(text);
                if (typeMatcher.find()) {
                    String phrase = typeMatcher.group(1).trim();

                    // Simulate typing speed: ~80-150ms per character + base delay
                    long perCharMs = 80 + ThreadLocalRandom.current().nextLong(70);
                    long typingTime = phrase.length() * perCharMs;
                    long baseDelay = config.randomChallengeDelay();
                    long typeDelay = baseDelay + typingTime;

                    // ~25% chance to introduce a typo for phrases longer than 4 chars
                    String typed = phrase;
                    if (phrase.length() > 4 && ThreadLocalRandom.current().nextInt(4) == 0) {
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
}
