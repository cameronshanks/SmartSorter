package net.shaddii.smartsorter.blockentity.controller;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.shaddii.smartsorter.blockentity.ProcessProbeBlockEntity;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;
import net.shaddii.smartsorter.network.ProbeStatsSyncPayload;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;
import net.shaddii.smartsorter.util.ProcessProbeConfig;

import java.util.*;

/**
 * Manages process probe registration and configuration.
 */
public class ProcessProbeManager {
    private final Map<BlockPos, ProcessProbeConfig> linkedProcessProbes = new LinkedHashMap<>();

    /**
     * Registers a process probe with the controller.
     */
    public boolean registerProbe(Level world, BlockPos pos, String machineType) {
        if (world == null) return false;

        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof ProcessProbeBlockEntity probe)) return false;

        ProcessProbeConfig config = probe.getConfig().copy();
        config.machineType = machineType;
        config.position = pos;

        // Preserve index if already registered
        if (!linkedProcessProbes.containsKey(pos)) {
            int index = getNextIndexForMachineType(machineType);
            config.setIndex(index);
        } else {
            ProcessProbeConfig existingConfig = linkedProcessProbes.get(pos);
            if (existingConfig != null) {
                config.setIndex(existingConfig.index);
            }
        }

        linkedProcessProbes.put(pos, config);
        probe.setConfig(config);

        return true;
    }

    /**
     * Unregisters a process probe.
     */
    public void unregisterProbe(Level world, BlockPos pos) {
        ProcessProbeConfig config = linkedProcessProbes.get(pos);

        if (config != null) {
            if (world != null) {
                BlockEntity be = world.getBlockEntity(pos);
                if (be instanceof ProcessProbeBlockEntity probe) {
                    probe.setConfig(config.copy());
                }
            }

            linkedProcessProbes.remove(pos);
        }
    }

    /**
     * Updates probe configuration.
     */
    public void updateConfig(Level world, ProcessProbeConfig config,
                             StorageControllerBlockEntity controller) {
        if (!linkedProcessProbes.containsKey(config.position)) return;

        ProcessProbeConfig existing = linkedProcessProbes.get(config.position);

        // Skip update if functionally identical (except stats)
        if (existing != null && existing.isFunctionallyEqual(config)) {
            if (existing.itemsProcessed != config.itemsProcessed) {
                existing.itemsProcessed = config.itemsProcessed;
                syncStatsToClients(world, config.position, config.itemsProcessed, controller);
            }
            return;
        }

        // Update config
        linkedProcessProbes.put(config.position, config.copy());

        if (world != null) {
            BlockEntity be = world.getBlockEntity(config.position);
            if (be instanceof ProcessProbeBlockEntity probe) {
                probe.setConfig(config.copy());
                syncStatsToClients(world, config.position, config.itemsProcessed, controller);
            }
        }
    }

    /**
     * Gets all process probe configs.
     */
    public Map<BlockPos, ProcessProbeConfig> getConfigs() {
        return new LinkedHashMap<>(linkedProcessProbes);
    }

    /**
     * Gets a specific probe config.
     */
    public ProcessProbeConfig getConfig(BlockPos pos) {
        return linkedProcessProbes.get(pos);
    }

    /**
     * Syncs probe stats to all viewing clients.
     */
    public void syncStatsToClients(Level world, BlockPos probePos, int itemsProcessed,
                                   StorageControllerBlockEntity controller) {
        if (world instanceof ServerLevel serverWorld) {
            for (ServerPlayer player : serverWorld.players()) {
                if (player.containerMenu instanceof StorageControllerScreenHandler handler) {
                    if (handler.controller == controller) {
                        ServerPlayNetworking.send(player,
                                new ProbeStatsSyncPayload(probePos, itemsProcessed));
                    }
                }
            }
        }
    }

    /**
     * Unlinks all process probes (cleanup).
     */
    public void unlinkAll(Level world) {
        for (BlockPos pos : new ArrayList<>(linkedProcessProbes.keySet())) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof ProcessProbeBlockEntity probe) {
                probe.clearController();
            }
        }
        linkedProcessProbes.clear();
    }

    /**
     * Writes process probes to NBT.
     */
    public ListTag writeToNbt() {
        ListTag list = new ListTag();
        for (ProcessProbeConfig config : linkedProcessProbes.values()) {
            list.add(config.toNbt());
        }
        return list;
    }

    /**
     * Reads process probes from NBT.
     */
    public void readFromNbt(ListTag list) {
        linkedProcessProbes.clear();

        for (int i = 0; i < list.size(); i++) {
            list.getCompound(i).ifPresent(nbt -> {
                ProcessProbeConfig config = ProcessProbeConfig.fromNbt(nbt);
                if (config != null && config.position != null) {
                    linkedProcessProbes.put(config.position, config);
                }
            });
        }
    }

    /**
     * Gets next available index for a machine type.
     */
    private int getNextIndexForMachineType(String machineType) {
        Set<Integer> usedIndices = new HashSet<>();

        for (ProcessProbeConfig config : linkedProcessProbes.values()) {
            if (config.machineType.equals(machineType)) {
                usedIndices.add(config.index);
            }
        }

        int index = 0;
        while (usedIndices.contains(index)) {
            index++;
        }

        return index;
    }
}