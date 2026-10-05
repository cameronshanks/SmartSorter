package net.shaddii.smartsorter;

import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import net.shaddii.smartsorter.block.IntakeBlock;
import net.shaddii.smartsorter.blockentity.IntakeBlockEntity;
import net.shaddii.smartsorter.config.SmartSorterConfig;
import net.shaddii.smartsorter.intake.IntakeBuffer;
import net.shaddii.smartsorter.intake.IntakeRouting;

import java.util.Objects;

/**
 * Moves items from the inventory an intake faces into the storage network.
 *
 * An item with no free chest never blocks the intake:
 *  - partial-insert remainders go into the intake's multi-slot IntakeBuffer,
 *    each stack with its own retry time, and every due stack is retried on
 *    each pass (a configured overflow chest gets them if set);
 *  - an item that couldn't be routed gets a cooldown (stuckRetryTicks) and is
 *    skipped with one map lookup until then, so the intake moves on to the
 *    next item instead of retrying the stuck one every pass.
 */
public final class StorageLogic {

    private StorageLogic() {}

    /** Maximum number of items an intake can pull per operation. */
    private static final int MAX_PULL_PER_OP = 8;

    /**
     * One intake operation: retry due buffered stacks, then pull the next
     * source item that isn't on cooldown (up to MAX_PULL_PER_OP, in a
     * transaction committed only when something was inserted).
     *
     * @return true if anything moved (IntakeBlockEntity.tick keeps calling
     *         while this returns true, then idles).
     */
    public static boolean pullAndRoute(IntakeBlockEntity intake) {
        if (intake == null || intake.getWorld() == null || intake.getWorld().isClient()) {
            return false;
        }

        World world = intake.getWorld();
        IntakeBuffer buffer = intake.getIntakeBuffer();
        long now = world.getTime();

        boolean moved = buffer.anyDue(now) && IntakeRouting.routeBuffered(world, intake, buffer, now);
        return pullFromSource(world, intake, buffer, now) || moved;
    }

    private static boolean pullFromSource(World world, IntakeBlockEntity intake, IntakeBuffer buffer, long now) {
        if (!intake.isInManagedMode() && !intake.isInDirectMode()) {
            return false;
        }
        if (intake.isInManagedMode() && IntakeRouting.controllerOf(world, intake) == null) {
            return false;
        }

        Direction facing = intake.getCachedState().get(IntakeBlock.FACING);
        BlockPos sourcePos = intake.getPos().offset(facing);
        if (!world.isChunkLoaded(sourcePos)) {
            return false;
        }
        Storage<ItemVariant> fromStorage = locateItemStorage(world, sourcePos, facing.getOpposite());
        if (fromStorage == null) {
            return false;
        }

        long retryAt = now + SmartSorterConfig.stuckRetryTicks;
        for (StorageView<ItemVariant> view : fromStorage) {
            if (view.isResourceBlank() || view.getAmount() == 0) continue;

            ItemVariant variant = view.getResource();
            if (buffer.isCoolingDown(variant, now)) continue;

            int maxAmount = (int) Math.min(MAX_PULL_PER_OP, view.getAmount());
            try (Transaction tx = Transaction.openOuter()) {
                long extracted = view.extract(variant, maxAmount, tx);
                if (extracted == 0) continue; // closing the transaction aborts it

                ItemStack remainder = IntakeRouting.route(world, intake, variant.toStack((int) extracted));
                long inserted = remainder == null ? 0 : extracted - remainder.getCount();
                if (inserted <= 0) {
                    // No destination right now: leave it in the source and skip it for a while.
                    buffer.coolDown(variant, retryAt);
                    continue;
                }

                if (!remainder.isEmpty()) {
                    // The chests filled up mid-insert, so this item has no more room anywhere.
                    buffer.coolDown(variant, retryAt);
                    if (!buffer.hasSlotFor(remainder)) {
                        // Buffer full: put the rest back into the source in the same transaction.
                        long returned = fromStorage.insert(variant, remainder.getCount(), tx);
                        remainder.decrement((int) returned);
                    }
                    buffer.add(remainder, retryAt); // never drops items
                }

                tx.commit();
                return true;
            }
        }
        return false;
    }

    private static Storage<ItemVariant> locateItemStorage(World world, BlockPos pos, Direction searchSide) {
        Objects.requireNonNull(world);
        Objects.requireNonNull(pos);

        Storage<ItemVariant> found = ItemStorage.SIDED.find(world, pos, searchSide);
        if (found == null) found = ItemStorage.SIDED.find(world, pos, null);

        BlockEntity be = world.getBlockEntity(pos);
        if (found == null && be instanceof Inventory inv) {
            found = InventoryStorage.of(inv, null);
        }

        return found;
    }
}
