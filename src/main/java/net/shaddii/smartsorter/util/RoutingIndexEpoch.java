package net.shaddii.smartsorter.util;

/**
 * Global "something about some probe changed" counter. Every controller's
 * routing index (ItemRoutingService) remembers the value it was built
 * at and rebuilds when it differs.
 *
 * Bumped from the probe side for changes the controller's ProbeRegistry
 * doesn't already see: a probe block entity being loaded (placed, or its
 * chunk reloading), its local config being replaced, or its mode cycling.
 * Changes that go through the controller (updateChestConfig, add/remove
 * probe, ...) already call ProbeRegistry.invalidateCache(), which hands the
 * index a fresh sorted-probe list and triggers a rebuild that way.
 *
 * Deliberately global rather than per-controller: these events are rare
 * (player edits, chunk loads), and a spurious rebuild only costs one pass
 * over the probe list per distinct item - the same pass the unindexed code
 * made on every single insert.
 *
 * Only touched on the server thread (block entity loading and item routing
 * both run there), so no synchronization.
 */
public final class RoutingIndexEpoch {
    private static long epoch = 0L;

    private RoutingIndexEpoch() {
    }

    public static long get() {
        return epoch;
    }

    public static void bump() {
        epoch++;
    }
}
