# Cache invalidation points

Every cache this fork adds, and every event that invalidates it. All caches live on the server
thread; none use locks.

## Caches

| Cache | Where | Holds |
|---|---|---|
| Target position | `OutputProbeBlockEntity.getTargetPos` | Probe's target `BlockPos`, keyed by the probe's block-state object |
| Target inventory | `OutputProbeBlockEntity.getTargetInventory` | Resolved `Inventory` (single or double chest) plus the block entities behind it |
| Chest contents snapshot | `ChestSnapshot` (one per probe) | Variants present, items present, variants with room, any empty slot |
| Routing index | `ItemRoutingService` (one per controller) | `Item` → candidate probes, in priority order |
| Sorted probe list | `ProbeRegistry` | Probes sorted by priority |
| Intake retry cooldowns | `IntakeBuffer` (one per intake) | `ItemVariant` → next retry tick |
| Kept-loaded chunks | `ChunkKeeper` + `KeepLoadedState` | Owner block → chunks; chunk → refcount; ticketed set |

## Invalidation points

| Event | What gets invalidated | Hook |
|---|---|---|
| **Chest contents change** (insert, extract, hopper, player GUI, sorting) | Snapshot (its version no longer matches) | `BlockEntityChangeCounterMixin` (`BlockEntity.markDirty` HEAD); compared in `OutputProbeBlockEntity.currentSnapshot` |
| Chest changes without `markDirty` (some modded inventories) | Snapshot | `chestCacheMaxAgeTicks` max age (default 200; 0 disables it) |
| **Target chest broken / placed / replaced / joins or leaves a double chest** | Target pos, inventory, snapshot | `OutputProbeBlock.neighborUpdate` → `invalidateTargetCache` |
| Either chest half removed or its chunk unloaded | Inventory + snapshot (re-resolved on next call) | `isRemoved()` check on the cached primary and secondary block entities |
| **Probe rotated / re-linked** (block-state change) | Target pos, inventory, snapshot | Block-state identity comparison in `getTargetPos` / `getTargetInventory` |
| **Probe placed or chunk loaded** (block entity data read) | Target caches, snapshot, all routing indexes, sorted list | `OutputProbeBlockEntity.readData` → `invalidateTargetCache` + `RoutingIndexEpoch.bump()`; `ProbeRegistry.getSortedProbes` re-sorts on the epoch change |
| **Probe removed** | Target caches, routing indexes, kept-loaded chunks | `OutputProbeBlockEntity.onRemoved`; `ProbeRegistry.removeProbe` → `invalidateCache` (new sorted list) |
| Probe's cached block entity unloaded (chunk unload) | Routing index | `ItemRoutingService.resolve` sees `isRemoved()` and marks the index stale |
| **Filter edit via the controller** (mode, category, priority, Match NBT) | Routing index, sorted list, probe config cache, snapshot | `StorageControllerBlockEntity.updateChestConfig` → `ProbeRegistry.invalidateCache` + `probe.invalidateConfigCache()` (→ snapshot invalidate) + `RoutingIndexEpoch.bump()` |
| **Filter edit on the probe** (`setChestConfig`, `updateLocalConfig`, `cycleMode`) | Routing index, sorted list | → `RoutingIndexEpoch.bump()` |
| Chest config removed | Routing index, sorted list | `removeChestConfig` → `RoutingIndexEpoch.bump()` |
| **Whitelist edit** (Sorting toggle, item add/remove on the probe) | Routing index | `WhitelistNetworking` toggle handler, `OutputProbeBlock.toggleWhitelistItem` → `RoutingIndexEpoch.bump()` (`accepts()` also reads the whitelist live) |
| **Bulk edit** | Same as a controller filter edit, once per chest | `BulkEditController` sends the normal `ChestConfigUpdatePayload`, which runs `setChestConfig` / `updateChestConfig` |
| Probe linked to / unlinked from a controller | Sorted list → routing index | `ProbeRegistry.addProbe` / `removeProbe` → `invalidateCache` |
| Intake item can't be routed | (Creates a cooldown) | Expires after `stuckRetryTicks`; cleared when the buffer is cleared by hand; pruned when the map grows past 64 entries |
| **Kept-loaded block placed / chunk loaded** | Adds the owner's chunks | `ServerBlockEntityEvents.BLOCK_ENTITY_LOAD` (queued, applied at end of server tick) |
| Double-chest half or target in an unloaded neighbor chunk | Adds that chunk | `getTargetInventory` → `ChunkKeeper.requestExtraChunk` |
| **Kept-loaded block removed** | Releases its chunks (ticket removed when refcount hits 0) | Probe `onRemoved`, controller `onRemoved`, `IntakeBlock.onStateReplaced` → `ChunkKeeper.unregister` |
| Server start | Restores tickets from saved owners (or clears saved owners if the option is off) | `ServerLifecycleEvents.SERVER_STARTED` |
| Server stop | Clears in-memory keeper state | `ServerLifecycleEvents.SERVER_STOPPED` |

There is no time-based routing-index expiry: the index only depends on probe filter mode and category,
and every path that changes those is listed above. Smart Sorter's own `ProbeRegistry` still refreshes
its sorted list every 100 ticks, which also rebuilds the index.

## Test checklist

- [ ] **Placeholder chest accepts.** Put 1 of each of 27 different items in a chest (no empty slots),
      probe it in Custom or Filter mode, and feed the intake a mix of those items plus other items. The
      placeholder items keep going in until each stack is full. Other items are rejected and don't block
      the placeholders, even within the same tick.
- [ ] **Partial insert.** Fill a placeholder chest to 63/64 oak logs and feed 8 logs: 1 goes in and 7 stay
      in the intake buffer (shown on right-click), with no duplication and no loss.
- [ ] **Stuck item doesn't block.** Fill every destination for item A, then put A and B in the intake's
      source chest. B keeps flowing, A stays in the source (or shows as "Stuck" in the buffer), and spark
      shows almost no intake cost while A waits.
- [ ] **Stuck indicator and clearing.** Right-click the intake to see `Stuck: N types (M items): ...`.
      Sneak + right-click with an empty hand to get the buffered items back. Check the log for the
      one-time "has no destination with room" line.
- [ ] **Overflow target.** Set `stuckOverflowTarget` to a chest's coordinates. Stuck items move there
      within `stuckRetryTicks`.
- [ ] **Bulk edit invalidates caches.** Bulk-switch several chests from Category to Custom (and back).
      Routing follows the new modes right away, with no 1–5 second delay.
- [ ] **Chest change refreshes cache.** Take the last placeholder out of a Custom chest by hand. That
      item is no longer accepted. Put it back and it's accepted again.
- [ ] **Forced chunks restore after restart.** Set `keepChunksLoaded=true` and fly far away: the intake
      keeps sorting. Restart the server without going near the base: the log shows "Restored N
      kept-loaded chunk(s)", and sorting continues. Break the probe and confirm its chunk is released
      (`/forceload query` doesn't list ticket chunks; check with the F3 + chunk debug view or spark).
- [ ] **Cap.** Set `maxForcedChunks=1` with blocks spread over several chunks: you get one warning, and
      only one chunk stays loaded.
- [ ] **Option off is free.** With `keepChunksLoaded=false`, no `smartsorter_keep_loaded.dat` is
      written for new worlds, and any existing one is emptied on start.
- [ ] **Unloaded chunks are never force-loaded.** With the option off, leave a probe's chunk unloaded
      while the intake runs. That probe is skipped (no chunk-load spikes in spark), and items go to other
      chests or wait.
