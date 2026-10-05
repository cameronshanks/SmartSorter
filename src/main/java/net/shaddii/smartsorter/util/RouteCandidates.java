package net.shaddii.smartsorter.util;

import net.minecraft.util.math.BlockPos;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;

/**
 * One item's entry in a controller's routing index: the probes that could
 * possibly accept that item, in the controller's own priority order, with
 * the block entity each position resolved to when the entry was built.
 * See ItemRoutingService.
 */
public final class RouteCandidates {
    public final BlockPos[] positions;
    public final OutputProbeBlockEntity[] probes;

    public RouteCandidates(BlockPos[] positions, OutputProbeBlockEntity[] probes) {
        this.positions = positions;
        this.probes = probes;
    }
}
