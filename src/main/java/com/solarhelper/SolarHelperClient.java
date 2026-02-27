package com.solarhelper;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
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
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SolarHelperClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("solarhelper");

    private static final String MOD_VERSION = "1.6.9";
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

    // Matches: "The first to unreverse rehten wins!"
    private static final Pattern UNREVERSE_PATTERN = Pattern.compile(
        "The first to unreverse\\s+(\\S+)\\s+wins!"
    );

    // Matches: "The first to read a8Kx3mQ wins!" (random letter/number strings)
    private static final Pattern READ_PATTERN = Pattern.compile(
        "The first to read\\s+(\\S+)\\s+wins!"
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

    // ── Dropper Solver state ──
    private static volatile boolean dropperActive   = false;
    private static volatile int     dropperForward  = 0;  // -1, 0, or 1
    private static volatile int     dropperSideways = 0;  // -1=right, 0=none, 1=left
    private static double[] cachedWaterCenter = null;     // computed once per activation

    // Smooth rotation (render-thread, FPS-independent — same pattern as tickRotation)
    // dropperTargetYaw is written on game tick thread, read on render thread → volatile
    private static volatile float dropperTargetYaw  = 0f;
    private static float dropperCurrentYaw          = 0f; // render thread only
    private static long  dropperLastFrameNs         = 0;  // render thread only
    // Degrees per second the simulated "hand" tracks toward the target angle.
    // ~220 °/s feels like a deliberate but not robotic mouse movement.
    private static final float DROPPER_ROT_SPEED    = 220f;

    // Only recompute steering every N game ticks (not every 50 ms = still responsive at 200 ms)
    private static int dropperSteerCounter  = 0;
    private static final int DROPPER_STEER_TICKS = 4;

    public static boolean isDropperActive() { return dropperActive; }
    public static int getDropperForward()   { return dropperForward; }
    public static int getDropperSideways()  { return dropperSideways; }

    private static void startDropper() {
        dropperActive     = true;
        dropperForward    = 0;
        dropperSideways   = 0;
        dropperSteerCounter = 0;
        cachedWaterCenter = null;
        dropperLastFrameNs = 0; // will be initialised on first render frame
    }

    public static void stopDropper() {
        dropperActive   = false;
        dropperForward  = 0;
        dropperSideways = 0;
        cachedWaterCenter = null;
        dropperLastFrameNs = 0;
    }

    /**
     * Called every render frame (via GameRendererMixin) to smoothly lerp the player's
     * yaw toward dropperTargetYaw at a fixed angular speed, independent of FPS.
     */
    public static void tickDropperRotation() {
        if (!dropperActive) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        long now = System.nanoTime();

        // First frame after activation — seed current yaw from the actual player yaw
        if (dropperLastFrameNs == 0) {
            dropperCurrentYaw  = client.player.getYaw();
            dropperTargetYaw   = dropperCurrentYaw;
            dropperLastFrameNs = now;
            return;
        }

        float dt = (now - dropperLastFrameNs) / 1_000_000_000f;
        dropperLastFrameNs = now;
        // Guard against lag spikes / pauses
        if (dt > 0.1f) dt = 0.1f;

        // Shortest-path angle difference
        float diff = dropperTargetYaw - dropperCurrentYaw;
        while (diff >  180f) diff -= 360f;
        while (diff < -180f) diff += 360f;

        float maxStep = DROPPER_ROT_SPEED * dt;
        float step    = Math.abs(diff) <= maxStep ? diff : Math.signum(diff) * maxStep;
        dropperCurrentYaw += step;

        client.player.setYaw(dropperCurrentYaw);
    }

    // ── Head Seeker state ──
    public enum HeadSeekState { IDLE, ROTATING, MOVING, APPROACHING, INTERACTING }
    private static volatile boolean headSeekActive = false;
    private static HeadSeekState headSeekState = HeadSeekState.IDLE;
    private static BlockPos headSeekTarget = null;
    private static int interactTicks = 0;       // how many ticks we've been right-clicking
    private static int interactAttempts = 0;    // how many interaction cycles we've tried on this head
    private static int flyDoubleTapTick = -1;   // countdown for double-tap jump to start flying
    private static final float SEEK_ROTATION_SPEED = 0.10f; // lerp factor per frame

    // Pathfinding
    private static List<BlockPos> seekPath = null;  // computed A* path waypoints
    private static int seekPathIndex = 0;            // current waypoint we're walking toward
    private static BlockPos currentWaypoint = null;  // the waypoint we're actively navigating to
    private static int pathRecalcCooldown = 0;       // ticks until we can recalculate path

    // Stuck detection
    private static double lastSeekX = 0, lastSeekY = 0, lastSeekZ = 0;
    private static int stuckTicks = 0;          // how many ticks position hasn't changed
    private static boolean seekJumping = false;  // trying to jump over obstacle

    public static boolean isHeadSeekActive() {
        return headSeekActive;
    }

    public static HeadSeekState getHeadSeekState() {
        return headSeekState;
    }

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

    // ── Auto Island state ──
    private static volatile boolean pendingAutoIsland = false;
    private static long autoIslandSentTime = 0;
    private static boolean autoIslandScreenHandled = false;

    // Track when we send messages so we don't stop auto-farm from our own chat
    private static volatile long lastOwnMessageTime = 0;
    private static final long OWN_MESSAGE_GRACE_MS = 1000; // ignore our name in chat for 1s after sending

    private long lastSellallTime = 0;
    private long lastWelcomeTime = 0;

    // Auto-update state — set by checkForUpdates(), consumed by downloadUpdate()
    private static volatile String pendingUpdateVersion = null;
    private static volatile String pendingUpdateUrl     = null;

    private KeyBinding autoFarmKeybind;
    private KeyBinding headSeekKeybind;
    private KeyBinding dropperKeybind;

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

        // Register head-seek keybind (H key)
        headSeekKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.solarhelper.headseek",
            InputUtil.Type.KEYSYM,
            InputUtil.GLFW_KEY_H,
            KeyBinding.Category.MISC
        ));

        // Register dropper-solver keybind (J key)
        dropperKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.solarhelper.dropper",
            InputUtil.Type.KEYSYM,
            InputUtil.GLFW_KEY_J,
            KeyBinding.Category.MISC
        ));

        // Client-side command that downloads and installs a pending update
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess) -> dispatcher.register(
                net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("shupdate")
                    .executes(ctx -> {
                        if (pendingUpdateUrl != null && pendingUpdateVersion != null) {
                            startDownloadUpdate(pendingUpdateVersion, pendingUpdateUrl);
                        }
                        return 1;
                    })
            )
        );

        // Load dictionary + check for updates in background
        scheduler.execute(this::loadDictionary);
        scheduler.execute(this::checkForUpdates);

        // Show update notification when the player joins a world/server
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
            (handler, sender, client) -> {
                if (pendingUpdateVersion == null) return;
                final String ver = pendingUpdateVersion;
                final String url = pendingUpdateUrl;
                // Clear immediately so switching servers doesn't show it a second time
                pendingUpdateVersion = null;
                pendingUpdateUrl     = null;
                // Wait 10 seconds after joining so the notification doesn't get buried in join messages
                scheduler.schedule(() -> client.execute(() -> showUpdateNotification(ver, url)),
                    10, TimeUnit.SECONDS);
            }
        );

        // Listen for game messages (server-sent messages like join notifications)
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String text = message.getString();
            handleMessage(text);
            // Auto-raffle: detect clickable raffle messages
            if (SolarHelperConfig.get().autoRaffleEnabled && text.contains("Click here to join the raffle")) {
                handleRaffleClick(message);
            }
        });

        // Also listen for chat messages in case it comes through as player chat
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            handleMessage(message.getString());
        });

        // Track when we send any message (chat or command) so we can ignore our own name in chat
        ClientSendMessageEvents.CHAT.register(message -> {
            lastOwnMessageTime = System.currentTimeMillis();
        });
        ClientSendMessageEvents.COMMAND.register(command -> {
            lastOwnMessageTime = System.currentTimeMillis();

            // Detect /is command for auto-island
            if (SolarHelperConfig.get().autoIslandEnabled && command.equalsIgnoreCase("is")) {
                pendingAutoIsland = true;
                autoIslandSentTime = System.currentTimeMillis();
                autoIslandScreenHandled = false;
                LOGGER.info("Detected /is command, watching for island menu...");
            }
        });

        // Tick handler for auto-farm toggle + rotation + head click dismiss
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            SolarHelperConfig config = SolarHelperConfig.get();

            // Check if player clicked a highlighted head to dismiss it
            HeadOutlineRenderer.checkClickDismiss();

            // Auto-island: scan for ender pearl in /is menu and click it
            if (pendingAutoIsland && !autoIslandScreenHandled && client.currentScreen instanceof HandledScreen<?> handledScreen) {
                long elapsed = System.currentTimeMillis() - autoIslandSentTime;
                // Timeout after 5 seconds
                if (elapsed > 5000) {
                    pendingAutoIsland = false;
                } else if (elapsed >= config.autoIslandDelayMs) {
                    // Scan slots for ender pearl
                    var handler = handledScreen.getScreenHandler();
                    for (Slot slot : handler.slots) {
                        if (slot.hasStack() && slot.getStack().getItem() == Items.ENDER_PEARL) {
                            // Click the ender pearl slot
                            client.interactionManager.clickSlot(
                                handler.syncId, slot.id, 0, SlotActionType.PICKUP, client.player
                            );
                            autoIslandScreenHandled = true;
                            pendingAutoIsland = false;
                            sendLocalNotification(Text.empty()
                                .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                                .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                                .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                                .append(Text.literal("Auto-clicked island teleport!").formatted(Formatting.GREEN))
                            );
                            LOGGER.info("Auto-clicked ender pearl in /is menu (slot {})", slot.id);
                            break;
                        }
                    }
                }
            }

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

            // Toggle head-seek on keybind press
            if (headSeekKeybind.wasPressed()) {
                if (!config.headSeekEnabled) {
                    sendLocalNotification(Text.empty()
                        .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                        .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("Head Seeker is disabled in settings.").formatted(Formatting.RED))
                    );
                } else if (headSeekActive) {
                    stopHeadSeek();
                } else {
                    // Stop auto-farm if running
                    if (autoFarmActive) stopAutoFarm();
                    headSeekActive = true;
                    headSeekState = HeadSeekState.IDLE;
                    headSeekTarget = null;
                    pickNextHead();
                    sendLocalNotification(Text.empty()
                        .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                        .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("Head Seeker ").formatted(Formatting.WHITE))
                        .append(Text.literal("ON").formatted(Formatting.GREEN, Formatting.BOLD))
                    );
                }
            }

            // Toggle dropper solver on keybind press
            if (dropperKeybind.wasPressed()) {
                if (!config.dropperEnabled) {
                    sendLocalNotification(Text.empty()
                        .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                        .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("Dropper Solver is disabled in settings.").formatted(Formatting.RED))
                    );
                } else if (dropperActive) {
                    stopDropper();
                    sendLocalNotification(Text.empty()
                        .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                        .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("Dropper Solver ").formatted(Formatting.WHITE))
                        .append(Text.literal("OFF").formatted(Formatting.RED, Formatting.BOLD))
                    );
                } else {
                    if (autoFarmActive) stopAutoFarm();
                    startDropper();
                    sendLocalNotification(Text.empty()
                        .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                        .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("Dropper Solver ").formatted(Formatting.WHITE))
                        .append(Text.literal("ON").formatted(Formatting.GREEN, Formatting.BOLD))
                        .append(Text.literal(" — scanning for water...").formatted(Formatting.GRAY))
                    );
                }
            }

            // Dropper Solver tick: compute steering inputs each tick while active
            if (dropperActive && client.player != null && client.world != null) {
                // Auto-stop when player lands or enters water
                if (client.player.isOnGround() || client.player.isTouchingWater()) {
                    stopDropper();
                    sendLocalNotification(Text.empty()
                        .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                        .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("Dropper Solver ").formatted(Formatting.WHITE))
                        .append(Text.literal("landed!").formatted(Formatting.GREEN, Formatting.BOLD))
                    );
                } else {
                    // Find water center once per activation (the pool doesn't move)
                    if (cachedWaterCenter == null) {
                        cachedWaterCenter = DropperSolver.findWaterCenter(
                            client.world,
                            client.player.getX(),
                            client.player.getY(),
                            client.player.getZ()
                        );
                        if (cachedWaterCenter != null) {
                            sendLocalNotification(Text.empty()
                                .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                                .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                                .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                                .append(Text.literal("Target: ").formatted(Formatting.GRAY))
                                .append(Text.literal(
                                    String.format("%.1f, %.0f, %.1f",
                                        cachedWaterCenter[0], cachedWaterCenter[1], cachedWaterCenter[2])
                                ).formatted(Formatting.AQUA))
                            );
                        }
                    }

                    if (cachedWaterCenter != null) {
                        // Recompute steering every DROPPER_STEER_TICKS ticks, not every tick.
                        // This avoids robotic per-tick input flipping and gives a more human
                        // "hold a key for a moment then adjust" feel.
                        dropperSteerCounter++;
                        if (dropperSteerCounter >= DROPPER_STEER_TICKS) {
                            dropperSteerCounter = 0;

                            DropperSolver.SimState state = new DropperSolver.SimState(
                                client.player.getX(), client.player.getY(), client.player.getZ(),
                                client.player.getVelocity().x,
                                client.player.getVelocity().y,
                                client.player.getVelocity().z
                            );
                            // Pass the previous target yaw so the solver can apply hysteresis
                            // (don't switch yaw unless the current path is actually blocked).
                            DropperSolver.Steering steering = DropperSolver.computeSteering(
                                client.world, state, cachedWaterCenter, dropperTargetYaw);
                            dropperTargetYaw = steering.yaw();
                            dropperForward   = steering.forward();
                            dropperSideways  = steering.sideways();
                        }
                        // Actual yaw is smoothed toward dropperTargetYaw in tickDropperRotation()
                    } else {
                        // No water found yet — hold still
                        dropperForward  = 0;
                        dropperSideways = 0;
                    }
                }
            }

            // Stop auto-farm when any screen is opened (inventory, chat, pause menu, etc.)
            // The user has to retoggle R to resume — prevents accidental farming while navigating menus.
            if (autoFarmActive && client.currentScreen != null) {
                stopAutoFarm();
                if (client.options != null) client.options.attackKey.setPressed(false);
                sendLocalNotification(Text.empty()
                    .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                    .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal("Auto Farm stopped — press ").formatted(Formatting.GRAY))
                    .append(autoFarmKeybind.getBoundKeyLocalizedText().copy().formatted(Formatting.WHITE))
                    .append(Text.literal(" to resume.").formatted(Formatting.GRAY))
                );
            }

            // Auto-farm: force attack even when tabbed out (setPressed alone doesn't trigger mining when unfocused)
            if (autoFarmActive && !autoFarmPaused && !inputFrozen && client.player != null && client.interactionManager != null) {
                if (client.options.attackKey.isPressed()) {
                    ((com.solarhelper.mixin.MinecraftClientAccessor) client).invokeHandleBlockBreaking(true);
                }
            }

            // Rotation timer (triggers rotation; actual smooth movement is in tickRotation per-frame)
            // Only count ticks when actually farming (not frozen/paused) to prevent desync
            if (isAutoFarmActive() && client.player != null) {
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

    private static void showUpdateNotification(String latestVersion, String downloadUrl) {
        Text installButton = downloadUrl != null
            ? Text.literal(" [Install]").formatted(Formatting.AQUA, Formatting.BOLD)
                .styled(s -> s.withClickEvent(new ClickEvent.RunCommand("/shupdate"))
                              .withHoverEvent(new net.minecraft.text.HoverEvent.ShowText(
                                  Text.literal("Click to download v" + latestVersion + " and restart"))))
            : Text.empty();

        sendLocalNotification(Text.empty()
            .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
            .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
            .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
            .append(Text.literal("Update available! ").formatted(Formatting.WHITE))
            .append(Text.literal("v" + MOD_VERSION).formatted(Formatting.RED))
            .append(Text.literal(" \u2192 ").formatted(Formatting.GRAY))
            .append(Text.literal("v" + latestVersion).formatted(Formatting.GREEN, Formatting.BOLD))
            .append(installButton)
        );
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

                Pattern tagPattern = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"");
                Matcher tagMatcher = tagPattern.matcher(body);
                if (!tagMatcher.find()) return;
                String latestVersion = tagMatcher.group(1);

                if (!isNewerVersion(latestVersion, MOD_VERSION)) {
                    LOGGER.info("Solar Helper is up to date (v{})", MOD_VERSION);
                    return;
                }

                // Find the main jar download URL (not -sources)
                Pattern assetPattern = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.jar)\"");
                Matcher assetMatcher = assetPattern.matcher(body);
                String downloadUrl = null;
                while (assetMatcher.find()) {
                    String url = assetMatcher.group(1);
                    if (!url.contains("sources")) {
                        downloadUrl = url;
                        break;
                    }
                }

                pendingUpdateVersion = latestVersion;
                pendingUpdateUrl     = downloadUrl;

                LOGGER.info("Update available: v{} -> v{}", MOD_VERSION, latestVersion);
                // Notification is shown on next world join via ClientPlayConnectionEvents.JOIN
            }
        } catch (Exception e) {
            LOGGER.debug("Could not check for updates: {}", e.getMessage());
        }
    }

    private void startDownloadUpdate(String version, String downloadUrl) {
        MinecraftClient client = MinecraftClient.getInstance();

        // Show "downloading..." feedback immediately
        client.execute(() -> sendLocalNotification(Text.empty()
            .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
            .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
            .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
            .append(Text.literal("Downloading v" + version + "...").formatted(Formatting.GRAY))
        ));

        scheduler.execute(() -> {
            try {
                java.nio.file.Path modsDir = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getGameDir().resolve("mods");
                java.nio.file.Path newJar = modsDir.resolve("solar-helper-" + version + ".jar");

                // Download
                HttpClient http = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(java.time.Duration.ofSeconds(15))
                    .build();
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .timeout(java.time.Duration.ofSeconds(60))
                    .build();
                HttpResponse<java.nio.file.Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(newJar));

                if (resp.statusCode() != 200) {
                    client.execute(() -> sendLocalNotification(
                        Text.literal("Download failed (HTTP " + resp.statusCode() + ")").formatted(Formatting.RED)));
                    java.nio.file.Files.deleteIfExists(newJar);
                    return;
                }

                // Delete old solar-helper jars (keep the one we just downloaded)
                try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(modsDir)) {
                    stream.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.matches("solar-helper-[\\d.]+\\.jar") && !p.equals(newJar);
                    }).forEach(p -> {
                        try { java.nio.file.Files.delete(p); } catch (Exception ignored) {}
                    });
                }

                // Clear pending state so the button doesn't fire twice
                pendingUpdateVersion = null;
                pendingUpdateUrl     = null;

                client.execute(() -> sendLocalNotification(Text.empty()
                    .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                    .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal("Solar Helper updated to v" + version + "! ").formatted(Formatting.GREEN, Formatting.BOLD))
                    .append(Text.literal("Restart Minecraft to apply.").formatted(Formatting.WHITE))
                ));

            } catch (Exception e) {
                LOGGER.error("Update download failed", e);
                client.execute(() -> sendLocalNotification(
                    Text.literal("Update failed: " + e.getMessage()).formatted(Formatting.RED)));
            }
        });
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
    private static void sendLocalNotification(Text message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.empty(), false);
            client.player.sendMessage(message, false);
            client.player.sendMessage(Text.empty(), false);
        }
    }

    /**
     * Extracts and executes the click event from a raffle message.
     * Walks the Text tree to find a ClickEvent.RunCommand and runs it.
     */
    private void handleRaffleClick(Text message) {
        // Walk the text tree to find the click event
        ClickEvent clickEvent = findClickEvent(message);
        if (!(clickEvent instanceof ClickEvent.RunCommand runCmd)) return;

        String command = runCmd.command();
        if (command == null || command.isEmpty()) return;

        // Small random delay to look human
        long delay = 500 + ThreadLocalRandom.current().nextLong(1500);
        delayedAction(delay, () -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;

            if (command.startsWith("/")) {
                client.player.networkHandler.sendChatCommand(command.substring(1));
            } else {
                client.player.networkHandler.sendChatMessage(command);
            }
            sendLocalNotification(Text.empty()
                .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal("Auto-joined raffle!").formatted(Formatting.GREEN))
            );
            LOGGER.info("Auto-clicked raffle: {}", command);
        });
    }

    /** Recursively searches a Text tree for a ClickEvent. */
    private ClickEvent findClickEvent(Text text) {
        Style style = text.getStyle();
        if (style.getClickEvent() != null) {
            return style.getClickEvent();
        }
        for (Text sibling : text.getSiblings()) {
            ClickEvent found = findClickEvent(sibling);
            if (found != null) return found;
        }
        return null;
    }

    private void delayedAction(long delayMs, Runnable action) {
        MinecraftClient client = MinecraftClient.getInstance();
        SolarHelperConfig config = SolarHelperConfig.get();

        if (config.freezeInputEnabled && delayMs > 0) {
            // Freeze immediately when challenge is detected (player stops to read and think)
            inputFrozen = true;
            scheduler.schedule(() -> client.execute(() -> {
                lastOwnMessageTime = System.currentTimeMillis(); // grace window for name-mention check
                action.run();
                // Unfreeze shortly after sending
                scheduler.schedule(() -> client.execute(() -> inputFrozen = false),
                    200 + ThreadLocalRandom.current().nextLong(300), TimeUnit.MILLISECONDS);
            }), delayMs, TimeUnit.MILLISECONDS);
        } else {
            scheduler.schedule(() -> client.execute(() -> {
                lastOwnMessageTime = System.currentTimeMillis(); // grace window for name-mention check
                action.run();
            }), delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void handleMessage(String text) {
        SolarHelperConfig config = SolarHelperConfig.get();
        long now = System.currentTimeMillis();

        // If someone else mentions our username in chat, stop auto-farm and head-seek for safety
        // Skip if we just sent a message (grace window) since our name appears in our own messages
        MinecraftClient mc = MinecraftClient.getInstance();
        if (config.nameMentionStopEnabled && mc.player != null) {
            String username = mc.player.getNameForScoreboard();
            boolean recentlySentMessage = (System.currentTimeMillis() - lastOwnMessageTime) < OWN_MESSAGE_GRACE_MS;
            if (!recentlySentMessage && text.contains(username)) {
                boolean wasActive = autoFarmActive || headSeekActive || dropperActive;
                if (autoFarmActive) {
                    stopAutoFarm();
                    sendLocalNotification(Text.empty()
                        .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                        .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("Auto Farm stopped — your name was mentioned in chat.").formatted(Formatting.RED))
                    );
                }
                if (headSeekActive) {
                    stopHeadSeek();
                    sendLocalNotification(Text.empty()
                        .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                        .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("Head Seeker stopped — your name was mentioned in chat.").formatted(Formatting.RED))
                    );
                }
                if (dropperActive) {
                    stopDropper();
                    sendLocalNotification(Text.empty()
                        .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                        .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("Dropper Solver stopped — your name was mentioned in chat.").formatted(Formatting.RED))
                    );
                }
                // Play alert sound, look up, and say "sup" to seem natural
                if (wasActive) {
                    mc.execute(() -> {
                        if (mc.player != null) {
                            mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.0f, 1.0f);
                        }
                    });
                    // After ~2 seconds, look up and say sup
                    scheduler.schedule(() -> mc.execute(() -> {
                        if (mc.player != null) {
                            mc.player.setPitch(-20f + ThreadLocalRandom.current().nextFloat() * 10f); // look up
                            mc.player.networkHandler.sendChatMessage("sup");
                            lastOwnMessageTime = System.currentTimeMillis();
                        }
                    }), 1800 + ThreadLocalRandom.current().nextLong(800), TimeUnit.MILLISECONDS);
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

                if (config.aiWelcomeEnabled && !config.openRouterApiKey.isBlank()) {
                    // Generate AI welcome in background, then send with delay
                    scheduler.execute(() -> {
                        String aiMsg = generateAiWelcome(username, config.openRouterApiKey);
                        String welcomeMsg = (aiMsg != null) ? aiMsg : "welcome";
                        delayedAction(delayMs, () -> {
                            MinecraftClient client = MinecraftClient.getInstance();
                            if (client.player != null) {
                                client.player.networkHandler.sendChatMessage(welcomeMsg);
                                sendLocalNotification(Text.empty()
                                    .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                                    .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                                    .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                                    .append(Text.literal("Automatically welcomed ").formatted(Formatting.WHITE))
                                    .append(Text.literal(username).formatted(Formatting.GREEN, Formatting.ITALIC))
                                    .append(Text.literal(": ").formatted(Formatting.WHITE))
                                    .append(Text.literal(welcomeMsg).formatted(Formatting.AQUA, Formatting.ITALIC))
                                );
                            }
                        });
                    });
                } else {
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
                }
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

                    // ~10% chance to be off by exactly 1 to look more human
                    long finalAnswer = correctAnswer;
                    if (config.mathFuzzEnabled && ThreadLocalRandom.current().nextInt(10) == 0) {
                        finalAnswer += ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
                    }
                    long answer = finalAnswer;

                    long rawDelay = mathDelay(a, op, b, config);
                    long ping = getPlayerPing();
                    long delayMs = Math.max(200, rawDelay - ping);
                    ChallengeStats.get().setPending("math", delayMs, ping, a + " " + op + " " + b);
                    delayedAction(delayMs, () -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player != null) {
                            client.player.networkHandler.sendChatMessage(String.valueOf(answer));
                            String estSeconds = String.format("%.2f", (delayMs + ping) / 1000.0);
                            sendLocalNotification(Text.empty()
                                .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                                .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                                .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                                .append(Text.literal("math ").formatted(Formatting.GOLD))
                                .append(Text.literal(a + " " + op + " " + b + " = ").formatted(Formatting.GRAY))
                                .append(Text.literal(String.valueOf(answer)).formatted(Formatting.GREEN, Formatting.BOLD))
                                .append(Text.literal("  \u00b7  ").formatted(Formatting.DARK_GRAY))
                                .append(Text.literal("~" + estSeconds + "s").formatted(Formatting.AQUA))
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

            // Unreverse: "The first to unreverse rehten wins!"
            if (text.contains("unreverse") && config.unreverseEnabled) {
                Matcher unreverseMatcher = UNREVERSE_PATTERN.matcher(text);
                if (unreverseMatcher.find()) {
                    String reversed = unreverseMatcher.group(1);
                    String answer = new StringBuilder(reversed).reverse().toString();

                    long rawDelay = unreverseDelay(reversed, config);
                    long ping = getPlayerPing();
                    long delay = Math.max(200, rawDelay - ping);
                    ChallengeStats.get().setPending("unreverse", delay, ping, reversed);
                    delayedAction(delay, () -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player != null) {
                            client.player.networkHandler.sendChatMessage(answer);
                            String estSeconds = String.format("%.2f", (delay + ping) / 1000.0);
                            sendLocalNotification(Text.empty()
                                .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                                .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                                .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                                .append(Text.literal("unreverse ").formatted(Formatting.GOLD))
                                .append(Text.literal(reversed).formatted(Formatting.GRAY, Formatting.ITALIC))
                                .append(Text.literal(" \u2192 ").formatted(Formatting.DARK_GRAY))
                                .append(Text.literal(answer).formatted(Formatting.GREEN, Formatting.ITALIC))
                                .append(Text.literal("  \u00b7  ").formatted(Formatting.DARK_GRAY))
                                .append(Text.literal("~" + estSeconds + "s").formatted(Formatting.AQUA))
                            );
                        }
                    });
                    return;
                }
            }

            // Read: "The first to read a8Kx3mQ wins!" (random letter/number combos)
            if (text.contains("read") && config.readSolverEnabled) {
                Matcher readMatcher = READ_PATTERN.matcher(text);
                if (readMatcher.find()) {
                    String randomStr = readMatcher.group(1);

                    long rawDelay = readDelay(randomStr, config);
                    long ping = getPlayerPing();
                    long delay = Math.max(200, rawDelay - ping);
                    ChallengeStats.get().setPending("read", delay, ping, randomStr);
                    delayedAction(delay, () -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player != null) {
                            client.player.networkHandler.sendChatMessage(randomStr);
                            String estSeconds = String.format("%.2f", (delay + ping) / 1000.0);
                            sendLocalNotification(Text.empty()
                                .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                                .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                                .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                                .append(Text.literal("read ").formatted(Formatting.GOLD))
                                .append(Text.literal(randomStr).formatted(Formatting.GREEN, Formatting.ITALIC))
                                .append(Text.literal("  \u00b7  ").formatted(Formatting.DARK_GRAY))
                                .append(Text.literal("~" + estSeconds + "s").formatted(Formatting.AQUA))
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

                    long typeDelay = typeDelay(phrase, config);
                    long typePing = getPlayerPing();
                    long typeActualDelay = Math.max(200, typeDelay - typePing);
                    ChallengeStats.get().setPending("type", typeActualDelay, typePing, phrase);

                    // ~12.5% chance to introduce a typo for phrases longer than 4 chars
                    String typed = phrase;
                    if (config.typosEnabled && phrase.length() > 4 && ThreadLocalRandom.current().nextInt(8) == 0) {
                        typed = addTypo(phrase);
                    }
                    String finalTyped = typed;

                    String estSeconds = String.format("%.2f", (typeActualDelay + typePing) / 1000.0);
                    delayedAction(typeDelay, () -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player != null) {
                            client.player.networkHandler.sendChatMessage(finalTyped);
                            sendLocalNotification(Text.empty()
                                .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                                .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                                .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                                .append(Text.literal("type ").formatted(Formatting.GOLD))
                                .append(Text.literal(finalTyped).formatted(Formatting.GREEN, Formatting.ITALIC))
                                .append(Text.literal("  \u00b7  ").formatted(Formatting.DARK_GRAY))
                                .append(Text.literal("~" + estSeconds + "s").formatted(Formatting.AQUA))
                            );
                        }
                    });
                }
            }
        }

        // Track challenge results — "[player] unscrambled/solved/typed ... in [time]s!"
        if (text.contains(" in ") && text.contains("s!")) {
            MinecraftClient resultClient = MinecraftClient.getInstance();
            if (resultClient.player != null) {
                String ourName = resultClient.player.getNameForScoreboard();
                ChallengeStats.ChallengeResult result = ChallengeStats.get().parseResult(text, ourName);
                if (result != null) {
                    ChallengeStats.Stats allStats = ChallengeStats.get().getStats(null);
                    ChallengeStats.Stats typeStats = ChallengeStats.get().getStats(result.type);

                    var notification = Text.empty()
                        .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                        .append(Text.literal("] ").formatted(Formatting.DARK_GRAY));

                    if (result.weWon) {
                        notification.append(Text.literal("\u2714 WIN").formatted(Formatting.GREEN, Formatting.BOLD));
                    } else if (result.shouldHaveWon) {
                        notification.append(Text.literal("\u2718 LOSS").formatted(Formatting.RED, Formatting.BOLD))
                            .append(Text.literal("*").formatted(Formatting.YELLOW, Formatting.BOLD));
                    } else {
                        notification.append(Text.literal("\u2718 LOSS").formatted(Formatting.RED));
                    }

                    notification
                        .append(Text.literal("  \u00b7  ").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal(result.winner).formatted(Formatting.AQUA))
                        .append(Text.literal(" ").formatted(Formatting.GRAY))
                        .append(Text.literal(String.format("%.2fs", result.winnerTimeS)).formatted(Formatting.WHITE));

                    if (result.ourEstTimeS > 0) {
                        notification
                            .append(Text.literal("  \u00b7  ").formatted(Formatting.DARK_GRAY))
                            .append(Text.literal("est ").formatted(Formatting.GRAY))
                            .append(Text.literal(String.format("%.2fs", result.ourEstTimeS)).formatted(
                                result.ourEstTimeS <= result.winnerTimeS ? Formatting.GREEN : Formatting.RED));
                    }

                    notification
                        .append(Text.literal("  \u00b7  ").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal(result.type + " ").formatted(Formatting.GOLD))
                        .append(Text.literal(String.format("%d/%d", typeStats.wins, typeStats.total)).formatted(Formatting.GRAY))
                        .append(Text.literal(String.format(" (%.0f%%)", typeStats.winRate())).formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("  \u00b7  ").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal("all ").formatted(Formatting.GRAY))
                        .append(Text.literal(String.format("%d/%d", allStats.wins, allStats.total)).formatted(Formatting.GRAY))
                        .append(Text.literal(String.format(" (%.0f%%)", allStats.winRate())).formatted(Formatting.DARK_GRAY));

                    sendLocalNotification(notification);
                    LOGGER.info("Challenge result: {} {} in {}s (we: ~{}s) — {} win rate: {}%, overall: {}%",
                        result.winner, result.type, result.winnerTimeS, result.ourEstTimeS,
                        result.type, String.format("%.0f", typeStats.winRate()), String.format("%.0f", allStats.winRate()));
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

        // Trivially obvious answers — you see it instantly, no mental work needed.
        // e.g. 3*0=0, 0+99=99, 7*1=7, 100-100=0, 0/5=0
        boolean multOrDiv = op.equals("*") || op.equals("x") || op.equals("/");
        boolean addOrSub  = op.equals("+") || op.equals("-");
        boolean trivial =
            (b == 0 && (multOrDiv || addOrSub))    // anything * / + - 0
         || (a == 0 && (multOrDiv || op.equals("+"))) // 0 * / + anything
         || (b == 1 && multOrDiv)                   // anything * / 1
         || (a == 1 && (op.equals("*") || op.equals("x"))) // 1 * anything
         || (a == b && op.equals("-"));             // x - x = 0
        if (trivial) {
            // Still pause briefly to read the question, but the answer is instant
            return 600 + rand.nextLong(700); // ~0.6–1.3s total
        }

        long base = config.challengeMinDelayMs;
        long range = Math.max(0, config.challengeMaxDelayMs - config.challengeMinDelayMs);

        // Digit complexity: more digits = harder to compute mentally
        int digits = String.valueOf(a).length() + String.valueOf(b).length();
        // 2 digits total (e.g. 5+3) → 0 extra, 4 digits (e.g. 12+34) → ~800ms, 6 digits → ~1600ms
        long digitPenalty = Math.max(0, (digits - 2)) * (300 + rand.nextLong(100));

        // Operation complexity — context-aware
        long opPenalty = switch (op) {
            case "+" -> {
                // Carrying makes addition harder: 99+99 harder than 10+20
                boolean hasCarry = (a % 10 + b % 10) >= 10;
                yield hasCarry ? 200 + rand.nextLong(200) : 0;
            }
            case "-" -> {
                // Borrowing makes subtraction harder: 100-37 harder than 100-50
                boolean hasBorrow = (a % 10) < (b % 10);
                yield hasBorrow ? 200 + rand.nextLong(200) : 0;
            }
            case "*", "x" -> {
                // Single digit * anything is easier; large * large is brutal
                boolean oneIsSmall = (a <= 12 || b <= 12);
                boolean bothRound = (a % 10 == 0) && (b % 10 == 0);
                if (bothRound) {
                    yield 400 + rand.nextLong(300);       // 20*30 = easy, just move zeros
                } else if (oneIsSmall) {
                    yield 600 + rand.nextLong(400);       // 7*234 = times table
                } else {
                    yield 1200 + rand.nextLong(800);      // 47*83 = hard mental math
                }
            }
            case "/" -> {
                // Even division is way easier than remainder division
                boolean divideByOne = (b == 1);
                boolean evenDivision = (b != 0 && a % b == 0);
                boolean divisorIsRound = (b % 10 == 0 || b == 5 || b == 2);
                if (divideByOne) {
                    yield 0;                               // trivial
                } else if (evenDivision && divisorIsRound) {
                    yield 400 + rand.nextLong(300);       // 100/5 = easy
                } else if (evenDivision) {
                    yield 700 + rand.nextLong(500);       // 144/12 = moderate
                } else {
                    yield 1200 + rand.nextLong(800);      // 137/7 = hard, has remainder
                }
            }
            default -> 0;
        };

        // Typing the actual answer — use real answer length
        Long answer = solveMath(a, op, b);
        long answerLen = answer != null ? String.valueOf(Math.abs(answer)).length() : 2;
        long typePenalty = answerLen * (60 + rand.nextLong(40));

        return base + (range > 0 ? rand.nextLong(range) : 0) + digitPenalty + opPenalty + typePenalty;
    }

    /**
     * Human-like delay for unscrambling based on word length.
     * Calibrated against real game data — previous floor of ~1.5s was not humanly plausible.
     * A real player needs time to read the scrambled letters, mentally rearrange them,
     * recognize the word, then type it. Minimum realistic total: ~2.5s for short words.
     */
    private long unscrambleDelay(String scrambled, SolarHelperConfig config) {
        ThreadLocalRandom rand = ThreadLocalRandom.current();

        int len = scrambled.length();
        long thinkTime;
        if (len <= 4) {
            thinkTime = 2000 + rand.nextLong(1000);  // total ≈ 2.2–3.3s
        } else if (len <= 6) {
            thinkTime = 2500 + rand.nextLong(1000);  // total ≈ 2.8–3.9s
        } else if (len <= 9) {
            thinkTime = 3200 + rand.nextLong(1200);  // total ≈ 3.6–5.0s
        } else {
            thinkTime = 4000 + rand.nextLong(1500);  // total ≈ 4.5–6.3s
        }

        // Typing the answer
        long typePenalty = len * (35 + rand.nextLong(25));

        return thinkTime + typePenalty;
    }

    /**
     * Human-like delay for unreversing words.
     * Reading backwards is slow — you go letter by letter, and longer words
     * take disproportionately longer because you lose track and re-read.
     */
    private long unreverseDelay(String reversed, SolarHelperConfig config) {
        ThreadLocalRandom rand = ThreadLocalRandom.current();

        int len = reversed.length();
        // Per-letter reading time: ~200-350ms per letter reading backwards
        long readTime = len * (200 + rand.nextLong(150));
        // Longer words need re-reads — people lose their place
        if (len >= 8) {
            readTime += 800 + rand.nextLong(600);   // one re-read
        }
        if (len >= 12) {
            readTime += 1000 + rand.nextLong(800);  // probably re-read again
        }
        // Typing the answer
        long typePenalty = len * (35 + rand.nextLong(25));

        return readTime + typePenalty;
    }

    /**
     * Human-like delay for read challenges (random letter/number strings).
     * These are much harder than normal words — you can't touch-type random chars,
     * you have to look at each one, find it on the keyboard, and type it.
     * Uppercase/numbers/mixed case make it even slower.
     */
    private long readDelay(String str, SolarHelperConfig config) {
        ThreadLocalRandom rand = ThreadLocalRandom.current();

        int len = str.length();
        // Initial reading time — scanning the random string
        long readTime = 500 + rand.nextLong(300);

        // Per-character typing: random chars are slow, ~150-300ms each
        // Numbers and uppercase require shift key = slower
        long typingTime = 0;
        for (int i = 0; i < len; i++) {
            char c = str.charAt(i);
            if (Character.isUpperCase(c)) {
                typingTime += 200 + rand.nextLong(150);  // shift + key
            } else if (Character.isDigit(c)) {
                typingTime += 180 + rand.nextLong(120);  // reach to number row
            } else {
                typingTime += 120 + rand.nextLong(100);  // lowercase letter
            }
            // ~15% chance of hesitation (looking back at the string)
            if (rand.nextInt(7) == 0) {
                typingTime += 200 + rand.nextLong(300);
            }
        }

        // Longer strings get a re-read penalty — you lose your place
        if (len >= 10) {
            typingTime += 500 + rand.nextLong(400);
        }
        if (len >= 15) {
            typingTime += 800 + rand.nextLong(500);
        }

        return readTime + typingTime;
    }

    /**
     * Human-like delay for typing challenges.
     * Reading time + per-character typing speed that varies.
     */
    private long typeDelay(String phrase, SolarHelperConfig config) {
        ThreadLocalRandom rand = ThreadLocalRandom.current();
        long base = config.challengeMinDelayMs;
        long range = Math.max(0, config.challengeMaxDelayMs - config.challengeMinDelayMs);

        // Detect random strings (contains digits or has very low vowel ratio — not a real word).
        // Humans can't type these from muscle memory; they have to hunt-and-peck every char.
        boolean hasDigit = phrase.chars().anyMatch(Character::isDigit);
        long vowels = phrase.chars().filter(c -> "aeiouAEIOU".indexOf(c) >= 0).count();
        boolean isRandomString = hasDigit || (phrase.length() >= 6 && vowels < phrase.length() * 0.2);

        // Reading time — random strings take much longer to process visually
        int words = phrase.split("\\s+").length;
        long readTime = words * (150 + rand.nextLong(100)); // ~150-250ms per word
        if (isRandomString) {
            readTime += 800 + rand.nextLong(500); // extra 0.8–1.3s to parse unfamiliar chars
        }

        // Typing speed:
        //   Real words: ~40-80ms per char (muscle memory, predictable)
        //   Random strings: ~120-220ms per char (hunt-and-peck, no prediction)
        long typingTime = 0;
        for (int i = 0; i < phrase.length(); i++) {
            if (isRandomString) {
                typingTime += 120 + rand.nextLong(100); // 120-220ms per char
                // ~25% chance of a hesitation pause on random strings
                if (rand.nextInt(4) == 0) {
                    typingTime += 250 + rand.nextLong(350);
                }
            } else {
                // Real words: slightly slower per-char as length grows (muscle memory fades on longer words)
                int baseMs  = phrase.length() <= 5 ? 38 : phrase.length() <= 8 ? 46 : 54;
                int rangeMs = phrase.length() <= 5 ? 30 : phrase.length() <= 8 ? 34 : 38;
                typingTime += baseMs + rand.nextLong(rangeMs);
                // Slightly more hesitation on longer words (more chars = more chances to lose focus)
                int pauseOdds = phrase.length() <= 5 ? 12 : phrase.length() <= 8 ? 9 : 7;
                if (rand.nextInt(pauseOdds) == 0) {
                    typingTime += 200 + rand.nextLong(300);
                }
            }
        }

        return base + (range > 0 ? rand.nextLong(range) : 0) + readTime + typingTime;
    }

    /** Gets the player's ping to the server in ms, or 0 if unknown. */
    private long getPlayerPing() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.getNetworkHandler() != null) {
            var entry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
            if (entry != null) {
                return entry.getLatency();
            }
        }
        return 0;
    }

    private void sendUnscrambleAnswer(String scrambled, String answer, SolarHelperConfig config) {
        long rawDelay = unscrambleDelay(scrambled, config);
        long ping = getPlayerPing();
        long delay = Math.max(200, rawDelay - ping); // subtract ping, floor at 200ms
        ChallengeStats.get().setPending("unscramble", delay, ping, scrambled);
        delayedAction(delay, () -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.networkHandler.sendChatMessage(answer);
                long estServerTime = delay + ping;
                String estSeconds = String.format("%.2f", estServerTime / 1000.0);
                sendLocalNotification(Text.empty()
                    .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                    .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal("unscramble ").formatted(Formatting.GOLD))
                    .append(Text.literal(scrambled).formatted(Formatting.GRAY, Formatting.ITALIC))
                    .append(Text.literal(" \u2192 ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(answer).formatted(Formatting.GREEN, Formatting.ITALIC))
                    .append(Text.literal("  \u00b7  ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal("~" + estSeconds + "s").formatted(Formatting.AQUA))
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

    /**
     * Uses OpenRouter AI to generate a short, varied welcome message.
     * Strictly 3 words max, must contain "welcome", casual Minecraft chat style.
     */
    private String generateAiWelcome(String username, String apiKey) {
        try {
            HttpClient http = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();

            String jsonBody = "{\"model\":\"google/gemini-2.0-flash-001\","
                + "\"messages\":["
                + "{\"role\":\"system\",\"content\":\"You generate short welcome messages for a Minecraft server chat. "
                + "STRICT RULES: "
                + "1. Max 3 words. "
                + "2. MUST contain 'welcome'. "
                + "3. You may include the player's username if you want. "
                + "4. No quotes, no newlines, no formatting. Just the raw message. "
                + "5. Casual, like a real player typing. "
                + "Good examples: welcome, welcome!, welcome bro, ayy welcome, welcome chase, yo welcome\"},"
                + "{\"role\":\"user\",\"content\":\"" + username + " just joined\"}"
                + "],\"max_tokens\":16,\"temperature\":1.0}";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://openrouter.ai/api/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                Pattern contentPattern = Pattern.compile("\"content\"\\s*:\\s*\"([^\"]+)\"");
                Matcher contentMatcher = contentPattern.matcher(response.body());
                String lastContent = null;
                while (contentMatcher.find()) {
                    lastContent = contentMatcher.group(1);
                }
                if (lastContent != null) {
                    String result = lastContent.trim();
                    // Strip escape sequences, quotes, newlines, curly braces
                    result = result.replace("\\n", " ").replace("\\r", " ");
                    result = result.replaceAll("[\"'{}\\[\\]]", "");
                    // Collapse multiple spaces
                    result = result.replaceAll("\\s+", " ").trim();
                    // Enforce max 3 words
                    String[] words = result.split("\\s+");
                    if (words.length > 3) {
                        result = words[0] + " " + words[1] + " " + words[2];
                    }
                    // Must contain "welcome" (case insensitive)
                    if (result.toLowerCase().contains("welcome") && !result.isEmpty()) {
                        LOGGER.info("AI welcome for '{}': '{}'", username, result);
                        return result;
                    }
                    LOGGER.warn("AI welcome didn't contain 'welcome': '{}'", result);
                }
            } else {
                LOGGER.warn("OpenRouter API returned status {} for welcome", response.statusCode());
            }
        } catch (Exception e) {
            LOGGER.warn("AI welcome generation failed: {}", e.getMessage());
        }
        return null; // fallback to plain "welcome"
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

    // ── Head Seeker logic ──

    /** Called every frame by GameRendererMixin to drive the head-seek state machine. */
    public static void tickHeadSeek() {
        if (!headSeekActive) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        if (pathRecalcCooldown > 0) pathRecalcCooldown--;

        // If we have no target, try to pick one
        if (headSeekTarget == null) {
            pickNextHead();
            if (headSeekTarget == null) {
                // No heads left
                stopHeadSeek();
                client.execute(() -> {
                    if (client.player != null) {
                        client.player.sendMessage(Text.empty(), false);
                        client.player.sendMessage(Text.empty()
                            .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                            .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                            .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                            .append(Text.literal("Head Seeker complete — no more heads!").formatted(Formatting.GREEN)), false);
                        client.player.sendMessage(Text.empty(), false);
                    }
                });
                return;
            }
        }

        // ── Distance to final target (the head block) ──
        double dx = headSeekTarget.getX() + 0.5 - client.player.getX();
        double dy = headSeekTarget.getY() + 0.5 - client.player.getEyeY();
        double dz = headSeekTarget.getZ() + 0.5 - client.player.getZ();
        double horizDist = Math.sqrt(dx * dx + dz * dz);
        double totalDist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // ── Determine what to look at ──
        // When MOVING, look at the current waypoint (next step on the path)
        // When APPROACHING/INTERACTING, look at the head's exposed face
        double aimX, aimY, aimZ;

        if ((headSeekState == HeadSeekState.APPROACHING || headSeekState == HeadSeekState.INTERACTING)
                && totalDist < 5.0) {
            // Aim at exposed face for precise interaction
            double[] faceAim = findExposedFaceAim(client, headSeekTarget);
            if (faceAim != null) {
                aimX = faceAim[0]; aimY = faceAim[1]; aimZ = faceAim[2];
            } else {
                aimX = headSeekTarget.getX() + 0.5;
                aimY = headSeekTarget.getY() + 0.5;
                aimZ = headSeekTarget.getZ() + 0.5;
            }
        } else if (currentWaypoint != null && headSeekState == HeadSeekState.MOVING) {
            // Look at the current waypoint, not the final head
            aimX = currentWaypoint.getX() + 0.5;
            aimY = currentWaypoint.getY() + 0.5; // aim at feet level of waypoint
            aimZ = currentWaypoint.getZ() + 0.5;
        } else {
            aimX = headSeekTarget.getX() + 0.5;
            aimY = headSeekTarget.getY() + 0.5;
            aimZ = headSeekTarget.getZ() + 0.5;
        }

        double aimDx = aimX - client.player.getX();
        double aimDy = aimY - client.player.getEyeY();
        double aimDz = aimZ - client.player.getZ();
        double aimHorizDist = Math.sqrt(aimDx * aimDx + aimDz * aimDz);

        float targetYaw = (float) Math.toDegrees(Math.atan2(-aimDx, aimDz));
        float targetPitch = (float) Math.toDegrees(-Math.atan2(aimDy, aimHorizDist));
        targetPitch = MathHelper.clamp(targetPitch, -90f, 90f);

        // Smooth rotation
        float currentYaw = client.player.getYaw();
        float currentPitch = client.player.getPitch();
        float yawDiff = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float pitchDiff = targetPitch - currentPitch;

        float angleDist = Math.abs(yawDiff) + Math.abs(pitchDiff);
        float speed = SEEK_ROTATION_SPEED + Math.min(angleDist / 1800f, 0.05f);
        if (headSeekState == HeadSeekState.INTERACTING || headSeekState == HeadSeekState.APPROACHING) {
            speed = 0.25f;
        }

        client.player.setYaw(currentYaw + yawDiff * speed);
        client.player.setPitch(currentPitch + pitchDiff * speed);

        boolean aimClose = Math.abs(yawDiff) < 8f && Math.abs(pitchDiff) < 8f;
        boolean aimVeryClose = Math.abs(yawDiff) < 3f && Math.abs(pitchDiff) < 3f;

        // ── Stuck detection while MOVING or APPROACHING ──
        if (headSeekState == HeadSeekState.MOVING || headSeekState == HeadSeekState.APPROACHING) {
            double movedDist = Math.sqrt(
                Math.pow(client.player.getX() - lastSeekX, 2) +
                Math.pow(client.player.getY() - lastSeekY, 2) +
                Math.pow(client.player.getZ() - lastSeekZ, 2)
            );
            if (movedDist < 0.03) {
                stuckTicks++;
            } else {
                stuckTicks = 0;
                seekJumping = false;
            }
            lastSeekX = client.player.getX();
            lastSeekY = client.player.getY();
            lastSeekZ = client.player.getZ();

            // If stuck, try jumping first
            if (stuckTicks >= 8) {
                seekJumping = true;
            }
            // If still stuck after jumping, recalculate path (MOVING only — no path in APPROACHING)
            if (stuckTicks >= 30 && pathRecalcCooldown <= 0 && headSeekState == HeadSeekState.MOVING) {
                recalculatePath(client);
                stuckTicks = 0;
                seekJumping = false;
            }
            // If stuck for way too long, skip this head
            if (stuckTicks >= 80) {
                stuckTicks = 0;
                seekJumping = false;
                headSeekTarget = null;
                seekPath = null;
                currentWaypoint = null;
                headSeekState = HeadSeekState.IDLE;
                return;
            }
        } else {
            stuckTicks = 0;
            seekJumping = false;
        }

        // ── Waypoint advancement ──
        if (headSeekState == HeadSeekState.MOVING && seekPath != null && currentWaypoint != null) {
            double wpDx = currentWaypoint.getX() + 0.5 - client.player.getX();
            double wpDz = currentWaypoint.getZ() + 0.5 - client.player.getZ();
            double wpHorizDist = Math.sqrt(wpDx * wpDx + wpDz * wpDz);

            // Reached this waypoint — advance to next
            if (wpHorizDist < 1.2) {
                seekPathIndex++;
                if (seekPathIndex < seekPath.size()) {
                    currentWaypoint = seekPath.get(seekPathIndex);
                    stuckTicks = 0; // reset stuck on waypoint change
                } else {
                    // Reached end of path, should be close to head
                    seekPath = null;
                    currentWaypoint = null;
                }
            }
        }

        // ── State transitions ──
        switch (headSeekState) {
            case IDLE -> {
                interactAttempts = 0;
                // Compute path to head
                if (totalDist < 5.0) {
                    // Already close, skip pathfinding
                    headSeekState = HeadSeekState.APPROACHING;
                    seekPath = null;
                    currentWaypoint = null;
                } else {
                    computePathToTarget(client);
                    headSeekState = HeadSeekState.ROTATING;
                }
            }
            case ROTATING -> {
                if (aimClose) {
                    headSeekState = HeadSeekState.MOVING;
                    lastSeekX = client.player.getX();
                    lastSeekY = client.player.getY();
                    lastSeekZ = client.player.getZ();
                }
            }
            case MOVING -> {
                // Flying toggle
                boolean canFly = client.player.getAbilities().allowFlying;
                boolean isFlying = client.player.getAbilities().flying;
                if (currentWaypoint != null) {
                    double wpDy = currentWaypoint.getY() - client.player.getY();
                    if (wpDy > 1.5 && canFly && !isFlying && flyDoubleTapTick < 0) {
                        flyDoubleTapTick = 3;
                    }
                    if (wpDy < -3.0 && isFlying && flyDoubleTapTick < 0) {
                        flyDoubleTapTick = 3;
                    }
                }

                // Switch to approach once the A* path is fully walked (waypoint == null)
                // or we somehow got very close. Don't beeline from 5 blocks out.
                if (currentWaypoint == null || totalDist < 2.5) {
                    if (totalDist < 5.0) {
                        headSeekState = HeadSeekState.APPROACHING;
                        seekPath = null;
                        currentWaypoint = null;
                    } else if (currentWaypoint == null && pathRecalcCooldown <= 0) {
                        // Path ended but we're still far — recompute
                        computePathToTarget(client);
                    }
                }
            }
            case APPROACHING -> {
                if (totalDist < 4.5 && aimVeryClose) {
                    headSeekState = HeadSeekState.INTERACTING;
                    interactTicks = 0;
                } else if (totalDist >= 5.0) {
                    // Drifted too far, recompute path
                    computePathToTarget(client);
                    headSeekState = HeadSeekState.MOVING;
                }
            }
            case INTERACTING -> {
                interactTicks++;
                // Check if the head was actually collected
                if (!HeadOutlineRenderer.getHeadPositions().contains(headSeekTarget)) {
                    HeadOutlineRenderer.dismissHead(headSeekTarget);
                    headSeekTarget = null;
                    seekPath = null;
                    currentWaypoint = null;
                    headSeekState = HeadSeekState.IDLE;
                    interactTicks = 0;
                    interactAttempts = 0;
                    return;
                }
                if (interactTicks >= 15) {
                    interactAttempts++;
                    if (interactAttempts >= 5) {
                        // Unreachable, skip
                        headSeekTarget = null;
                        seekPath = null;
                        currentWaypoint = null;
                        headSeekState = HeadSeekState.IDLE;
                        interactTicks = 0;
                        interactAttempts = 0;
                    } else {
                        headSeekState = HeadSeekState.APPROACHING;
                        interactTicks = 0;
                    }
                }
            }
        }

        // Fly double-tap countdown
        if (flyDoubleTapTick >= 0) {
            flyDoubleTapTick--;
        }
    }

    /** Computes an A* path from the player to near the current head target. */
    private static void computePathToTarget(MinecraftClient client) {
        if (client.player == null || client.world == null || headSeekTarget == null) return;
        boolean canFly = client.player.getAbilities().allowFlying;
        BlockPos start = client.player.getBlockPos();
        seekPath = PathFinder.findPath(client.world, start, headSeekTarget, canFly, 1.5);
        if (seekPath != null && !seekPath.isEmpty()) {
            seekPathIndex = 0;
            currentWaypoint = seekPath.get(0);
            // Skip the first waypoint if it's basically where we are
            if (seekPath.size() > 1) {
                double d = Math.sqrt(seekPath.get(0).getSquaredDistance(client.player.getBlockPos()));
                if (d < 1.5) {
                    seekPathIndex = 1;
                    currentWaypoint = seekPath.get(1);
                }
            }
        } else {
            // No path found — will beeline (fallback)
            seekPath = null;
            currentWaypoint = null;
        }
        pathRecalcCooldown = 40; // don't recalc for 2 seconds
    }

    /** Recalculates path when stuck. */
    private static void recalculatePath(MinecraftClient client) {
        computePathToTarget(client);
        pathRecalcCooldown = 60; // longer cooldown after recalc
    }

    /**
     * Finds the nearest exposed face of the target head block and returns
     * the aim point on that face. Handles heads hidden behind/under blocks.
     */
    private static double[] findExposedFaceAim(MinecraftClient client, BlockPos headPos) {
        if (client.world == null) return null;

        int[][] offsets = {
            {0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}
        };
        double[][] faceCenters = {
            {0.5, 1.0, 0.5}, {0.5, 0.0, 0.5}, {1.0, 0.5, 0.5},
            {0.0, 0.5, 0.5}, {0.5, 0.5, 1.0}, {0.5, 0.5, 0.0}
        };

        double playerX = client.player.getX();
        double playerEyeY = client.player.getEyeY();
        double playerZ = client.player.getZ();

        double bestDist = Double.MAX_VALUE;
        double[] bestAim = null;

        for (int i = 0; i < 6; i++) {
            BlockPos neighbor = headPos.add(offsets[i][0], offsets[i][1], offsets[i][2]);
            if (!client.world.getBlockState(neighbor).isFullCube(client.world, neighbor)) {
                double fx = headPos.getX() + faceCenters[i][0];
                double fy = headPos.getY() + faceCenters[i][1];
                double fz = headPos.getZ() + faceCenters[i][2];
                double dist = Math.pow(fx - playerX, 2) + Math.pow(fy - playerEyeY, 2) + Math.pow(fz - playerZ, 2);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestAim = new double[]{fx, fy, fz};
                }
            }
        }
        return bestAim;
    }

    /** Picks the nearest head from the cached positions and resets path state. */
    private static void pickNextHead() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            headSeekTarget = null;
            return;
        }
        Set<BlockPos> heads = HeadOutlineRenderer.getHeadPositions();
        if (heads.isEmpty()) {
            headSeekTarget = null;
            return;
        }
        double playerX = client.player.getX();
        double playerY = client.player.getY();
        double playerZ = client.player.getZ();
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (BlockPos pos : heads) {
            double ddx = pos.getX() + 0.5 - playerX;
            double ddy = pos.getY() + 0.5 - playerY;
            double ddz = pos.getZ() + 0.5 - playerZ;
            double dist = ddx * ddx + ddy * ddy + ddz * ddz;
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = pos;
            }
        }
        headSeekTarget = nearest;
        seekPath = null;
        currentWaypoint = null;
        seekPathIndex = 0;
        headSeekState = HeadSeekState.IDLE;
    }

    /** Returns the fly double-tap tick countdown (>= 0 means actively double-tapping). */
    public static int getFlyDoubleTapTick() {
        return flyDoubleTapTick;
    }

    /** Whether the seeker is trying to jump over an obstacle. */
    public static boolean isSeekJumping() {
        return seekJumping;
    }

    /** Whether we currently have a path waypoint to follow. */
    public static BlockPos getCurrentWaypoint() {
        return currentWaypoint;
    }

    /** Stops head-seek mode and releases all forced inputs. */
    public static void stopHeadSeek() {
        if (!headSeekActive) return;
        headSeekActive = false;
        headSeekState = HeadSeekState.IDLE;
        headSeekTarget = null;
        interactTicks = 0;
        interactAttempts = 0;
        flyDoubleTapTick = -1;
        stuckTicks = 0;
        seekJumping = false;
        seekPath = null;
        currentWaypoint = null;
        seekPathIndex = 0;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options != null) {
            client.options.useKey.setPressed(false);
        }
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendMessage(Text.empty(), false);
                client.player.sendMessage(Text.empty()
                    .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal("\u26A1").formatted(Formatting.YELLOW))
                    .append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal("Head Seeker ").formatted(Formatting.WHITE))
                    .append(Text.literal("OFF").formatted(Formatting.RED, Formatting.BOLD)), false);
                client.player.sendMessage(Text.empty(), false);
            }
        });
    }
}
