package net.shaddii.smartsorter.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.world.level.block.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
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
import net.shaddii.smartsorter.blockentity.ProcessProbeBlockEntity;
import net.shaddii.smartsorter.item.LinkingToolItem;

import org.jetbrains.annotations.Nullable;

public class ProcessProbeBlock extends BaseEntityBlock {
    // ========================================
    // CONSTANTS
    // ========================================

    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final MapCodec<ProcessProbeBlock> CODEC = simpleCodec(ProcessProbeBlock::new);

    // ========================================
    // CONSTRUCTOR
    // ========================================

    public ProcessProbeBlock(BlockBehaviour.Properties settings) {
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
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction side = ctx.getClickedFace();
        return this.defaultBlockState().setValue(FACING, side.getOpposite());
    }

    @Override
    public void setPlacedBy(Level world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack itemStack) {
        super.setPlacedBy(world, pos, state, placer, itemStack);

        if (world.isClientSide() || !(world instanceof ServerLevel)) return;

        Direction facing = state.getValue(FACING);
        BlockPos targetPos = pos.relative(facing);
        BlockEntity targetEntity = world.getBlockEntity(targetPos);
        BlockState targetState = world.getBlockState(targetPos);

        // Determine machine type
        String machineType = null;
        boolean isValid = false;

        if (targetEntity instanceof AbstractFurnaceBlockEntity) {
            if (targetState.is(Blocks.FURNACE)) {
                machineType = "Furnace";
                isValid = true;
            } else if (targetState.is(Blocks.BLAST_FURNACE)) {
                machineType = "Blast Furnace";
                isValid = true;
            } else if (targetState.is(Blocks.SMOKER)) {
                machineType = "Smoker";
                isValid = true;
            }
        }

        // Send feedback to placer
        if (placer instanceof ServerPlayer player) {
            if (isValid) {
                Component message = Component.literal("Linked to " + machineType).withStyle(ChatFormatting.GREEN);
                player.sendOverlayMessage(message);
            } else {
                Component message = Component.literal("No valid processing machine found").withStyle(ChatFormatting.RED);
                player.sendOverlayMessage(message);
            }
        }
    }

    // ========================================
    // BLOCK ENTITY
    // ========================================

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ProcessProbeBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
        if (world == null || world.isClientSide()) return null;
        return type == SmartSorter.PROCESS_PROBE_BE_TYPE
                ? (world1, pos, state1, be) -> ProcessProbeBlockEntity.tick(world1, pos, state1, (ProcessProbeBlockEntity) be)
                : null;
    }

    // ========================================
    // INTERACTION
    // ========================================

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
        if (world == null) return InteractionResult.PASS;
        if (world.isClientSide()) return InteractionResult.SUCCESS;

        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof ProcessProbeBlockEntity probe)) {
            return InteractionResult.PASS;
        }

        ItemStack held = player.getMainHandItem();
        if (held.getItem() instanceof LinkingToolItem) {
            return InteractionResult.PASS;
        }

        // Show status
        String machineType = probe.getMachineType();
        String status = probe.getStatusText();
        int processed = probe.getProcessedCount();
        boolean enabled = probe.isEnabled();

        Component message = Component.literal(
                String.format("§7Process Probe §8[§b%s§8]\n", state.getValue(FACING).getSerializedName()) +
                        String.format("§7Machine: §f%s\n", machineType) +
                        String.format("§7Status: %s\n", status) +
                        String.format("§7Processed: §f%d items\n", processed) +
                        String.format("§7Mode: %s", enabled ? "§aEnabled" : "§cDisabled")
        );

        player.sendSystemMessage(message);
        return InteractionResult.SUCCESS;
    }

    protected void neighborUpdate(BlockState state, Level world, BlockPos pos, Block sourceBlock, BlockPos sourcePos, boolean notify) {
        if (world != null && !world.isClientSide()) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof ProcessProbeBlockEntity probe) {
                boolean powered = world.hasNeighborSignal(pos);
                probe.setEnabled(powered);
            }
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

    // Unregistering runs in ProcessProbeBlockEntity.preRemoveSideEffects.

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