package net.shaddii.smartsorter.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.shaddii.smartsorter.SmartSorter;
import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * Simpler ender chest probe - only works when owner is online
 */
public class EnderChestProbeBlockEntity extends OutputProbeBlockEntity {

    @Nullable
    private UUID ownerUUID = null;

    public EnderChestProbeBlockEntity(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    public void setOwner(UUID playerUUID) {
        this.ownerUUID = playerUUID;
        setChanged();
    }

    public void setOwner(Player player) {
        setOwner(player.getUUID());
    }

    @Nullable
    public UUID getOwner() {
        return ownerUUID;
    }

    /**
     * Override to return ender chest inventory if owner is online
     */
    @Override
    public Container getTargetInventory() {
        if (level == null || level.isClientSide()) return null;
        if (!(level instanceof ServerLevel serverWorld)) return null;
        if (ownerUUID == null) return null;

        // Try to get online player
        ServerPlayer player = serverWorld.getServer().getPlayerList().getPlayer(ownerUUID);

        if (player != null) {
            // Player is online - return their ender chest inventory
            return player.getEnderChestInventory();
        }

        // Player offline - can't access
        return null;
    }

    // ========================================
    // NBT SERIALIZATION
    // ========================================

    @Override
    public void saveAdditional(ValueOutput view) {
        super.saveAdditional(view);

        if (ownerUUID != null) {
            view.putBoolean("hasOwner", true);
            view.putLong("ownerMost", ownerUUID.getMostSignificantBits());
            view.putLong("ownerLeast", ownerUUID.getLeastSignificantBits());
        } else {
            view.putBoolean("hasOwner", false);
        }
    }

    @Override
    public void loadAdditional(ValueInput view) {
        super.loadAdditional(view);

        if (view.getBooleanOr("hasOwner", false)) {
            long most = view.getLongOr("ownerMost", 0);
            long least = view.getLongOr("ownerLeast", 0);
            if (most != 0 || least != 0) {
                this.ownerUUID = new UUID(most, least);
            }
        }
    }
}