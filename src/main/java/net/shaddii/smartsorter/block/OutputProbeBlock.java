package net.shaddii.smartsorter.block;

import com.mojang.serialization.MapCodec;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.block.WireOrientation;
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

public class OutputProbeBlock extends BlockWithEntity {
    // ========================================
    // CONSTANTS
    // ========================================

    public static final EnumProperty<Direction> FACING = Properties.FACING;
    public static final BooleanProperty LINKED = BooleanProperty.of("linked");
    public static final MapCodec<OutputProbeBlock> CODEC = createCodec(OutputProbeBlock::new);

    // ========================================
    // CONSTRUCTOR
    // ========================================

    public OutputProbeBlock(AbstractBlock.Settings settings) {
        super(settings.luminance(state -> state.get(LINKED) ? 3 : 0));
        this.setDefaultState(this.getStateManager().getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(LINKED, false));
    }

    // ========================================
    // BLOCK SETUP
    // ========================================

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, LINKED);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        Direction playerFacing = ctx.getPlayerLookDirection();
        return this.getDefaultState().with(FACING, playerFacing);
    }

    // ========================================
    // BLOCK ENTITY
    // ========================================

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new OutputProbeBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
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
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack itemStack) {
        super.onPlaced(world, pos, state, placer, itemStack);

        // Only run on server
        if (world.isClient()) return;

        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof OutputProbeBlockEntity probe)) return;

        // Check if facing an ender chest
        Direction face = state.get(FACING);
        BlockPos targetPos = pos.offset(face);
        BlockState targetState = world.getBlockState(targetPos);

        if (targetState.getBlock() instanceof net.minecraft.block.EnderChestBlock) {
            // Upgrade to ender chest probe
            if (placer instanceof PlayerEntity player) {
                // Replace with EnderChestProbeBlockEntity
                world.removeBlockEntity(pos);
                EnderChestProbeBlockEntity enderProbe = new EnderChestProbeBlockEntity(pos, state);
                enderProbe.setOwner(player);
                world.addBlockEntity(enderProbe);

                // Show above hotbar (overlay = true)
                player.sendMessage(Text.literal("§aEnder Chest Probe bound to your ender chest §7(Online only)"), true);
            }
        } else {
            // Normal probe initialization
            probe.onPlaced(world);
        }
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (world.isClient()) return ActionResult.SUCCESS;

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof OutputProbeBlockEntity probe)) {
            return ActionResult.PASS;
        }

        ItemStack heldStack = player.getMainHandStack();
        boolean hasLinkingTool = heldStack.getItem() instanceof LinkingToolItem;

        if (hasLinkingTool) {
            return ActionResult.PASS; // Let linking tool handle it
        }

        // Shift-click with empty hand to cycle mode
        if (player.isSneaking() && heldStack.isEmpty()) {
            probe.cycleMode();

            String modeName = probe.getModeName();
            String modeColor = switch (probe.mode) {
                case FILTER -> "§9";
                case ACCEPT_ALL -> "§a";
                case PRIORITY -> "§6";
            };

            player.sendMessage(Text.literal(modeColor + "Mode: " + modeName), true);
            return ActionResult.SUCCESS;
        }

        // Normal click with empty hand opens the GUI
        if (!player.isSneaking() && heldStack.isEmpty()) {
            // ALWAYS open GUI, no controller check needed
            player.openHandledScreen(probe);
            return ActionResult.SUCCESS;
        }

        // Whitelist editing: while the chest's Editing toggle is on, using an
        // item on the probe adds/removes it from the whitelist instead of testing it.
        if (!player.isSneaking() && !heldStack.isEmpty() && toggleWhitelistItem(world, probe, player, heldStack)) {
            return ActionResult.SUCCESS;
        }

        // Using an item on the probe tests if it's accepted
        if (!player.isSneaking() && !heldStack.isEmpty()) {
            ItemVariant heldVariant = ItemVariant.of(heldStack);
            boolean accepted = probe.accepts(heldVariant);
            String itemName = heldStack.getItem().getName(heldStack).getString();
            String status = accepted ? "§aAccepted" : "§cRejected";

            player.sendMessage(Text.literal(itemName + ": " + status), true);
            return ActionResult.SUCCESS;
        }

        return ActionResult.PASS;
    }

    /**
     * Adds or removes the held item from the chest's whitelist if that chest
     * is a Custom chest with whitelist editing turned on. Returns false (and
     * changes nothing) otherwise, so the normal Accepted/Rejected test runs.
     */
    private static boolean toggleWhitelistItem(World world, OutputProbeBlockEntity probe, PlayerEntity player, ItemStack heldStack) {
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
        controller.markDirty();
        RoutingIndexEpoch.bump(); // filter edit

        String itemName = item.getName(heldStack).getString();
        player.sendMessage(Text.literal(itemName + ": " + (added ? "§aAdded to whitelist" : "§cRemoved from whitelist")), true);

        if (world instanceof ServerWorld serverWorld) {
            WhitelistBroadcast.toViewers(serverWorld.getServer(), controller, WhitelistNetworking.snapshotOf(chestPos, config));
        }
        return true;
    }

    /** The target chest being broken, placed, replaced or joining/leaving a double chest. */
    @Override
    protected void neighborUpdate(BlockState state, World world, BlockPos pos, Block sourceBlock,
                                  @Nullable WireOrientation wireOrientation, boolean notify) {
        super.neighborUpdate(state, world, pos, sourceBlock, wireOrientation, notify);
        if (world.getBlockEntity(pos) instanceof OutputProbeBlockEntity probe) {
            probe.invalidateTargetCache();
        }
    }

    // ========================================
    // RENDERING
    // ========================================

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    // ========================================
    // CLEANUP
    // ========================================

    @Override
    public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        // Get block entity BEFORE calling super (which removes it)
        if (!world.isClient()) {
            BlockEntity blockEntity = world.getBlockEntity(pos);

            if (blockEntity instanceof OutputProbeBlockEntity probe) {
                probe.onRemoved(world);
            }
        }

        return super.onBreak(world, pos, state, player);
    }

    // Keep onStateReplaced for other cases (like explosions, pistons, etc.)
//? if >=1.21.8 {
    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos, boolean moved) {
        // Only handle if not moved by piston
        if (!moved && !world.isClient()) {
            BlockEntity blockEntity = world.getBlockEntity(pos);

            if (blockEntity instanceof OutputProbeBlockEntity probe) {
                probe.onRemoved(world);
            }
        }

        super.onStateReplaced(state, world, pos, moved);
    }
    //?} else {
    /*@Override
    protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        // Only run if actually changing to a different block type
        if (!state.isOf(newState.getBlock()) && !world.isClient()) {
            BlockEntity blockEntity = world.getBlockEntity(pos);

            if (blockEntity instanceof OutputProbeBlockEntity probe && world instanceof ServerWorld serverWorld) {
                probe.onRemoved(serverWorld);
            }
        }

        super.onStateReplaced(state, world, pos, newState, moved);
    }
    *///?}

    // ========================================
    // COMPATIBILITY
    // ========================================

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        Direction facing = state.get(FACING);

        // The flat face points in the FACING direction
        // The slab is 8 pixels thick in the direction it's facing

        return switch (facing) {
            case NORTH -> Block.createCuboidShape(0, 0, 0, 16, 16, 8);   // Flat face on north (z=0)
            case SOUTH -> Block.createCuboidShape(0, 0, 8, 16, 16, 16);  // Flat face on south (z=16)
            case WEST -> Block.createCuboidShape(0, 0, 0, 8, 16, 16);    // Flat face on west (x=0)
            case EAST -> Block.createCuboidShape(8, 0, 0, 16, 16, 16);   // Flat face on east (x=16)
            case DOWN -> Block.createCuboidShape(0, 0, 0, 16, 8, 16);    // Flat face on bottom (y=0)
            case UP -> Block.createCuboidShape(0, 8, 0, 16, 16, 16);     // Flat face on top (y=16)
        };
    }
}