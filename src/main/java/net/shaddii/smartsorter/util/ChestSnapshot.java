package net.shaddii.smartsorter.util;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * What one probe's target chest contains, so accepts() can answer with set
 * lookups instead of scanning every slot.
 *
 * "Full for variant v" means: no empty slot AND no slot holding v (same item
 * and components) below its max count. A chest of single placeholder items is
 * therefore never full for those items, only for everything else.
 *
 * Rebuilt only when the chest's change counter moves, the inventory object
 * changes (re-resolved target, chunk reload), the probe invalidates it, or the
 * optional max age passes. The sets are fastutil open-addressing sets that
 * keep their arrays across clear(), so a rebuild doesn't allocate per entry.
 */
public final class ChestSnapshot {
    private final ObjectOpenHashSet<ItemVariant> variants = new ObjectOpenHashSet<>();
    private final ReferenceOpenHashSet<Item> items = new ReferenceOpenHashSet<>();
    private final ObjectOpenHashSet<ItemVariant> variantsWithRoom = new ObjectOpenHashSet<>();
    private boolean hasEmptySlot;

    private Inventory source;
    private long version = Long.MIN_VALUE;
    private long builtAt;

    public boolean isCurrent(Inventory inv, long currentVersion, long now, int maxAge) {
        return this.source == inv
                && this.version == currentVersion
                && currentVersion != Long.MIN_VALUE
                && now >= this.builtAt
                && (maxAge <= 0 || now - this.builtAt < maxAge);
    }

    public void rebuild(Inventory inv, long now) {
        this.variants.clear();
        this.items.clear();
        this.variantsWithRoom.clear();
        this.hasEmptySlot = false;

        int perStack = inv.getMaxCountPerStack();
        int size = inv.size();
        for (int i = 0; i < size; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) {
                this.hasEmptySlot = true;
                continue;
            }
            ItemVariant variant = ItemVariant.of(stack);
            this.variants.add(variant);
            this.items.add(stack.getItem());
            if (stack.getCount() < Math.min(stack.getMaxCount(), perStack)) {
                this.variantsWithRoom.add(variant);
            }
        }

        this.source = inv;
        this.builtAt = now;
    }

    /** Set after rebuild(), read from the change counters AFTER the scan (loot generation can mark dirty). */
    public void setVersion(long version) {
        this.version = version;
    }

    public void invalidate() {
        this.source = null;
        this.version = Long.MIN_VALUE;
    }

    public boolean hasRoomFor(ItemVariant variant) {
        return this.hasEmptySlot || this.variantsWithRoom.contains(variant);
    }

    public boolean containsVariant(ItemVariant variant) {
        return this.variants.contains(variant);
    }

    public boolean containsItem(Item item) {
        return this.items.contains(item);
    }

    public boolean isEmpty() {
        return this.variants.isEmpty();
    }
}
