package net.shaddii.smartsorter.block;

import com.mojang.serialization.MapCodec;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.world.level.block.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.blockentity.EnderChestProbeBlockEntity;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;
import net.shaddii.smartsorter.item.LinkingToolItem;
import net.shaddii.smartsorter.network.WhitelistBroadcast;
import net.shaddii.smartsorter.network.WhitelistNetworking;
import net.shaddii.smartsorter.util.ChestConfig;
import net.shaddii.smartsorter.util.RoutingIndexEpoch;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

public class OutputProbeBlock extends BaseEntityBlock {
    // ========================================
    // CONSTANTS
    // ========================================

    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final BooleanProperty LINKED = BooleanProperty.create("linked");
    public static final MapCodec<OutputProbeBlock> CODEC = simpleCodec(OutputProbeBlock::new);

    // ========================================
    // CONSTRUCTOR
    // ========================================

    public OutputProbeBlock(BlockBehaviour.Properties settings) {
        super(settings.lightLevel(state -> state.getValue(LINKED) ? 3 : 0));
        this.registerDefaultState(this.getStateDefinition().any()
                .setValue(FACING, Direction.NORTH)
                .setValue(LINKED, false));
    }

    // ========================================
    // BLOCK SETUP
    // ========================================

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LINKED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction playerFacing = ctx.getNearestLookingDirection();
        return this.defaultBlockState().setValue(FACING, playerFacing);
    }

    // ========================================
    // BLOCK ENTITY
    // ========================================

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OutputProbeBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
        // Both OutputProbeBlockEntity and EnderChestProbeBlockEntity use the same ticker
        if (type == SmartSorter.PROBE_BE_TYPE || type == SmartSorter.ENDER_PROBE_BE_TYPE) {
            return (world1, pos, state1, be) -> OutputProbeBlockEntity.tick(world1, pos, state1, (OutputProbeBlockEntity) be);
        }
        return null;
    }

    // ========================================
    // INTERACTION
    // ========================================

    @Override
    public void setPlacedBy(Level world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack itemStack) {
        super.setPlacedBy(world, pos, state, placer, itemStack);

        // Only run on server
        if (world.isClientSide()) return;

        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof OutputProbeBlockEntity probe)) return;

        // Check if facing an ender chest
        Direction face = state.getValue(FACING);
        BlockPos targetPos = pos.relative(face);
        BlockState targetState = world.getBlockState(targetPos);

        if (targetState.getBlock() instanceof net.minecraft.world.level.block.EnderChestBlock) {
            // Upgrade to ender chest probe
            if (placer instanceof Player player) {
                // Replace with EnderChestProbeBlockEntity
                world.removeBlockEntity(pos);
                EnderChestProbeBlockEntity enderProbe = new EnderChestProbeBlockEntity(pos, state);
                enderProbe.setOwner(player);
                world.setBlockEntity(enderProbe);

                // Show above hotbar (overlay = true)
                player.sendOverlayMessage(Component.literal("§aEnder Chest Probe bound to your ender chest §7(Online only)"));
            }
        } else {
            // Normal probe initialization
            probe.onPlaced(world);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
        if (world.isClientSide()) return InteractionResult.SUCCESS;

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof OutputProbeBlockEntity probe)) {
            return InteractionResult.PASS;
        }

        ItemStack heldStack = player.getMainHandItem();
        boolean hasLinkingTool = heldStack.getItem() instanceof LinkingToolItem;

        if (hasLinkingTool) {
            return InteractionResult.PASS; // Let linking tool handle it
        }

        // Shift-click with empty hand shows what the probe is set to
        if (player.isShiftKeyDown() && heldStack.isEmpty()) {
            ChestConfig config = probe.getChestConfig();
            String description = config != null ? " §7- " + config.filterMode.getDescription() : "";
            player.sendOverlayMessage(Component.literal("§b" + probe.getFilterSummary() + description + " §8(right-click to configure)"));
            return InteractionResult.SUCCESS;
        }

        // Normal click with empty hand opens the GUI
        if (!player.isShiftKeyDown() && heldStack.isEmpty()) {
            // ALWAYS open GUI, no controller check needed
            player.openMenu(probe);
            return InteractionResult.SUCCESS;
        }

        // Whitelist editing: while the chest's Editing toggle is on, using an
        // item on the probe adds/removes it from the whitelist instead of testing it.
        if (!player.isShiftKeyDown() && !heldStack.isEmpty() && toggleWhitelistItem(world, probe, player, heldStack)) {
            return InteractionResult.SUCCESS;
        }

        // Using an item on the probe tests if it's accepted
        if (!player.isShiftKeyDown() && !heldStack.isEmpty()) {
            ItemVariant heldVariant = ItemVariant.of(heldStack);
            boolean accepted = probe.accepts(heldVariant);
            String itemName = heldStack.getItem().getName(heldStack).getString();
            String status = accepted ? "§aAccepted" : "§cRejected";

            player.sendOverlayMessage(Component.literal(itemName + ": " + status));
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.PASS;
    }

    /**
     * Adds or removes the held item from the chest's whitelist if that chest
     * is a Custom chest with whitelist editing turned on. Returns false (and
     * changes nothing) otherwise, so the normal Accepted/Rejected test runs.
     */
    private static boolean toggleWhitelistItem(Level world, OutputProbeBlockEntity probe, Player player, ItemStack heldStack) {
        BlockPos controllerPos = probe.getLinkedController();
        BlockPos chestPos = probe.getTargetPos();
        if (controllerPos == null || chestPos == null) return false;
        if (!(world.getBlockEntity(controllerPos) instanceof StorageControllerBlockEntity controller)) return false;

        ChestConfig config = controller.getChestConfig(chestPos);
        if (config == null || config.filterMode != ChestConfig.FilterMode.CUSTOM || !config.whitelistEditMode) {
            return false;
        }

        Item item = heldStack.getItem();
        Set<Item> items = new HashSet<>(config.getWhitelist());
        boolean added = items.add(item);
        if (!added) {
            items.remove(item);
        }
        config.setWhitelist(items);
        controller.setChanged();
        RoutingIndexEpoch.bump(); // filter edit

        String itemName = item.getName(heldStack).getString();
        player.sendOverlayMessage(Component.literal(itemName + ": " + (added ? "§aAdded to whitelist" : "§cRemoved from whitelist")));

        if (world instanceof ServerLevel serverWorld) {
            WhitelistBroadcast.toViewers(serverWorld.getServer(), controller, WhitelistNetworking.snapshotOf(chestPos, config));
        }
        return true;
    }

    /** The target chest being broken, placed, replaced or joining/leaving a double chest. */
    @Override
    protected void neighborChanged(BlockState state, Level world, BlockPos pos, Block sourceBlock,
                                  @Nullable Orientation wireOrientation, boolean notify) {
        super.neighborChanged(state, world, pos, sourceBlock, wireOrientation, notify);
        if (world.getBlockEntity(pos) instanceof OutputProbeBlockEntity probe) {
            probe.invalidateTargetCache();
        }
    }

    // ========================================
    // RENDERING
    // ========================================

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    // ========================================
    // CLEANUP
    // ========================================

    // Unlinking runs in OutputProbeBlockEntity.preRemoveSideEffects, which
    // covers players, explosions and commands alike.

    // ========================================
    // COMPATIBILITY
    // ========================================

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        Direction facing = state.getValue(FACING);

        // The flat face points in the FACING direction
        // The slab is 8 pixels thick in the direction it's facing

        return switch (facing) {
            case NORTH -> Block.box(0, 0, 0, 16, 16, 8);   // Flat face on north (z=0)
            case SOUTH -> Block.box(0, 0, 8, 16, 16, 16);  // Flat face on south (z=16)
            case WEST -> Block.box(0, 0, 0, 8, 16, 16);    // Flat face on west (x=0)
            case EAST -> Block.box(8, 0, 0, 16, 16, 16);   // Flat face on east (x=16)
            case DOWN -> Block.box(0, 0, 0, 16, 8, 16);    // Flat face on bottom (y=0)
            case UP -> Block.box(0, 8, 0, 16, 16, 16);     // Flat face on top (y=16)
        };
    }
}