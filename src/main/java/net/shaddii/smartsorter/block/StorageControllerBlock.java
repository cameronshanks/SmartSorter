package net.shaddii.smartsorter.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;
import org.jetbrains.annotations.Nullable;

public class StorageControllerBlock extends BaseEntityBlock {
    // ========================================
    // CONSTANTS
    // ========================================

    public static final MapCodec<StorageControllerBlock> CODEC = simpleCodec(StorageControllerBlock::new);

    // ========================================
    // CONSTRUCTOR
    // ========================================

    public StorageControllerBlock(Properties settings) {
        super(settings);
    }

    // ========================================
    // BLOCK SETUP
    // ========================================

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    // ========================================
    // BLOCK ENTITY
    // ========================================

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StorageControllerBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
        return world.isClientSide() ? null : createTickerHelper(type, SmartSorter.STORAGE_CONTROLLER_BE_TYPE, StorageControllerBlockEntity::tick);
    }

    // ========================================
    // INTERACTION
    // ========================================

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
        if (world.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof StorageControllerBlockEntity controller)) {
            return InteractionResult.PASS;
        }

        ItemStack heldItem = player.getMainHandItem();

        // Let linking tool handle its own logic
        if (heldItem.getItem() instanceof net.shaddii.smartsorter.item.LinkingToolItem) {
            return InteractionResult.PASS;
        }

        // Shift-click: show capacity info
        if (player.isShiftKeyDown()) {
            int free = controller.calculateTotalFreeSlots();
            int total = controller.calculateTotalCapacity();
            int inventories = controller.getLinkedInventoryCount();

            float percentFree = total > 0 ? (free / (float) total) * 100 : 0;
            String color = percentFree > 50 ? "§a" : percentFree > 25 ? "§e" : percentFree > 10 ? "§6" : "§c";

            player.sendOverlayMessage(
                    Component.literal(String.format(
                            "%sFree: §f%d§7/§f%d §8(§f%.0f%%§8) §7in §f%d §7inventories",
                            color, free, total, percentFree, inventories
                    )));

            return InteractionResult.SUCCESS;
        }

        // Normal click: open GUI
        MenuProvider screenHandlerFactory = state.getMenuProvider(world, pos);
        if (screenHandlerFactory != null) {
            player.openMenu(screenHandlerFactory);
        }

        return InteractionResult.SUCCESS;
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

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel world, BlockPos pos, boolean moved) {
        // The block entity is already gone here; its cleanup runs in
        // StorageControllerBlockEntity.preRemoveSideEffects.
        world.updateNeighbourForOutputSignal(pos, this);
        super.affectNeighborsAfterRemoval(state, world, pos, moved);
    }

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