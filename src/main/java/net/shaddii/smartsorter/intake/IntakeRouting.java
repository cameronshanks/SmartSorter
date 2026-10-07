package net.shaddii.smartsorter.intake;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.shaddii.smartsorter.blockentity.IntakeBlockEntity;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;

import net.shaddii.smartsorter.config.SmartSorterConfig;

/**
 * Routing helpers for StorageLogic. Every world lookup here checks
 * isChunkLoaded() first, so an intake never force-loads a chunk.
 */
public final class IntakeRouting {
    private static final Logger LOGGER = LoggerFactory.getLogger("smartsorter");

    private IntakeRouting() {
    }

    /** The linked controller, or null if unlinked, missing, or its chunk isn't loaded. */
    public static StorageControllerBlockEntity controllerOf(Level world, IntakeBlockEntity intake) {
        BlockPos controllerPos = intake.getController();
        if (controllerPos == null || !world.hasChunkAt(controllerPos)) {
            return null;
        }
        return world.getBlockEntity(controllerPos) instanceof StorageControllerBlockEntity controller ? controller : null;
    }

    /**
     * Routes {@code stack} the way the intake is configured. Returns the
     * remainder (a new stack; {@code stack} itself is not modified), or null
     * when the intake has no usable destination right now.
     */
    public static ItemStack route(Level world, IntakeBlockEntity intake, ItemStack stack) {
        if (intake.isInManagedMode()) {
            StorageControllerBlockEntity controller = controllerOf(world, intake);
            return controller == null ? null : controller.insertItem(stack).remainder();
        }
        if (intake.isInDirectMode()) {
            return insertIntoDirectOutputs(world, intake, stack);
        }
        return null;
    }

    private static ItemStack insertIntoDirectOutputs(Level world, IntakeBlockEntity intake, ItemStack stack) {
        ItemVariant variant = ItemVariant.of(stack);
        ItemStack current = stack.copy();
        List<BlockPos> outputs = intake.getOutputs();
        for (int i = 0; i < outputs.size() && !current.isEmpty(); i++) {
            BlockPos probePos = outputs.get(i);
            if (!world.hasChunkAt(probePos)) {
                continue;
            }
            if (world.getBlockEntity(probePos) instanceof OutputProbeBlockEntity probe && probe.accepts(variant)) {
                Container inv = probe.getTargetInventory();
                if (inv != null) {
                    insertInto(inv, current);
                }
            }
        }
        return current;
    }

    /**
     * Moves as much of {@code stack} into {@code inv} as fits - same-item
     * slots first, then empty ones - decrementing {@code stack}. Partial
     * inserts are normal: the caller keeps whatever is left. Compares stacks
     * in place (no toStack()); the only allocation is the new stack object
     * placed into an empty slot.
     */
    public static int insertInto(Container inv, ItemStack stack) {
        int original = stack.getCount();
        int maxStackSize = Math.min(stack.getMaxStackSize(), inv.getMaxStackSize());
        int size = inv.getContainerSize();
        boolean changed = false;

        for (int i = 0; i < size && !stack.isEmpty(); i++) {
            ItemStack slot = inv.getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, stack)) {
                int room = maxStackSize - slot.getCount();
                if (room > 0) {
                    int moved = Math.min(room, stack.getCount());
                    slot.grow(moved);
                    stack.shrink(moved);
                    changed = true;
                }
            }
        }
        for (int i = 0; i < size && !stack.isEmpty(); i++) {
            if (inv.getItem(i).isEmpty()) {
                int moved = Math.min(maxStackSize, stack.getCount());
                inv.setItem(i, stack.copyWithCount(moved));
                stack.shrink(moved);
                changed = true;
            }
        }

        if (changed) {
            inv.setChanged();
        }
        return original - stack.getCount();
    }

    /** The configured overflow chest (or probe's target chest), if set and loaded. */
    private static Container overflowInventory(Level world) {
        BlockPos target = SmartSorterConfig.stuckOverflowTarget;
        if (target == null || !world.hasChunkAt(target)) {
            return null;
        }
        if (world.getBlockEntity(target) instanceof OutputProbeBlockEntity probe) {
            return probe.getTargetInventory();
        }
        return HopperBlockEntity.getContainerAt(world, target);
    }

    /**
     * Tries every buffered entry whose retry time has come. Returns true if
     * anything moved. Entries that still can't go anywhere wait another
     * stuckRetryTicks, so they cost nothing in between.
     */
    public static boolean routeBuffered(Level world, IntakeBlockEntity intake, IntakeBuffer buffer, long now) {
        boolean moved = false;
        long retryAt = now + SmartSorterConfig.stuckRetryTicks;
        List<IntakeBuffer.Entry> entries = buffer.entries();

        for (int i = entries.size() - 1; i >= 0; i--) {
            IntakeBuffer.Entry entry = entries.get(i);
            if (entry.retryAt > now) {
                continue;
            }

            int before = entry.stack.getCount();
            ItemStack remainder = route(world, intake, entry.stack);
            if (remainder == null) {
                remainder = entry.stack;
            }
            if (!remainder.isEmpty()) {
                Container overflow = overflowInventory(world);
                if (overflow != null) {
                    remainder = remainder == entry.stack ? remainder.copy() : remainder;
                    insertInto(overflow, remainder);
                }
            }

            if (remainder.isEmpty()) {
                entries.remove(i);
                moved = true;
                continue;
            }

            if (remainder.getCount() < before) {
                moved = true;
            }
            entry.stack = remainder;
            entry.retryAt = retryAt;
            if (!entry.stuck) {
                entry.stuck = true;
                if (SmartSorterConfig.logStuckItems) {
                    BlockPos pos = intake.getBlockPos();
                    LOGGER.info("[Smart Sorter] Intake at {} {} {}: {}x {} has no destination with room; buffered, retrying every {} ticks",
                            pos.getX(), pos.getY(), pos.getZ(), remainder.getCount(), remainder.getHoverName().getString(),
                            SmartSorterConfig.stuckRetryTicks);
                }
            }
        }

        buffer.recomputeNextDue();
        return moved;
    }
}
