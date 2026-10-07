package net.shaddii.smartsorter.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.blockentity.IntakeBlockEntity;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;
import net.shaddii.smartsorter.chunk.ChunkKeeper;
import net.shaddii.smartsorter.intake.IntakeBuffer;
import net.shaddii.smartsorter.item.LinkingToolItem;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class IntakeBlock extends BaseEntityBlock {
    // ========================================
    // CONSTANTS
    // ========================================

    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final MapCodec<IntakeBlock> CODEC = simpleCodec(IntakeBlock::new);

    // ========================================
    // CONSTRUCTOR
    // ========================================

    public IntakeBlock(BlockBehaviour.Properties settings) {
        super(settings);
        this.registerDefaultState(this.getStateDefinition().any().setValue(FACING, Direction.NORTH));
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
        super.createBlockStateDefinition(builder);
        builder.add(FACING);
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
        return new IntakeBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
        if (world.isClientSide()) return null;
        return type == SmartSorter.INTAKE_BE_TYPE
                ? (world1, pos, state1, be) -> IntakeBlockEntity.tick(world1, pos, state1, (IntakeBlockEntity) be)
                : null;
    }

    // ========================================
    // INTERACTION
    // ========================================

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
        if (world.isClientSide()) return InteractionResult.SUCCESS;

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof IntakeBlockEntity intake)) {
            return InteractionResult.PASS;
        }

        ItemStack heldStack = player.getMainHandItem();

        if (player.isShiftKeyDown() && !heldStack.isEmpty()) return InteractionResult.PASS;
        if (!heldStack.isEmpty() && heldStack.getItem() instanceof LinkingToolItem) return InteractionResult.PASS;

        IntakeBuffer buffer = intake.getIntakeBuffer();

        // Sneak + empty hand with something buffered: hand it all back to the player.
        if (player.isShiftKeyDown() && heldStack.isEmpty() && !buffer.isEmpty()) {
            int total = buffer.totalCount();
            List<ItemStack> stacks = buffer.drain();
            for (ItemStack stack : stacks) {
                player.getInventory().placeItemBackInInventory(stack);
            }
            intake.setChanged();
            player.sendOverlayMessage(Component.literal("§7Intake: §aCleared buffer §7(" + stacks.size() + " stacks, " + total + " items returned)"));
            return InteractionResult.SUCCESS;
        }

        player.sendOverlayMessage(Component.literal(statusText(state, world, intake, buffer)));
        return InteractionResult.SUCCESS;
    }

    /** Action-bar status: facing, buffer contents, link mode, and any stuck items. */
    private static String statusText(BlockState state, Level world, IntakeBlockEntity intake, IntakeBuffer buffer) {
        String facing = state.getValue(FACING).getSerializedName();

        String bufferText;
        List<IntakeBuffer.Entry> entries = buffer.entries();
        if (entries.isEmpty()) {
            bufferText = "§8Empty";
        } else if (entries.size() == 1) {
            ItemStack stack = entries.get(0).stack;
            bufferText = "§e" + stack.getCount() + "x §f" + stack.getHoverName().getString();
        } else {
            bufferText = "§e" + entries.size() + " stacks §7(" + buffer.totalCount() + " items)";
        }

        // Build mode status text
        String modeText;
        if (intake.isInManagedMode()) {
            BlockPos controllerPos = intake.getController();
            if (world.hasChunkAt(controllerPos)
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
                names.append(entry.stack.getHoverName().getString());
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
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    // ========================================
    // CLEANUP
    // ========================================

    // Dropping the buffer and unlinking run in IntakeBlockEntity.preRemoveSideEffects.

    // ========================================
    // COMPATIBILITY
    // ========================================


    @Override
    public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        // Define the actual shape of your block for proper culling
        // This example uses full cube, adjust if your blocks are smaller
        return Shapes.block();
    }
}