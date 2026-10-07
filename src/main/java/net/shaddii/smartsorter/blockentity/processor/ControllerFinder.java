package net.shaddii.smartsorter.blockentity.processor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;

import java.util.*;

/**
 * Finds storage controllers through redstone network using staged BFS.
 * Optimized with distance-based stages and manhattan distance checks.
 */
public class ControllerFinder {
    private static final int[] SEARCH_STAGES = {8, 16, 32, 64, 128};

    // Cache for recent searches (position -> controller position)
    private static final Map<BlockPos, CachedResult> cache = new HashMap<>();
    private static final long CACHE_DURATION = 200L;

    private static class CachedResult {
        final BlockPos controllerPos;
        final long timestamp;

        CachedResult(BlockPos pos, long time) {
            this.controllerPos = pos;
            this.timestamp = time;
        }

        boolean isValid(long currentTime) {
            return currentTime - timestamp < CACHE_DURATION;
        }
    }

    /**
     * Finds a storage controller connected via redstone network.
     * Uses staged search (8 → 16 → 32 → 64 → 128 blocks) for optimization.
     */
    public static BlockPos findController(ServerLevel world, BlockPos start) {
        // Check cache first
        CachedResult cached = cache.get(start);
        if (cached != null && cached.isValid(world.getGameTime())) {
            // Validate cached result
            if (cached.controllerPos != null) {
                BlockEntity be = world.getBlockEntity(cached.controllerPos);
                if (be instanceof StorageControllerBlockEntity) {
                    return cached.controllerPos;
                }
            }
            // Cache says no controller
            return null;
        }

        // Staged search
        BlockPos result = null;
        for (int radius : SEARCH_STAGES) {
            result = searchRadius(world, start, radius);
            if (result != null) break;
        }

        // Cache result
        cache.put(start, new CachedResult(result, world.getGameTime()));

        // Clean old cache entries periodically
        if (cache.size() > 100) {
            cleanCache(world.getGameTime());
        }

        return result;
    }

    /**
     * Searches for controller within given radius using BFS.
     */
    private static BlockPos searchRadius(ServerLevel world, BlockPos start, int radius) {
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new LinkedList<>();

        queue.add(start);
        for (Direction dir : Direction.values()) {
            queue.add(start.relative(dir));
        }

        int blocksChecked = 0;
        int maxBlocks = radius * radius * 4;

        BlockPos closestController = null;
        double closestDistance = Double.MAX_VALUE;

        while (!queue.isEmpty() && blocksChecked < maxBlocks) {
            BlockPos current = queue.poll();

            if (!visited.add(current)) continue;

            // Check Manhattan distance
            int manhattanDist = getManhattanDistance(current, start);
            if (manhattanDist > radius) continue;

            blocksChecked++;

            // Check if this is a controller
            BlockEntity be = world.getBlockEntity(current);
            if (be instanceof StorageControllerBlockEntity) {
                double dist = start.distSqr(current);
                if (dist < closestDistance) {
                    closestDistance = dist;
                    closestController = current;
                }
                continue;
            }

            // Expand through redstone components
            BlockState state = world.getBlockState(current);
            if (isRedstoneComponent(state)) {
                expandSearch(queue, visited, current, state);
            }
        }

        return closestController;
    }

    /**
     * Adds adjacent positions to search queue based on redstone type.
     */
    private static void expandSearch(Queue<BlockPos> queue, Set<BlockPos> visited,
                                     BlockPos current, BlockState state) {
        // Repeaters have directional priority
        if (state.is(Blocks.REPEATER)) {
            Direction facing = state.getValue(net.minecraft.world.level.block.RepeaterBlock.FACING);
            queue.add(current.relative(facing));
            queue.add(current.relative(facing.getOpposite()));

            // Check sides for T-junctions
            for (Direction side : Direction.Plane.HORIZONTAL) {
                if (side != facing && side != facing.getOpposite()) {
                    BlockPos sidePos = current.relative(side);
                    if (!visited.contains(sidePos)) {
                        queue.add(sidePos);
                    }
                }
            }
        } else {
            // Check all adjacent blocks
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = current.relative(dir);
                if (!visited.contains(neighbor)) {
                    queue.add(neighbor);
                }
            }

            // Check diagonals for redstone wire
            if (state.is(Blocks.REDSTONE_WIRE)) {
                for (Direction horizontal : Direction.Plane.HORIZONTAL) {
                    for (Direction vertical : new Direction[]{Direction.UP, Direction.DOWN}) {
                        BlockPos diagonal = current.relative(horizontal).relative(vertical);
                        if (!visited.contains(diagonal)) {
                            queue.add(diagonal);
                        }
                    }
                }
            }
        }
    }

    /**
     * Checks if block is a redstone component.
     */
    private static boolean isRedstoneComponent(BlockState state) {
        return state.is(Blocks.REDSTONE_WIRE) ||
                state.is(Blocks.LEVER) ||
                state.is(Blocks.REDSTONE_TORCH) ||
                state.is(Blocks.REDSTONE_WALL_TORCH) ||
                state.is(Blocks.REDSTONE_BLOCK) ||
                state.is(Blocks.REPEATER) ||
                state.is(Blocks.COMPARATOR) ||
                state.isSignalSource();
    }

    private static int getManhattanDistance(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) +
                Math.abs(a.getY() - b.getY()) +
                Math.abs(a.getZ() - b.getZ());
    }

    private static void cleanCache(long currentTime) {
        cache.entrySet().removeIf(entry ->
                !entry.getValue().isValid(currentTime)
        );
    }

    /**
     * Clears the entire cache (call on world unload).
     */
    public static void clearCache() {
        cache.clear();
    }
}