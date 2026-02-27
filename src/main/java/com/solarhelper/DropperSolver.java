package com.solarhelper;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Physics-based dropper solver.
 *
 * Each game tick, computes the best horizontal inputs to steer the player
 * toward the center of the water landing zone while avoiding obstacles.
 *
 * Uses a beam search: tries all 9 input combos (forward ∈ {-1,0,1} × sideways ∈ {-1,0,1})
 * simulated LOOKAHEAD ticks ahead, scores each by collision and landing accuracy.
 */
public class DropperSolver {

    // ── Minecraft player-in-air physics constants ──
    private static final double GRAVITY         = 0.08;
    private static final double VERTICAL_DRAG   = 0.98;
    private static final double HORIZONTAL_DRAG = 0.91;
    private static final double AIR_ACCEL       = 0.02; // base horizontal acceleration per tick in air

    // Ticks to simulate ahead when scoring each combo.
    // 200 ticks ≈ 10 s of freefall, covering ~400+ blocks — enough for any dropper map.
    // The loop exits early once water is hit, so performance is fine for short droppers too.
    private static final int LOOKAHEAD = 200;

    // Player AABB (feet position, centered on X/Z)
    private static final double PLAYER_HALF_W = 0.3;  // 0.6 wide total
    private static final double PLAYER_HEIGHT  = 1.8;

    // Water BFS cap — don't walk more than this many water blocks
    private static final int MAX_WATER_BFS = 200;

    // ── Data types ──────────────────────────────────────────────────────────

    /** Snapshot of player position and velocity for physics simulation. */
    public record SimState(double x, double y, double z, double vx, double vy, double vz) {}

    /**
     * Steering decision for one tick.
     * forward: -1=backward, 0=none, 1=forward (relative to yaw)
     * sideways: -1=right, 0=none, 1=left  (Minecraft convention: positive = left)
     */
    public record Steering(float yaw, int forward, int sideways) {}

    // ── Physics simulation ──────────────────────────────────────────────────

    /**
     * Simulates one tick of freefall physics.
     * forward ∈ {-1, 0, 1}, sideways ∈ {-1, 0, 1} (positive sideways = left).
     *
     * Minecraft horizontal movement in air:
     *   X += (sideways * cos(yaw) - forward * sin(yaw)) * AIR_ACCEL
     *   Z += (forward  * cos(yaw) + sideways * sin(yaw)) * AIR_ACCEL
     * Then multiply by HORIZONTAL_DRAG.
     */
    public static SimState simulateTick(SimState s, float yaw, int forward, int sideways) {
        double yawRad = Math.toRadians(yaw);
        double sinYaw = Math.sin(yawRad);
        double cosYaw = Math.cos(yawRad);

        double inputX = (sideways * cosYaw - forward * sinYaw) * AIR_ACCEL;
        double inputZ = (forward  * cosYaw + sideways * sinYaw) * AIR_ACCEL;

        double newVx = (s.vx + inputX) * HORIZONTAL_DRAG;
        double newVy = (s.vy - GRAVITY) * VERTICAL_DRAG;
        double newVz = (s.vz + inputZ) * HORIZONTAL_DRAG;

        return new SimState(s.x + newVx, s.y + newVy, s.z + newVz, newVx, newVy, newVz);
    }

    // ── World queries ───────────────────────────────────────────────────────

    /**
     * True if the player AABB at this SimState overlaps any solid non-water block.
     */
    public static boolean collidesWithSolid(ClientWorld world, SimState s) {
        int x0 = (int) Math.floor(s.x - PLAYER_HALF_W);
        int x1 = (int) Math.floor(s.x + PLAYER_HALF_W);
        int y0 = (int) Math.floor(s.y);
        int y1 = (int) Math.floor(s.y + PLAYER_HEIGHT - 0.001);
        int z0 = (int) Math.floor(s.z - PLAYER_HALF_W);
        int z1 = (int) Math.floor(s.z + PLAYER_HALF_W);

        for (int bx = x0; bx <= x1; bx++) {
            for (int by = y0; by <= y1; by++) {
                for (int bz = z0; bz <= z1; bz++) {
                    BlockPos pos = new BlockPos(bx, by, bz);
                    BlockState state = world.getBlockState(pos);
                    if (!state.getFluidState().isIn(FluidTags.WATER) && state.isSolidBlock(world, pos)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** True if the player's feet are currently inside a water block. */
    public static boolean isInWater(ClientWorld world, SimState s) {
        BlockPos feet = new BlockPos((int) Math.floor(s.x), (int) Math.floor(s.y), (int) Math.floor(s.z));
        return world.getBlockState(feet).getFluidState().isIn(FluidTags.WATER);
    }

    // ── Water target detection ──────────────────────────────────────────────

    /**
     * Finds the "easiest" water landing pool below the player.
     *
     * Collects candidate water pools by scanning downward in expanding rings,
     * BFS-floods each pool to get its centroid + size, then scores by:
     *   (horizontal distance from player) - (pool size bonus)
     * so a large pool directly below beats a tiny pool far off to the side.
     *
     * Returns [centerX, centerY, centerZ] or null if no water found.
     */
    public static double[] findWaterCenter(ClientWorld world, double px, double py, double pz) {
        int sx = (int) Math.floor(px);
        int sy = (int) Math.floor(py);
        int sz = (int) Math.floor(pz);

        // Collect one seed per column (first water found going down)
        List<BlockPos> seeds = collectSeeds(world, sx, sy, sz);
        if (seeds.isEmpty()) return null;

        // BFS each seed into its pool, score, and return the best
        Set<BlockPos> globalVisited = new HashSet<>();
        double bestScore = Double.MAX_VALUE;
        double[] bestCenter = null;

        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        for (BlockPos seed : seeds) {
            if (globalVisited.contains(seed)) continue;
            if (!world.getBlockState(seed).getFluidState().isIn(FluidTags.WATER)) continue;

            int waterY = seed.getY();
            Set<BlockPos> visited = new HashSet<>();
            Queue<BlockPos> queue = new ArrayDeque<>();
            queue.add(seed);
            visited.add(seed);

            double sumX = 0, sumZ = 0;
            int count = 0;

            while (!queue.isEmpty() && visited.size() < MAX_WATER_BFS) {
                BlockPos curr = queue.poll();
                if (!world.getBlockState(curr).getFluidState().isIn(FluidTags.WATER)) continue;
                sumX += curr.getX() + 0.5;
                sumZ += curr.getZ() + 0.5;
                count++;
                for (int[] d : dirs) {
                    BlockPos n = new BlockPos(curr.getX() + d[0], waterY, curr.getZ() + d[1]);
                    if (!visited.contains(n)) { visited.add(n); queue.add(n); }
                }
            }

            globalVisited.addAll(visited);
            if (count == 0) continue;

            double cx = sumX / count;
            double cz = sumZ / count;
            double horizDist = Math.sqrt((cx - px) * (cx - px) + (cz - pz) * (cz - pz));
            // Lower score = better. Size is weighted heavily so the main landing pool
            // (large) always beats a small decorative water block that happens to be closer.
            double score = horizDist - count * 1.5;

            if (score < bestScore) {
                bestScore = score;
                bestCenter = new double[]{cx, waterY + 0.5, cz};
            }
        }

        return bestCenter;
    }

    /**
     * Scans ALL columns within r=0..10 and collects every distinct water block found,
     * not just the first per column. This ensures decorative water above the real landing
     * pool doesn't hide it: both pools get a seed, and BFS scoring picks the best one.
     */
    private static List<BlockPos> collectSeeds(ClientWorld world, int sx, int sy, int sz) {
        List<BlockPos> seeds = new ArrayList<>();
        for (int r = 0; r <= 10; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (r > 0 && Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    boolean inWater = false;
                    for (int dy = 1; dy <= 400; dy++) {
                        BlockPos pos = new BlockPos(sx + dx, sy - dy, sz + dz);
                        boolean isWater = world.getBlockState(pos).getFluidState().isIn(FluidTags.WATER);
                        // Add a seed at the first block of each distinct water stretch
                        if (isWater && !inWater) {
                            seeds.add(pos);
                        }
                        inWater = isWater;
                    }
                }
            }
        }
        return seeds;
    }

    // ── Steering ────────────────────────────────────────────────────────────

    // How many world-space directions to try when obstacle-dodging (every 360/DODGE_DIRS degrees)
    private static final int DODGE_DIRS = 16;

    // Skip collision detection for the first N simulated ticks.
    // The player has just left their starting platform; after 1 physics tick the simulated
    // body has fallen slightly INTO that platform (the sim doesn't do collision resolution),
    // causing a guaranteed false-positive collision that triggers the dodge loop endlessly.
    // After ~8 ticks the player is ~1.5 blocks below the platform and truly clear.
    private static final int GRACE_TICKS = 8;

    /**
     * Computes the best steering for this tick.
     *
     * Hysteresis first: if the current yaw (prevTargetYaw) is still collision-free, keep it.
     * This prevents the solver from oscillating between two similarly-scored yaws.
     *
     * Then: try going straight toward water. If blocked, sweep DODGE_DIRS world-space yaw
     * directions (every 22.5°, smallest detour first) and return the best one.
     *
     * Key insight: changing the yaw and pressing forward redirects ALL horizontal thrust,
     * far more effective than strafing (which adds only ±0.02 blocks/tick sideways).
     *
     * @param prevTargetYaw the yaw the solver chose last time (Float.NaN on first call)
     */
    public static Steering computeSteering(ClientWorld world, SimState state, double[] waterCenter,
                                           float prevTargetYaw) {
        double targetX = waterCenter[0];
        double targetZ = waterCenter[2];

        double dx = targetX - state.x;
        double dz = targetZ - state.z;
        float baseYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));

        // Hysteresis: keep the previous yaw if it still clears all obstacles.
        // This kills yaw-hunting when two adjacent directions score equally well.
        if (!Float.isNaN(prevTargetYaw)) {
            if (scoreYaw(world, state, prevTargetYaw, targetX, targetZ) < 1_000.0) {
                return new Steering(prevTargetYaw, 1, 0);
            }
        }

        // Direct route toward water
        double directScore = scoreYaw(world, state, baseYaw, targetX, targetZ);
        if (directScore < 1_000.0) {
            return new Steering(baseYaw, 1, 0);
        }

        // Obstacle ahead — sweep DODGE_DIRS directions, smallest detour first
        float bestYaw = baseYaw;
        double bestScore = directScore;

        for (int i = 1; i <= DODGE_DIRS / 2; i++) {
            float step = i * (360f / DODGE_DIRS);
            for (float candidate : new float[]{baseYaw + step, baseYaw - step}) {
                double score = scoreYaw(world, state, candidate, targetX, targetZ);
                if (score < bestScore) {
                    bestScore = score;
                    bestYaw = candidate;
                }
            }
            if (bestScore < 1_000.0) break; // found a clear path, stop searching
        }

        return new Steering(bestYaw, 1, 0);
    }

    /**
     * Simulates LOOKAHEAD ticks pressing forward at the given yaw and scores the path.
     * Lower = better. Scores ≥ 1_000 mean a collision was hit.
     *
     * Collision checking is skipped for the first GRACE_TICKS ticks to avoid false
     * positives caused by the starting platform (the sim can't resolve collisions, so
     * the simulated body briefly overlaps the platform block before falling clear).
     */
    private static double scoreYaw(ClientWorld world, SimState start, float yaw,
                                   double targetX, double targetZ) {
        SimState s = start;

        for (int t = 0; t < LOOKAHEAD; t++) {
            s = simulateTick(s, yaw, 1, 0);

            if (t >= GRACE_TICKS && collidesWithSolid(world, s)) {
                return 100_000.0 + (LOOKAHEAD - t) * 500.0;
            }

            if (isInWater(world, s)) {
                double ddx = s.x - targetX;
                double ddz = s.z - targetZ;
                return Math.sqrt(ddx * ddx + ddz * ddz);
            }
        }

        double ddx = s.x - targetX;
        double ddz = s.z - targetZ;
        return 50.0 + Math.sqrt(ddx * ddx + ddz * ddz);
    }
}
