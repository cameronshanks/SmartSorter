package net.shaddii.smartsorter.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.shaddii.smartsorter.block.IntakeBlock;
import net.shaddii.smartsorter.block.OutputProbeBlock;
import net.shaddii.smartsorter.block.ProcessProbeBlock;
import net.shaddii.smartsorter.block.StorageControllerBlock;
import net.shaddii.smartsorter.blockentity.IntakeBlockEntity;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.blockentity.ProcessProbeBlockEntity;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class LinkingToolItem extends Item {

    // ========================================
    // FIELDS
    // ========================================

    private static final Map<UUID, BlockPos> STORED_CONTROLLER = new HashMap<>();
    private static final Map<UUID, BlockPos> STORED_INTAKE = new HashMap<>();

    /**
     * Forget a player's selection. Positions carry no dimension, so a
     * selection must not outlive the world or dimension it was made in.
     */
    public static void clearSelection(UUID playerId) {
        STORED_CONTROLLER.remove(playerId);
        STORED_INTAKE.remove(playerId);
    }

    // ========================================
    // CONSTRUCTOR
    // ========================================

    public LinkingToolItem(Properties settings) {
        super(settings);
    }

    // ========================================
    // USE IN AIR (VERSION-SPECIFIC)
    // ========================================

    @Override
    public InteractionResult use(Level world, Player player, net.minecraft.world.InteractionHand hand) {
        if (player.isShiftKeyDown()) {
            if (!world.isClientSide()) {
                boolean hadController = STORED_CONTROLLER.remove(player.getUUID()) != null;
                boolean hadIntake = STORED_INTAKE.remove(player.getUUID()) != null;

                if (hadController && hadIntake) {
                    player.sendOverlayMessage(Component.literal("§eCleared controller + intake selection").withStyle(ChatFormatting.YELLOW));
                } else if (hadController) {
                    player.sendOverlayMessage(Component.literal("§eCleared controller selection").withStyle(ChatFormatting.YELLOW));
                } else if (hadIntake) {
                    player.sendOverlayMessage(Component.literal("§eCleared intake selection").withStyle(ChatFormatting.YELLOW));
                } else {
                    player.sendOverlayMessage(Component.literal("§7Nothing to clear").withStyle(ChatFormatting.GRAY));
                }
            }

            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    // ========================================
    // USE ON BLOCK
    // ========================================

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level world = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();

        if (player == null) return InteractionResult.PASS;

        var blockState = world.getBlockState(pos);

        // ========================================
        // SHIFT-CLICK HANDLING
        // ========================================

        if (player.isShiftKeyDown()) {
            // Clear stored controller / intake
            if (!world.isClientSide()) {
                boolean hadController = STORED_CONTROLLER.remove(player.getUUID()) != null;
                boolean hadIntake = STORED_INTAKE.remove(player.getUUID()) != null;

                if (hadController && hadIntake) {
                    player.sendOverlayMessage(Component.literal("§eCleared controller + intake selection").withStyle(ChatFormatting.YELLOW));
                } else if (hadController) {
                    player.sendOverlayMessage(Component.literal("§eCleared controller selection").withStyle(ChatFormatting.YELLOW));
                } else if (hadIntake) {
                    player.sendOverlayMessage(Component.literal("§eCleared intake selection").withStyle(ChatFormatting.YELLOW));
                } else {
                    player.sendOverlayMessage(Component.literal("§7Nothing to clear").withStyle(ChatFormatting.GRAY));
                }
            }
            return InteractionResult.SUCCESS;
        }

        if (world.isClientSide()) return InteractionResult.SUCCESS;

        // ========================================
        // STORAGE CONTROLLER LINKING
        // ========================================

        if (blockState.getBlock() instanceof StorageControllerBlock) {
            STORED_CONTROLLER.put(player.getUUID(), pos);

            player.sendOverlayMessage(
                    Component.literal("§aStorage Controller selected  §7Now right-click probes or intakes to link them"));
            return InteractionResult.SUCCESS;
        }

        // ========================================
        // OUTPUT PROBE LINKING
        // ========================================

        if (blockState.getBlock() instanceof OutputProbeBlock) {
            BlockPos controllerPos = STORED_CONTROLLER.get(player.getUUID());
            BlockPos intakePos = STORED_INTAKE.get(player.getUUID());

            // DIRECT MODE: Link intake directly to probe
            if (intakePos != null && controllerPos == null) {
                var intakeBE = world.getBlockEntity(intakePos);
                if (!(intakeBE instanceof IntakeBlockEntity intake)) {
                    player.sendOverlayMessage(Component.literal("§cIntake no longer exists!").withStyle(ChatFormatting.RED));
                    STORED_INTAKE.remove(player.getUUID());
                    return InteractionResult.FAIL;
                }

                var probeBE = world.getBlockEntity(pos);
                if (!(probeBE instanceof OutputProbeBlockEntity probe)) {
                    player.sendOverlayMessage(Component.literal("§cProbe not found!").withStyle(ChatFormatting.RED));
                    return InteractionResult.FAIL;
                }

                boolean intakeAdded = intake.addOutput(pos);
                boolean probeAdded = probe.addLinkedBlock(intakePos);

                if (intakeAdded && probeAdded) {
                    player.sendOverlayMessage(
                            Component.literal("§a✓ Direct Mode | §b" + probe.getFilterSummary() + " §7(Click intake again to add more)"));

                    STORED_INTAKE.remove(player.getUUID());

                    return InteractionResult.SUCCESS;
                } else {
                    player.sendOverlayMessage(Component.literal("§eAlready linked!").withStyle(ChatFormatting.YELLOW));
                    return InteractionResult.FAIL;
                }
            }

            // MANAGED MODE: Link controller to probe
            if (controllerPos != null) {
                var controllerBE = world.getBlockEntity(controllerPos);
                if (!(controllerBE instanceof StorageControllerBlockEntity controller)) {
                    player.sendOverlayMessage(Component.literal("§cController no longer exists!").withStyle(ChatFormatting.RED));
                    STORED_CONTROLLER.remove(player.getUUID());
                    return InteractionResult.FAIL;
                }

                var probeBE = world.getBlockEntity(pos);
                if (!(probeBE instanceof OutputProbeBlockEntity probe)) {
                    player.sendOverlayMessage(Component.literal("§cProbe not found!").withStyle(ChatFormatting.RED));
                    return InteractionResult.FAIL;
                }

                // Check if BOTH sides are already linked (bidirectional check)
                boolean isBidirectionallyLinked =
                        probe.getLinkedBlocks().contains(controllerPos) &&
                                controller.getLinkedProbes().contains(pos);

                if (isBidirectionallyLinked) {
                    player.sendOverlayMessage(Component.literal("§eAlready linked to this controller!").withStyle(ChatFormatting.YELLOW));
                    return InteractionResult.FAIL;
                }

                // Try to add the links (Fix 2 will clean up stale links here)
                boolean controllerAdded = controller.addProbe(pos);
                boolean probeAdded = probe.addLinkedBlock(controllerPos);

                if (controllerAdded || probeAdded) {
                    // At least one side was added (success or repair)
                    player.sendOverlayMessage(
                            Component.literal("§a✓ Output Probe linked | §b" + probe.getFilterSummary()));
                    return InteractionResult.SUCCESS;
                } else {
                    player.sendOverlayMessage(Component.literal("§eAlready linked!").withStyle(ChatFormatting.YELLOW));
                    return InteractionResult.FAIL;
                }
            }
            // Nothing stored
            player.sendOverlayMessage(Component.literal("§eSelect a Storage Controller or Intake first!").withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }

        // ========================================
        // PROCESS PROBE LINKING
        // ========================================

        if (blockState.getBlock() instanceof ProcessProbeBlock) {
            BlockPos controllerPos = STORED_CONTROLLER.get(player.getUUID());

            if (controllerPos == null) {
                player.sendOverlayMessage(Component.literal("§eSelect a Storage Controller first!").withStyle(ChatFormatting.RED));
                return InteractionResult.FAIL;
            }

            var controllerBE = world.getBlockEntity(controllerPos);
            if (!(controllerBE instanceof StorageControllerBlockEntity controller)) {
                player.sendOverlayMessage(Component.literal("§cController no longer exists!").withStyle(ChatFormatting.RED));
                STORED_CONTROLLER.remove(player.getUUID());
                return InteractionResult.FAIL;
            }

            var probeBE = world.getBlockEntity(pos);
            if (!(probeBE instanceof ProcessProbeBlockEntity probe)) {
                player.sendOverlayMessage(Component.literal("§cProcess Probe not found!").withStyle(ChatFormatting.RED));
                return InteractionResult.FAIL;
            }

            boolean probeAdded = probe.addLinkedBlock(controllerPos);

            if (probeAdded) {
                player.sendOverlayMessage(
                        Component.literal("§a✓ Process Probe linked"));
                return InteractionResult.SUCCESS;
            } else {
                player.sendOverlayMessage(Component.literal("§eAlready linked!").withStyle(ChatFormatting.YELLOW));
                return InteractionResult.FAIL;
            }
        }

        // ========================================
        // INTAKE LINKING
        // ========================================

        if (blockState.getBlock() instanceof IntakeBlock) {
            BlockPos controllerPos = STORED_CONTROLLER.get(player.getUUID());

            // If no controller selected, store this intake for direct linking
            if (controllerPos == null) {
                STORED_INTAKE.put(player.getUUID(), pos);
                player.sendOverlayMessage(
                        Component.literal("§bIntake selected  §7Right-click Output Probes for Direct Mode or select Storage Controller for Managed Mode"));
                return InteractionResult.SUCCESS;
            }

            // MANAGED MODE: Controller is selected
            var controllerBE = world.getBlockEntity(controllerPos);
            if (!(controllerBE instanceof StorageControllerBlockEntity controller)) {
                player.sendOverlayMessage(Component.literal("§cController no longer exists!").withStyle(ChatFormatting.RED));
                STORED_CONTROLLER.remove(player.getUUID());
                return InteractionResult.FAIL;
            }

            var intakeBE = world.getBlockEntity(pos);
            if (!(intakeBE instanceof IntakeBlockEntity intake)) {
                player.sendOverlayMessage(Component.literal("§cIntake not found!").withStyle(ChatFormatting.RED));
                return InteractionResult.FAIL;
            }

            boolean intakeLinked = intake.setController(controllerPos);
            boolean controllerLinked = controller.addIntake(pos);

            if (intakeLinked && controllerLinked) {
                int probeCount = controller.getLinkedProbes().size();
                String probeStatus = probeCount == 0
                        ? "§e(No output probes yet)"
                        : "§a(" + probeCount + " probes)";

                player.sendOverlayMessage(
                        Component.literal("§a✓ Intake → Managed Mode " + probeStatus));
                return InteractionResult.SUCCESS;
            } else {
                player.sendOverlayMessage(Component.literal("§eAlready linked!").withStyle(ChatFormatting.YELLOW));
                return InteractionResult.FAIL;
            }
        }

        // ========================================
        // FALLBACK
        // ========================================

        player.sendOverlayMessage(Component.literal("§7Click Storage Controller first, then click probes/intakes to link").withStyle(ChatFormatting.GRAY));
        return InteractionResult.PASS;
    }

        // ========================================
        // UTILITY
        // ========================================

        private String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}