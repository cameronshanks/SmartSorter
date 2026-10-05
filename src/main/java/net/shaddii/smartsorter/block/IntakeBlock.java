package net.shaddii.smartsorter.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.blockentity.IntakeBlockEntity;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;
import net.shaddii.smartsorter.chunk.ChunkKeeper;
import net.shaddii.smartsorter.intake.IntakeBuffer;
import net.shaddii.smartsorter.item.LinkingToolItem;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class IntakeBlock extends BlockWithEntity {
    // ========================================
    // CONSTANTS
    // ========================================

    public static final EnumProperty<Direction> FACING = Properties.FACING;
    public static final MapCodec<IntakeBlock> CODEC = createCodec(IntakeBlock::new);

    // ========================================
    // CONSTRUCTOR
    // ========================================

    public IntakeBlock(AbstractBlock.Settings settings) {
        super(settings);
        this.setDefaultState(this.getStateManager().getDefaultState().with(FACING, Direction.NORTH));
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
        super.appendProperties(builder);
        builder.add(FACING);
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
        return new IntakeBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (world.isClient()) return null;
        return type == SmartSorter.INTAKE_BE_TYPE
                ? (world1, pos, state1, be) -> IntakeBlockEntity.tick(world1, pos, state1, (IntakeBlockEntity) be)
                : null;
    }

    // ========================================
    // INTERACTION
    // ========================================

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (world.isClient()) return ActionResult.SUCCESS;

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof IntakeBlockEntity intake)) {
            return ActionResult.PASS;
        }

        ItemStack heldStack = player.getMainHandStack();

        if (player.isSneaking() && !heldStack.isEmpty()) return ActionResult.PASS;
        if (!heldStack.isEmpty() && heldStack.getItem() instanceof LinkingToolItem) return ActionResult.PASS;

        IntakeBuffer buffer = intake.getIntakeBuffer();

        // Sneak + empty hand with something buffered: hand it all back to the player.
        if (player.isSneaking() && heldStack.isEmpty() && !buffer.isEmpty()) {
            int total = buffer.totalCount();
            List<ItemStack> stacks = buffer.drain();
            for (ItemStack stack : stacks) {
                player.getInventory().offerOrDrop(stack);
            }
            intake.markDirty();
            player.sendMessage(Text.literal("§7Intake: §aCleared buffer §7(" + stacks.size() + " stacks, " + total + " items returned)"), true);
            return ActionResult.SUCCESS;
        }

        player.sendMessage(Text.literal(statusText(state, world, intake, buffer)), true);
        return ActionResult.SUCCESS;
    }

    /** Action-bar status: facing, buffer contents, link mode, and any stuck items. */
    private static String statusText(BlockState state, World world, IntakeBlockEntity intake, IntakeBuffer buffer) {
        String facing = state.get(FACING).asString();

        String bufferText;
        List<IntakeBuffer.Entry> entries = buffer.entries();
        if (entries.isEmpty()) {
            bufferText = "§8Empty";
        } else if (entries.size() == 1) {
            ItemStack stack = entries.get(0).stack;
            bufferText = "§e" + stack.getCount() + "x §f" + stack.getName().getString();
        } else {
            bufferText = "§e" + entries.size() + " stacks §7(" + buffer.totalCount() + " items)";
        }

        // Build mode status text
        String modeText;
        if (intake.isInManagedMode()) {
            BlockPos controllerPos = intake.getController();
            if (world.isChunkLoaded(controllerPos)
                    && world.getBlockEntity(controllerPos) instanceof StorageControllerBlockEntity controller) {
                int probeCount = controller.getLinkedProbes().size();
                if (probeCount == 0) {
                    modeText = "§e⚠ Managed Mode (No Probes)";
                } else {
                    modeText = "§aManaged Mode (" + probeCount + " probes)";
                }
            } else {
                modeText = "§c✗ Managed Mode (Invalid Link)";
            }
        } else if (intake.isInDirectMode()) {
            int outputCount = intake.getOutputs().size();
            modeText = "§bDirect Mode (" + outputCount + " outputs)";
        } else {
            modeText = "§cNot Linked";
        }

        String text = "§7Intake §8[§b" + facing + "§8] §7| Buffer: " + bufferText + " §7| " + modeText;

        int stuckTypes = buffer.stuckTypes();
        if (stuckTypes > 0) {
            StringBuilder names = new StringBuilder();
            int shown = 0;
            for (IntakeBuffer.Entry entry : entries) {
                if (!entry.stuck) continue;
                if (shown == 3) {
                    names.append(", ...");
                    break;
                }
                if (shown > 0) names.append(", ");
                names.append(entry.stack.getName().getString());
                shown++;
            }
            text += " §7| §c⚠ Stuck: " + stuckTypes + " type" + (stuckTypes == 1 ? "" : "s")
                    + " (" + buffer.stuckCount() + " items): " + names + " §8(sneak + empty hand to clear)";
        }
        return text;
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

    //? if >=1.21.8 {
    @Override
    protected void onStateReplaced(BlockState state, net.minecraft.server.world.ServerWorld world, BlockPos pos, boolean moved) {
        if (!moved) {
            BlockEntity blockEntity = world.getBlockEntity(pos);

            // Scatter buffered items
            if (blockEntity instanceof IntakeBlockEntity intake) {
                for (ItemStack stack : intake.getIntakeBuffer().drain()) {
                    ItemScatterer.spawn(world, pos.getX(), pos.getY(), pos.getZ(), stack);
                }
            }
            ChunkKeeper.unregister(world, pos);

            // Unlink from controller
            if (blockEntity instanceof IntakeBlockEntity intake) {
                BlockPos controllerPos = intake.getController();
                if (controllerPos != null) {
                    BlockEntity controllerBE = world.getBlockEntity(controllerPos);
                    if (controllerBE instanceof StorageControllerBlockEntity controller) {
                        controller.removeIntake(pos);
                    }
                }

                // Unlink from probes (direct mode)
                for (BlockPos probePos : new java.util.ArrayList<>(intake.getOutputs())) {
                    BlockEntity probeBE = world.getBlockEntity(probePos);
                    if (probeBE instanceof OutputProbeBlockEntity probe) {
                        probe.removeLinkedBlock(pos);
                    }
                }
            }
        }

        super.onStateReplaced(state, world, pos, moved);
    }
    //?} else {
    /*@Override
    protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock())) {
            BlockEntity blockEntity = world.getBlockEntity(pos);

            // Scatter buffered items
            if (blockEntity instanceof IntakeBlockEntity intake && !intake.getBuffer().isEmpty()) {
                ItemScatterer.spawn(world, pos.getX(), pos.getY(), pos.getZ(), intake.getBuffer());
            }

            // Unlink from controller
            if (!world.isClient() && blockEntity instanceof IntakeBlockEntity intake) {
                BlockPos controllerPos = intake.getController();
                if (controllerPos != null) {
                    BlockEntity controllerBE = world.getBlockEntity(controllerPos);
                    if (controllerBE instanceof StorageControllerBlockEntity controller) {
                        controller.removeIntake(pos);
                    }
                }

                // Unlink from probes (direct mode)
                for (BlockPos probePos : new java.util.ArrayList<>(intake.getOutputs())) {
                    BlockEntity probeBE = world.getBlockEntity(probePos);
                    if (probeBE instanceof OutputProbeBlockEntity probe) {
                        probe.removeLinkedBlock(pos);
                    }
                }
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
        // Define the actual shape of your block for proper culling
        // This example uses full cube, adjust if your blocks are smaller
        return VoxelShapes.fullCube();
    }
}