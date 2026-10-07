package net.shaddii.smartsorter.blockentity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.block.ProcessProbeBlock;
import net.shaddii.smartsorter.blockentity.processor.*;
import net.shaddii.smartsorter.util.*;

/**
 * Process Probe Block Entity - Manages automated furnace processing.
 * REFACTORED: ~250 lines (down from 800+)
 */
public class ProcessProbeBlockEntity extends BlockEntity implements ControllerLinkable {

    // ========================================
    // CONSTANTS
    // ========================================

    private static final int TICK_INTERVAL = 10;
    private static final long CACHE_CLEAN_INTERVAL = 200L;

    // ========================================
    // SERVICE COMPONENTS
    // ========================================

    private final RecipeValidator recipeValidator;
    private final SmeltingProcessor smeltingProcessor;
    private final ExperienceCollector experienceCollector;

    // ========================================
    // FIELDS
    // ========================================

    // Core properties
    private BlockPos controllerPos;
    private Direction facing;
    private boolean enabled = false;
    private String machineType = "None";
    private BlockPos targetMachinePos;

    // State tracking
    private boolean wasRedstonePowered = false;
    private boolean isLinked = false;
    private int tickCounter = 0;
    private boolean needsInitialLink = false;

    // Configuration
    private ProcessProbeConfig config;
    private boolean hasBeenConfigured = false;

    // ========================================
    // CONSTRUCTOR
    // ========================================

    public ProcessProbeBlockEntity(BlockPos pos, BlockState state) {
        super(SmartSorter.PROCESS_PROBE_BE_TYPE, pos, state);

        try {
            this.facing = state.getValue(ProcessProbeBlock.FACING);
        } catch (Exception e) {
            this.facing = Direction.NORTH;
        }

        this.config = new ProcessProbeConfig(pos, "Unknown");

        // Initialize services
        this.experienceCollector = new ExperienceCollector();
        this.recipeValidator = new RecipeValidator();
        this.smeltingProcessor = new SmeltingProcessor(recipeValidator, experienceCollector);
    }

    // ========================================
    // TICK LOGIC
    // ========================================

    public static void tick(Level world, BlockPos pos, BlockState state, ProcessProbeBlockEntity be) {
        if (world == null || world.isClientSide()) return;

        be.tickCounter++;

        if (be.needsInitialLink) {
            boolean powered = isReceivingRedstone(world, pos);
            if (powered) {
                be.attemptLink(world, state);
            }
            be.needsInitialLink = false;
            be.wasRedstonePowered = powered;
        }

        // Check if probe is enabled by controller
        if (!be.checkControllerEnabled(world)) return;

        // Check redstone state changes
        boolean powered = isReceivingRedstone(world, pos);
        if (powered != be.wasRedstonePowered) {
            if (powered) {
                be.attemptLink(world, state);
            } else {
                be.disconnect(world);
            }
            be.wasRedstonePowered = powered;
        }

        be.setEnabled(powered);

        // Process every TICK_INTERVAL
        if (be.tickCounter >= TICK_INTERVAL) {
            be.tickCounter = 0;

            if (be.enabled && be.isLinked && world instanceof ServerLevel serverWorld) {
                be.processTick(serverWorld);
            }
        }

        // Clean caches periodically
        if (be.tickCounter % CACHE_CLEAN_INTERVAL == 0) {
            be.cleanCaches();
        }
    }

    private boolean checkControllerEnabled(Level world) {
        if (controllerPos == null) return true;

        BlockEntity controllerBE = world.getBlockEntity(controllerPos);
        if (controllerBE instanceof StorageControllerBlockEntity controller) {
            ProcessProbeConfig config = controller.getProbeConfig(worldPosition);
            return config == null || config.enabled;
        }

        return true;
    }

    private void processTick(ServerLevel world) {
        if (controllerPos == null) return;

        BlockEntity controllerBE = world.getBlockEntity(controllerPos);
        if (!(controllerBE instanceof StorageControllerBlockEntity controller)) return;

        ProcessProbeConfig config = controller.getProbeConfig(worldPosition);
        if (config == null || !config.enabled) return;

        // Update facing
        try {
            BlockState state = world.getBlockState(worldPosition);
            facing = state.getValue(ProcessProbeBlock.FACING);
        } catch (Exception e) {
            facing = Direction.NORTH;
        }

        // Get target machine
        BlockPos machinePos = worldPosition.relative(facing);
        BlockEntity blockEntity = world.getBlockEntity(machinePos);

        if (blockEntity instanceof AbstractFurnaceBlockEntity smeltingMachine) {
            smeltingProcessor.processSmeltingMachine(world, worldPosition, smeltingMachine, controller, config);

            // Update config with processed count
            int processedCount = smeltingProcessor.getItemsProcessed();
            if (config.itemsProcessed != processedCount) {
                config.itemsProcessed = processedCount;
                controller.updateProbeConfig(config);
            }
        }
    }

    // ========================================
    // LINKING LOGIC
    // ========================================

    private void attemptLink(Level world, BlockState state) {
        if (!(world instanceof ServerLevel serverWorld)) return;

        // Update facing
        try {
            facing = state.getValue(ProcessProbeBlock.FACING);
        } catch (Exception e) {
            facing = Direction.NORTH;
        }

        // Check for valid machine
        BlockPos machinePos = worldPosition.relative(facing);
        BlockEntity machineEntity = world.getBlockEntity(machinePos);
        BlockState machineState = world.getBlockState(machinePos);

        if (!isValidProcessingMachine(machineEntity, machineState)) {
            notifyPlayers(serverWorld, "No valid processing machine found", false);
            isLinked = false;
            return;
        }

        updateMachineType(machineState);
        targetMachinePos = machinePos;

        // Use the controller picked with the Linking Tool; otherwise find one
        // through the redstone network
        BlockPos foundController = controllerPos != null
                && world.getBlockEntity(controllerPos) instanceof StorageControllerBlockEntity
                ? controllerPos
                : ControllerFinder.findController(serverWorld, worldPosition);

        if (foundController != null) {
            BlockEntity be = world.getBlockEntity(foundController);
            if (be instanceof StorageControllerBlockEntity controller) {
                boolean success = controller.registerProcessProbe(worldPosition, machineType);

                if (success) {
                    this.controllerPos = foundController;
                    isLinked = true;
                    setChanged();

                    String message = String.format("Linked to %s - Link Active", machineType);
                    notifyPlayers(serverWorld, message, true);
                } else {
                    isLinked = false;
                    notifyPlayers(serverWorld, "Controller rejected link", false);
                }
            }
        } else {
            isLinked = false;
            notifyPlayers(serverWorld, "No Storage Controller found in redstone network", false);
        }
    }

    private void disconnect(Level world) {
        if (!(world instanceof ServerLevel serverWorld)) return;

        if (isLinked && controllerPos != null) {
            BlockEntity be = world.getBlockEntity(controllerPos);
            if (be instanceof StorageControllerBlockEntity controller) {
                controller.unregisterProcessProbe(worldPosition);
            }

            isLinked = false;
            notifyPlayers(serverWorld, "Link Inactive", false);
        }

        enabled = false;
    }

    // ========================================
    // UTILITY METHODS
    // ========================================

    private static boolean isReceivingRedstone(Level world, BlockPos pos) {
        if (world.hasNeighborSignal(pos)) return true;

        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.relative(dir);
            BlockState neighborState = world.getBlockState(neighborPos);

            if (neighborState.is(Blocks.REDSTONE_WIRE)) {
                int power = neighborState.getValue(net.minecraft.world.level.block.RedStoneWireBlock.POWER);
                if (power > 0) return true;
            }

            if (neighborState.is(Blocks.LEVER) &&
                    neighborState.getValue(net.minecraft.world.level.block.LeverBlock.POWERED)) {
                return true;
            }

            if (world.getSignal(neighborPos, dir.getOpposite()) > 0) {
                return true;
            }
        }

        return false;
    }

    private boolean isValidProcessingMachine(BlockEntity entity, BlockState state) {
        if (!(entity instanceof AbstractFurnaceBlockEntity)) return false;

        return state.is(Blocks.FURNACE) ||
                state.is(Blocks.BLAST_FURNACE) ||
                state.is(Blocks.SMOKER);
    }

    private void updateMachineType(BlockState state) {
        if (state.is(Blocks.FURNACE)) {
            machineType = "Furnace";
        } else if (state.is(Blocks.BLAST_FURNACE)) {
            machineType = "Blast Furnace";
        } else if (state.is(Blocks.SMOKER)) {
            machineType = "Smoker";
        } else {
            machineType = "Unknown";
        }

        if (config != null) {
            config.machineType = machineType;
        }
    }

    private void notifyPlayers(ServerLevel world, String message, boolean success) {
        Component text = Component.literal(message).withStyle(success ? ChatFormatting.GREEN : ChatFormatting.YELLOW);

        for (net.minecraft.server.level.ServerPlayer player : world.players()) {
            if (player.distanceToSqr(Vec3.atCenterOf(worldPosition)) < 256) {
                player.sendOverlayMessage(text);
            }
        }
    }

    private void cleanCaches() {
        recipeValidator.cleanCache();
        // Experience collector cache is small enough to not need frequent cleaning
    }

    // ========================================
    // GETTERS & SETTERS
    // ========================================

    @Override
    public void setController(BlockPos controllerPos) {
        this.controllerPos = controllerPos;
        setChanged();
    }

    @Override
    public BlockPos getController() {
        return controllerPos;
    }

    public ProcessProbeConfig getConfig() {
        if (config == null) {
            config = new ProcessProbeConfig(this.worldPosition, this.machineType);
        }
        config.position = this.worldPosition;
        config.machineType = this.machineType;
        config.itemsProcessed = smeltingProcessor.getItemsProcessed();
        return config;
    }

    public void setConfig(ProcessProbeConfig newConfig) {
        this.config = newConfig.copy();
        this.config.position = this.worldPosition;

        smeltingProcessor.setItemsProcessed(newConfig.itemsProcessed);
        this.hasBeenConfigured = true;

        setChanged();
    }

    public boolean addLinkedBlock(BlockPos controllerPos) {
        if (this.controllerPos == null || !this.controllerPos.equals(controllerPos)) {
            if (level != null) {
                disconnect(level); // leave the previous controller, if any
            }
            setController(controllerPos);
            // Already powered: link now instead of waiting for the next redstone change
            if (level != null && isReceivingRedstone(level, worldPosition)) {
                attemptLink(level, getBlockState());
                wasRedstonePowered = true;
            }
            return true;
        }
        return false;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            setChanged();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getProcessedCount() {
        return smeltingProcessor.getItemsProcessed();
    }

    public String getMachineType() {
        return machineType;
    }

    public String getStatusText() {
        if (!enabled) return "§cDisabled";
        if (!isLinked) return "§eNot Linked";
        if (targetMachinePos == null) return "§eNo Machine Found";
        return "§aActive";
    }

    public void collectExperience(Player player) {
        // Experience is automatically sent to controller
        player.sendOverlayMessage(Component.literal("§eExperience is stored in the controller!"));
    }

    // ========================================
    // NBT SERIALIZATION
    // ========================================

    @Override
    public void saveAdditional(ValueOutput view) {
        super.saveAdditional(view);

        if (controllerPos != null) {
            view.putLong("ControllerPos", controllerPos.asLong());
        }
        if (targetMachinePos != null) {
            view.putLong("TargetMachine", targetMachinePos.asLong());
        }

        view.putBoolean("Enabled", enabled);
        view.putInt("Processed", smeltingProcessor.getItemsProcessed());
        view.putString("MachineType", machineType);
        view.putBoolean("WasRedstonePowered", wasRedstonePowered);
        view.putBoolean("IsLinked", isLinked);
        view.putBoolean("HasBeenConfigured", hasBeenConfigured);

        if (config != null && hasBeenConfigured) {
            if (config.customName != null) {
                view.putString("Config_CustomName", config.customName);
            }
            view.putBoolean("Config_Enabled", config.enabled);
            view.putString("Config_RecipeFilter", config.recipeFilter.asString());
            view.putString("Config_FuelFilter", config.fuelFilter.asString());
            view.putInt("Config_ItemsProcessed", config.itemsProcessed);
            view.putInt("Config_Index", config.index);
        }
    }

    @Override
    public void loadAdditional(ValueInput view) {
        super.loadAdditional(view);

        view.getLong("ControllerPos").ifPresent(posLong ->
                this.controllerPos = BlockPos.of(posLong)
        );
        view.getLong("TargetMachine").ifPresent(posLong ->
                this.targetMachinePos = BlockPos.of(posLong)
        );

        this.enabled = view.getBooleanOr("Enabled", false);
        smeltingProcessor.setItemsProcessed(view.getIntOr("Processed", 0));
        this.machineType = view.getStringOr("MachineType", "None");
        this.wasRedstonePowered = view.getBooleanOr("WasRedstonePowered", false);
        this.isLinked = view.getBooleanOr("IsLinked", false);
        this.hasBeenConfigured = view.getBooleanOr("HasBeenConfigured", false);

        if (this.isLinked && this.controllerPos != null) {
            this.needsInitialLink = true;
            this.isLinked = false; // Reset until re-linked
        }

        if (hasBeenConfigured) {
            this.config = new ProcessProbeConfig(this.worldPosition, this.machineType);
            config.customName = view.getStringOr("Config_CustomName", null);
            config.enabled = view.getBooleanOr("Config_Enabled", true);
            config.recipeFilter = RecipeFilterMode.fromString(
                    view.getStringOr("Config_RecipeFilter", "ORES_ONLY")
            );
            config.fuelFilter = FuelFilterMode.fromString(
                    view.getStringOr("Config_FuelFilter", "COAL_ONLY")
            );
            config.itemsProcessed = view.getIntOr("Config_ItemsProcessed", smeltingProcessor.getItemsProcessed());
            config.index = view.getIntOr("Config_Index", 0);
        } else {
            this.config = new ProcessProbeConfig(this.worldPosition, this.machineType);
        }
    }

    // ========================================
    // CLEANUP
    // ========================================

    public void onRemoved() {
        // Grab config from controller before it's gone
        if (level != null && !level.isClientSide() && controllerPos != null) {
            BlockEntity be = level.getBlockEntity(controllerPos);
            if (be instanceof StorageControllerBlockEntity controller) {
                ProcessProbeConfig controllerConfig = controller.getProbeConfig(worldPosition);
                if (controllerConfig != null) {
                    this.config = controllerConfig.copy();
                    this.hasBeenConfigured = true;
                    setChanged();
                }

                controller.unregisterProcessProbe(worldPosition);
            }
        }

        this.controllerPos = null;
        this.targetMachinePos = null;

        // Clear caches
        recipeValidator.clearCache();
        experienceCollector.clearCache();
    }

    public void clearController() {
        // BEFORE clearing, ensure config is saved locally
        if (level != null && controllerPos != null) {
            BlockEntity be = level.getBlockEntity(controllerPos);
            if (be instanceof StorageControllerBlockEntity controller) {
                ProcessProbeConfig controllerConfig = controller.getProbeConfig(worldPosition);
                if (controllerConfig != null) {
                    // Save the controller's config locally before unlinking
                    this.config = controllerConfig.copy();
                    this.hasBeenConfigured = true;
                }
            }
        }

        this.controllerPos = null;
        this.isLinked = false;

        setChanged();

        if (level != null) {
            BlockState state = level.getBlockState(worldPosition);
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    public boolean hasBeenConfigured() {
        return hasBeenConfigured;
    }

    /** Runs before the block entity is removed, for every kind of removal. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        onRemoved();
    }
}
