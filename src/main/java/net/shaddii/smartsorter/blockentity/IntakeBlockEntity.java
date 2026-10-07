package net.shaddii.smartsorter.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.StorageLogic;
import net.shaddii.smartsorter.chunk.ChunkKeeper;
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

    public static void tick(Level world, BlockPos pos, BlockState state, IntakeBlockEntity be) {
        if (world.isClientSide()) return;

        // Validate links periodically
        if (world.getGameTime() % VALIDATION_INTERVAL == 0L) {
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
            be.setChanged();
        } else {
            be.consecutiveSuccesses = 0;
            be.cooldown = IDLE_COOLDOWN; // Back off when idle
        }
    }

    private void validateLinks(Level world) {
        // Links in unloaded chunks are kept: looking them up would force-load the chunk.

        // Validate controller
        if (controllerPos != null && world.hasChunkAt(controllerPos)) {
            BlockEntity be = world.getBlockEntity(controllerPos);
            if (!(be instanceof net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity)) {
                controllerPos = null;
                setChanged();
            }
        }

        // Validate output probes
        outputs.removeIf(outputPos -> {
            if (!world.hasChunkAt(outputPos)) return false;
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
            setChanged();
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
            setChanged();
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
            setChanged();
            return true;
        }
        return false;
    }

    public boolean removeOutput(BlockPos probePos) {
        boolean removed = outputs.remove(probePos);
        if (removed) {
            setChanged();
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

    @Override
    protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput view) {
        super.saveAdditional(view);

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
                view.store("ssbulk_buf_" + count, ItemStack.CODEC, entry.stack);
                count++;
            }
        }
        view.putInt("ssbulk_buf_count", count);
    }

    @Override
    protected void loadAdditional(net.minecraft.world.level.storage.ValueInput view) {
        super.loadAdditional(view);

        // Controller
        controllerPos = null;
        view.getLong("controller").ifPresent(pos -> controllerPos = BlockPos.of(pos));

        // Outputs
        outputs.clear();
        int c = view.getIntOr("out_count", 0);
        for (int i = 0; i < c; i++) {
            view.getLong("o" + i).ifPresent(pos -> outputs.add(BlockPos.of(pos)));
        }

        // Buffer. Everything loaded is retried on the first pass (retry time 0).
        intakeBuffer.drain();
        // Original Smart Sorter's single buffer stack
        view.read("buffer", ItemStack.OPTIONAL_CODEC).ifPresent(stack -> intakeBuffer.add(stack, 0L));
        int count = view.getIntOr("ssbulk_buf_count", 0);
        for (int i = 0; i < count; i++) {
            view.read("ssbulk_buf_" + i, ItemStack.CODEC).ifPresent(stack -> intakeBuffer.add(stack, 0L));
        }
    }

    /** Runs before the block entity is removed, for every kind of removal. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (!(level instanceof net.minecraft.server.level.ServerLevel world)) return;

        // Scatter buffered items
        for (ItemStack stack : intakeBuffer.drain()) {
            net.minecraft.world.Containers.dropItemStack(world, pos.getX(), pos.getY(), pos.getZ(), stack);
        }
        ChunkKeeper.unregister(world, pos);

        // Unlink from controller
        if (controllerPos != null && world.getBlockEntity(controllerPos) instanceof StorageControllerBlockEntity controller) {
            controller.removeIntake(pos);
        }

        // Unlink from probes (direct mode)
        for (BlockPos probePos : new ArrayList<>(outputs)) {
            if (world.getBlockEntity(probePos) instanceof OutputProbeBlockEntity probe) {
                probe.removeLinkedBlock(pos);
            }
        }
    }
}
