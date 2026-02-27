package com.solarhelper;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Simple A* pathfinder for navigating between blocks in a Minecraft world.
 * Supports walking (step-up 1 block, drop up to 3) and flying (any air block).
 */
public class PathFinder {

    private static final int MAX_ITERATIONS = 3000;
    private static final int MAX_DROP = 3;

    private record Node(BlockPos pos, Node parent, double g, double h) implements Comparable<Node> {
        double f() { return g + h; }
        @Override
        public int compareTo(Node other) { return Double.compare(this.f(), other.f()); }
    }

    /**
     * Finds a walkable path from start to within reach of goal.
     * Returns a list of BlockPos waypoints (feet positions), or null if no path found.
     * The path stops when within reachDist blocks of the goal.
     */
    public static List<BlockPos> findPath(ClientWorld world, BlockPos start, BlockPos goal, boolean canFly, double reachDist) {
        if (world == null) return null;

        // Snap start to a valid standing position
        start = snapToGround(world, start, canFly);
        if (start == null) return null;

        PriorityQueue<Node> open = new PriorityQueue<>();
        Map<BlockPos, Double> bestG = new HashMap<>();

        double startH = heuristic(start, goal);
        open.add(new Node(start, null, 0, startH));
        bestG.put(start, 0.0);

        int iterations = 0;

        while (!open.isEmpty() && iterations++ < MAX_ITERATIONS) {
            Node current = open.poll();

            // Close enough to interact with the head
            double distToGoal = Math.sqrt(current.pos.getSquaredDistance(goal));
            if (distToGoal <= reachDist) {
                return reconstructPath(current);
            }

            // Skip if we already found a better path to this node
            Double existingG = bestG.get(current.pos);
            if (existingG != null && current.g > existingG) continue;

            for (BlockPos neighbor : getNeighbors(world, current.pos, canFly)) {
                double moveCost = current.pos.getSquaredDistance(neighbor) < 2.1
                    ? 1.0   // flat or step-up
                    : 1.414; // diagonal-ish (step-up counts a bit more)

                // Penalize going up/down to prefer flat paths
                int yDiff = Math.abs(neighbor.getY() - current.pos.getY());
                if (yDiff > 0) moveCost += yDiff * 0.5;

                double newG = current.g + moveCost;
                Double neighborBestG = bestG.get(neighbor);
                if (neighborBestG == null || newG < neighborBestG) {
                    bestG.put(neighbor, newG);
                    double h = heuristic(neighbor, goal);
                    open.add(new Node(neighbor, current, newG, h));
                }
            }
        }

        return null; // No path found
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        // 3D Euclidean distance
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static List<BlockPos> reconstructPath(Node node) {
        List<BlockPos> path = new ArrayList<>();
        while (node != null) {
            path.add(node.pos);
            node = node.parent;
        }
        Collections.reverse(path);
        // Simplify: remove waypoints that are in a straight line
        return simplifyPath(path);
    }

    /**
     * Removes intermediate waypoints that are on the same straight line,
     * keeping only the turns. This makes movement smoother.
     */
    private static List<BlockPos> simplifyPath(List<BlockPos> path) {
        if (path.size() <= 2) return path;
        List<BlockPos> simplified = new ArrayList<>();
        simplified.add(path.get(0));

        for (int i = 1; i < path.size() - 1; i++) {
            BlockPos prev = path.get(i - 1);
            BlockPos curr = path.get(i);
            BlockPos next = path.get(i + 1);

            // If direction changes, keep this waypoint
            int dx1 = curr.getX() - prev.getX();
            int dy1 = curr.getY() - prev.getY();
            int dz1 = curr.getZ() - prev.getZ();
            int dx2 = next.getX() - curr.getX();
            int dy2 = next.getY() - curr.getY();
            int dz2 = next.getZ() - curr.getZ();

            if (dx1 != dx2 || dy1 != dy2 || dz1 != dz2) {
                simplified.add(curr);
            }
        }
        simplified.add(path.get(path.size() - 1));
        return simplified;
    }

    /**
     * Gets all valid neighboring positions a player can move to from pos.
     */
    private static List<BlockPos> getNeighbors(ClientWorld world, BlockPos pos, boolean canFly) {
        List<BlockPos> neighbors = new ArrayList<>();

        // 4 cardinal directions
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        for (int[] dir : dirs) {
            int nx = pos.getX() + dir[0];
            int nz = pos.getZ() + dir[1];

            // Same level
            BlockPos flat = new BlockPos(nx, pos.getY(), nz);
            if (isStandable(world, flat) && hasHeadroom(world, pos, flat)) {
                neighbors.add(flat);
            }

            // Step up 1 block
            BlockPos up = new BlockPos(nx, pos.getY() + 1, nz);
            if (isStandable(world, up) && hasHeadroomForStepUp(world, pos, up)) {
                neighbors.add(up);
            }

            // Drop down (1-3 blocks)
            for (int drop = 1; drop <= MAX_DROP; drop++) {
                BlockPos down = new BlockPos(nx, pos.getY() - drop, nz);
                if (isStandable(world, down) && hasHeadroom(world, pos, new BlockPos(nx, pos.getY(), nz))) {
                    neighbors.add(down);
                    break; // Only use the first valid drop level
                }
            }
        }

        // Flying: also allow straight up and down
        if (canFly) {
            BlockPos up = pos.up();
            if (isPassable(world, up) && isPassable(world, up.up())) {
                neighbors.add(up);
            }
            BlockPos down = pos.down();
            if (isPassable(world, down) && isPassable(world, down.up())) {
                neighbors.add(down);
            }
        }

        return neighbors;
    }

    /**
     * A position is standable if the block below is solid and feet+head are passable.
     * For flying, any position with 2 air blocks is fine.
     */
    private static boolean isStandable(ClientWorld world, BlockPos feetPos) {
        // Feet and head must be passable
        if (!isPassable(world, feetPos)) return false;
        if (!isPassable(world, feetPos.up())) return false;
        // Block below must be solid (something to stand on)
        BlockState below = world.getBlockState(feetPos.down());
        return below.isSolidBlock(world, feetPos.down());
    }

    /**
     * Check that the player has 2 blocks of headroom when moving from src to dst at the same level.
     */
    private static boolean hasHeadroom(ClientWorld world, BlockPos src, BlockPos dst) {
        return isPassable(world, dst) && isPassable(world, dst.up());
    }

    /**
     * For stepping up, need headroom at the higher level AND the block above current head.
     */
    private static boolean hasHeadroomForStepUp(ClientWorld world, BlockPos src, BlockPos dst) {
        // Need 2 blocks clear at destination
        if (!isPassable(world, dst) || !isPassable(world, dst.up())) return false;
        // Also need clearance above current head to "jump" up
        return isPassable(world, src.up().up());
    }

    private static boolean isPassable(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        // Not a full solid block = passable (air, flowers, slabs in some cases, etc.)
        return !state.isSolidBlock(world, pos);
    }

    /**
     * Snaps a position to the nearest valid standing block below it.
     * Used to find a valid start position.
     */
    private static BlockPos snapToGround(ClientWorld world, BlockPos pos, boolean canFly) {
        // Try current position first
        if (isStandable(world, pos)) return pos;
        // If flying, just need passable space
        if (canFly && isPassable(world, pos) && isPassable(world, pos.up())) return pos;
        // Search down
        for (int dy = 0; dy <= 5; dy++) {
            BlockPos check = pos.down(dy);
            if (isStandable(world, check)) return check;
        }
        // Search up
        for (int dy = 1; dy <= 3; dy++) {
            BlockPos check = pos.up(dy);
            if (isStandable(world, check)) return check;
        }
        return pos; // fallback to original
    }
}
