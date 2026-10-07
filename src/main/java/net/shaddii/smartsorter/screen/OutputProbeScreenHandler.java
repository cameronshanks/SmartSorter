package net.shaddii.smartsorter.screen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;
import net.shaddii.smartsorter.network.ChestConfigUpdatePayload;
import net.shaddii.smartsorter.util.ChestConfig;

import org.jetbrains.annotations.Nullable;

public class OutputProbeScreenHandler extends AbstractContainerMenu {

    public BlockPos chestPos;
    private ChestConfig chestConfig;
    private ChestConfig cachedConfig = null;
    private long lastConfigRefresh = 0;
    private static final long CONFIG_REFRESH_INTERVAL = 100;

    // Reference to controller (may be null if not linked)
    @Nullable
    public StorageControllerBlockEntity controller;

    @Nullable
    public final OutputProbeBlockEntity probe;

    // Constructor for client-side
    public OutputProbeScreenHandler(int syncId, Inventory playerInventory, OutputProbeBlockEntity.ProbeData data) {
        this(syncId, playerInventory, (OutputProbeBlockEntity) null);

        // Read data from server
        this.chestPos = data.chestPos();
        this.chestConfig = data.config();
    }

    // Constructor for server-side (from block entity)
    public OutputProbeScreenHandler(int syncId, Inventory playerInventory, @Nullable OutputProbeBlockEntity probe) {
        super(SmartSorter.OUTPUT_PROBE_SCREEN_HANDLER, syncId);
        this.probe = probe;

        if (probe != null) {
            this.chestPos = probe.getTargetPos();

            this.chestConfig = probe.getChestConfig();

            // Try to find linked controller
            BlockPos controllerPos = probe.getLinkedController();
            if (controllerPos != null && probe.getLevel() != null) {
                var be = probe.getLevel().getBlockEntity(controllerPos);
                if (be instanceof StorageControllerBlockEntity controllerBE) {
                    this.controller = controllerBE;
                }
            }
        }

        // Create default config if still null
        if (this.chestConfig == null && this.chestPos != null) {
            this.chestConfig = new ChestConfig(this.chestPos);
        }
    }

    @Nullable
    public ChestConfig getChestConfig() {
        long now = System.currentTimeMillis();

        // Use cached config if recent enough
        if (cachedConfig != null && now - lastConfigRefresh < CONFIG_REFRESH_INTERVAL) {
            return cachedConfig;
        }

        // Refresh from controller if available
        if (controller != null && chestPos != null) {
            ChestConfig fresh = controller.getChestConfig(chestPos);
            if (fresh != null) {
                this.chestConfig = fresh;
                this.cachedConfig = fresh;
                this.lastConfigRefresh = now;
                return fresh;
            }
        }

        cachedConfig = chestConfig;
        lastConfigRefresh = now;
        return chestConfig;
    }

    public void setChestConfig(ChestConfig config) {
        this.chestConfig = config;
        this.cachedConfig = config;
        this.lastConfigRefresh = System.currentTimeMillis();
    }

    public void updateChestConfig(ChestConfig updatedConfig) {
        this.chestConfig = updatedConfig;

        if (probe != null) {
            probe.setChestConfig(updatedConfig);
        }

        if (chestPos != null) {
            ClientPlayNetworking.send(new ChestConfigUpdatePayload(updatedConfig));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        if (probe != null) {
            return player.distanceToSqr(
                    probe.getBlockPos().getX() + 0.5,
                    probe.getBlockPos().getY() + 0.5,
                    probe.getBlockPos().getZ() + 0.5
            ) <= 64.0;
        }
        return chestPos == null || player.distanceToSqr(
                chestPos.getX() + 0.5,
                chestPos.getY() + 0.5,
                chestPos.getZ() + 0.5
        ) <= 64.0;
    }
}