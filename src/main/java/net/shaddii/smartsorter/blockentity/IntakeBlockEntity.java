package net.shaddii.smartsorter.blockentity;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.StorageLogic;
import net.shaddii.smartsorter.intake.IntakeBuffer;

import java.util.ArrayList;
import java.util.List;

public class IntakeBlockEntity extends BlockEntity {
    // ========================================
    // CONSTANTS
    // ========================================

    private static final int ITEMS_PER_TICK = 16;
    private static final int ACTIVE_COOLDOWN = 0;
    private static final int IDLE_COOLDOWN = 8;
    private static final long VALIDATION_INTERVAL = 100L;

    // ========================================
    // FIELDS
    // ========================================

    // Direct mode (links to output probes)
    private final List<BlockPos> outputs = new ArrayList<>();

    // Managed mode (links to storage controller)
    private BlockPos controllerPos = null;

    // State
    private final IntakeBuffer intakeBuffer = new IntakeBuffer();
    private int cooldown = 0;

    // Performance tracking
    private int consecutiveSuccesses = 0;

    // ========================================
    // CONSTRUCTOR
    // ========================================

    public IntakeBlockEntity(BlockPos pos, BlockState state) {
        super(SmartSorter.INTAKE_BE_TYPE, pos, state);
    }

    // ========================================
    // TICK LOGIC
    // ========================================

    public static void tick(World world, BlockPos pos, BlockState state, IntakeBlockEntity be) {
        if (world.isClient()) return;

        // Validate links periodically
        if (world.getTime() % VALIDATION_INTERVAL == 0L) {
            be.validateLinks(world);
        }

        if (be.cooldown > 0) {
            be.cooldown--;
            return;
        }

        // OPTIMIZATION: Process multiple items per tick
        int movedThisTick = 0;
        boolean anyMoved = false;

        for (int i = 0; i < ITEMS_PER_TICK; i++) {
            boolean moved = StorageLogic.pullAndRoute(be);

            if (moved) {
                movedThisTick++;
                anyMoved = true;
            } else {
                // Stop early if we couldn't move anything
                break;
            }
        }

        // ADAPTIVE COOLDOWN: No cooldown when items are flowing
        if (anyMoved) {
            be.consecutiveSuccesses++;
            be.cooldown = ACTIVE_COOLDOWN; // Keep processing immediately
            be.markDirty();
        } else {
            be.consecutiveSuccesses = 0;
            be.cooldown = IDLE_COOLDOWN; // Back off when idle
        }
    }

    private void validateLinks(World world) {
        // Links in unloaded chunks are kept: looking them up would force-load the chunk.

        // Validate controller
        if (controllerPos != null && world.isChunkLoaded(controllerPos)) {
            BlockEntity be = world.getBlockEntity(controllerPos);
            if (!(be instanceof net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity)) {
                controllerPos = null;
                markDirty();
            }
        }

        // Validate output probes
        outputs.removeIf(outputPos -> {
            if (!world.isChunkLoaded(outputPos)) return false;
            BlockEntity be = world.getBlockEntity(outputPos);
            return !(be instanceof net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity);
        });
    }

    // ========================================
    // MANAGED MODE
    // ========================================

    public boolean setController(BlockPos pos) {
        if (controllerPos == null || !controllerPos.equals(pos)) {
            controllerPos = pos;
            outputs.clear();
            consecutiveSuccesses = 0; // Reset performance tracking
            markDirty();
            return true;
        }
        return false;
    }

    public BlockPos getController() {
        return controllerPos;
    }

    public boolean clearController() {
        if (controllerPos != null) {
            controllerPos = null;
            consecutiveSuccesses = 0;
            markDirty();
            return true;
        }
        return false;
    }

    // ========================================
    // DIRECT MODE
    // ========================================

    public boolean addOutput(BlockPos probePos) {
        if (!outputs.contains(probePos)) {
            outputs.add(probePos);
            controllerPos = null;
            consecutiveSuccesses = 0;
            markDirty();
            return true;
        }
        return false;
    }

    public boolean removeOutput(BlockPos probePos) {
        boolean removed = outputs.remove(probePos);
        if (removed) {
            markDirty();
        }
        return removed;
    }

    public List<BlockPos> getOutputs() {
        return outputs;
    }

    // ========================================
    // MODE DETECTION
    // ========================================

    public boolean isInManagedMode() {
        return controllerPos != null;
    }

    public boolean isInDirectMode() {
        return !outputs.isEmpty();
    }

    // ========================================
    // BUFFER MANAGEMENT
    // ========================================

    /** Stacks waiting for a destination (see StorageLogic / IntakeBuffer). */
    public IntakeBuffer getIntakeBuffer() {
        return intakeBuffer;
    }

    // ========================================
    // NBT SERIALIZATION
    // ========================================

    //? if >=1.21.8 {
    @Override
    protected void writeData(net.minecraft.storage.WriteView view) {
        super.writeData(view);

        // Controller
        if (controllerPos != null) {
            view.putLong("controller", controllerPos.asLong());
        }

        // Direct outputs
        view.putInt("out_count", outputs.size());
        for (int i = 0; i < outputs.size(); i++) {
            view.putLong("o" + i, outputs.get(i).asLong());
        }

        // Buffer ("ssbulk_buf_*" keys, as written by the Bulk Edit add-on
        // this fork grew out of, so its worlds load unchanged)
        int count = 0;
        for (IntakeBuffer.Entry entry : intakeBuffer.entries()) {
            if (!entry.stack.isEmpty()) {
                view.put("ssbulk_buf_" + count, ItemStack.CODEC, entry.stack);
                count++;
            }
        }
        view.putInt("ssbulk_buf_count", count);
    }

    @Override
    protected void readData(net.minecraft.storage.ReadView view) {
        super.readData(view);

        // Controller
        controllerPos = null;
        view.getOptionalLong("controller").ifPresent(pos -> controllerPos = BlockPos.fromLong(pos));

        // Outputs
        outputs.clear();
        int c = view.getInt("out_count", 0);
        for (int i = 0; i < c; i++) {
            view.getOptionalLong("o" + i).ifPresent(pos -> outputs.add(BlockPos.fromLong(pos)));
        }

        // Buffer. Everything loaded is retried on the first pass (retry time 0).
        intakeBuffer.drain();
        // Original Smart Sorter's single buffer stack
        view.read("buffer", ItemStack.OPTIONAL_CODEC).ifPresent(stack -> intakeBuffer.add(stack, 0L));
        int count = view.getInt("ssbulk_buf_count", 0);
        for (int i = 0; i < count; i++) {
            view.read("ssbulk_buf_" + i, ItemStack.CODEC).ifPresent(stack -> intakeBuffer.add(stack, 0L));
        }
    }
    //?} else {
    
        /*@Override
        protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
            super.writeNbt(nbt, registryLookup);

            // Controller
            if (controllerPos != null) {
                nbt.putLong("controller", controllerPos.asLong());
            }

            // Direct outputs
            nbt.putInt("out_count", outputs.size());
            for (int i = 0; i < outputs.size(); i++) {
                nbt.putLong("o" + i, outputs.get(i).asLong());
            }

            // Buffer
            if (!buffer.isEmpty()) {
                nbt.put("buffer", ItemStack.OPTIONAL_CODEC.encodeStart(registryLookup.getOps(net.minecraft.nbt.NbtOps.INSTANCE), buffer)
                        .getOrThrow());
            }
        }

        @Override
        protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
            super.readNbt(nbt, registryLookup);

            // Controller
            controllerPos = null;
            if (nbt.contains("controller")) {
                controllerPos = BlockPos.fromLong(nbt.getLong("controller"));
            }

            // Outputs
            outputs.clear();
            int c = nbt.getInt("out_count");
            for (int i = 0; i < c; i++) {
                if (nbt.contains("o" + i)) {
                    outputs.add(BlockPos.fromLong(nbt.getLong("o" + i)));
                }
            }

            // Buffer
            if (nbt.contains("buffer")) {
                buffer = ItemStack.OPTIONAL_CODEC.parse(registryLookup.getOps(net.minecraft.nbt.NbtOps.INSTANCE), nbt.get("buffer"))
                        .result()
                        .orElse(ItemStack.EMPTY);
            } else {
                buffer = ItemStack.EMPTY;
            }
        }
        
        *///?}
}