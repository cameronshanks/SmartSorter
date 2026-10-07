package net.shaddii.smartsorter.intake;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.world.item.ItemStack;
import net.shaddii.smartsorter.config.SmartSorterConfig;

/**
 * An intake's multi-slot buffer plus its per-item retry cooldowns.
 *
 * Smart Sorter's own intake has a single buffer stack, and while it holds
 * anything the intake does nothing but retry it - one item with no free chest
 * stops the whole intake. Here:
 *  - partial-insert remainders go into one of several buffer entries, each
 *    with its own retry time, and every due entry is tried on each pass;
 *  - an item that couldn't be routed (from the buffer or straight from the
 *    source chest) gets a cooldown, and the intake skips it with a single map
 *    lookup until the cooldown ends, moving on to the next item instead.
 *
 * Server-thread only.
 */
public final class IntakeBuffer {

    public static final class Entry {
        public ItemStack stack;
        public long retryAt;
        /** Set once a retry found no destination; drives the stuck indicator and the one-time log. */
        public boolean stuck;

        Entry(ItemStack stack, long retryAt) {
            this.stack = stack;
            this.retryAt = retryAt;
        }
    }

    private static final int COOLDOWN_PRUNE_SIZE = 64;

    private final List<Entry> entries = new ArrayList<>(4);
    private final Object2LongOpenHashMap<ItemVariant> cooldowns = new Object2LongOpenHashMap<>();
    private long nextDue = Long.MAX_VALUE;

    public IntakeBuffer() {
        this.cooldowns.defaultReturnValue(Long.MIN_VALUE);
    }

    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

    public List<Entry> entries() {
        return this.entries;
    }

    /** Cheap gate: false until the earliest entry's retry time. */
    public boolean anyDue(long now) {
        return now >= this.nextDue;
    }

    public void recomputeNextDue() {
        long next = Long.MAX_VALUE;
        for (int i = 0; i < this.entries.size(); i++) {
            next = Math.min(next, this.entries.get(i).retryAt);
        }
        this.nextDue = next;
    }

    /** Whether {@code stack} can be stored without exceeding the configured slot count. */
    public boolean hasSlotFor(ItemStack stack) {
        if (this.entries.size() < SmartSorterConfig.intakeBufferSlots) {
            return true;
        }
        for (int i = 0; i < this.entries.size(); i++) {
            ItemStack existing = this.entries.get(i).stack;
            if (ItemStack.isSameItemSameComponents(existing, stack)
                    && existing.getCount() + stack.getCount() <= existing.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    /** Takes ownership of {@code stack}; merges into a same-item entry where it fits. Never drops items. */
    public void add(ItemStack stack, long retryAt) {
        if (stack.isEmpty()) {
            return;
        }
        for (int i = 0; i < this.entries.size() && !stack.isEmpty(); i++) {
            Entry entry = this.entries.get(i);
            if (ItemStack.isSameItemSameComponents(entry.stack, stack)) {
                int room = entry.stack.getMaxStackSize() - entry.stack.getCount();
                if (room > 0) {
                    int moved = Math.min(room, stack.getCount());
                    entry.stack.grow(moved);
                    stack.shrink(moved);
                    entry.retryAt = Math.max(entry.retryAt, retryAt);
                }
            }
        }
        if (!stack.isEmpty()) {
            this.entries.add(new Entry(stack, retryAt));
        }
        this.recomputeNextDue();
    }

    /** Removes and returns every buffered stack. */
    public List<ItemStack> drain() {
        List<ItemStack> stacks = new ArrayList<>(this.entries.size());
        for (Entry entry : this.entries) {
            if (!entry.stack.isEmpty()) {
                stacks.add(entry.stack);
            }
        }
        this.entries.clear();
        this.cooldowns.clear();
        this.nextDue = Long.MAX_VALUE;
        return stacks;
    }

    public boolean isCoolingDown(ItemVariant variant, long now) {
        return this.cooldowns.getLong(variant) > now;
    }

    public void coolDown(ItemVariant variant, long until) {
        if (this.cooldowns.size() >= COOLDOWN_PRUNE_SIZE) {
            long now = until - SmartSorterConfig.stuckRetryTicks;
            this.cooldowns.object2LongEntrySet().removeIf(e -> e.getLongValue() <= now);
        }
        this.cooldowns.put(variant, until);
    }

    public int stuckTypes() {
        int types = 0;
        for (int i = 0; i < this.entries.size(); i++) {
            if (this.entries.get(i).stuck) {
                types++;
            }
        }
        return types;
    }

    public int stuckCount() {
        int count = 0;
        for (int i = 0; i < this.entries.size(); i++) {
            Entry entry = this.entries.get(i);
            if (entry.stuck) {
                count += entry.stack.getCount();
            }
        }
        return count;
    }

    public int totalCount() {
        int count = 0;
        for (int i = 0; i < this.entries.size(); i++) {
            count += this.entries.get(i).stack.getCount();
        }
        return count;
    }

    /** For tests/debugging: the cooldown table. */
    Object2LongMap<ItemVariant> cooldowns() {
        return this.cooldowns;
    }
}
