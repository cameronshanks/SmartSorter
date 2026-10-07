package net.shaddii.smartsorter.screen;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A lightweight, interactive placeholder slot representing a free slot
 * in the storage network. It behaves visually like a regular slot but
 * has no backing inventory container.
 */
public class VirtualFreeSlot extends Slot {

    private ItemStack displayStack = ItemStack.EMPTY;

    public VirtualFreeSlot(int index, int x, int y) {
        super(new DummyInventory(), index, x, y);
    }

    @Override
    public boolean hasItem() {
        return !displayStack.isEmpty();
    }

    @Override
    public ItemStack getItem() {
        return displayStack;
    }

    @Override
    public void setByPlayer(ItemStack stack) {
        this.displayStack = stack;
        this.setChanged();
    }

    @Override
    public void setChanged() {
        // optional hook for UI refresh
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        // allow deposit interaction from player
        return true;
    }

    @Override
    public ItemStack remove(int amount) {
        if (displayStack.isEmpty()) return ItemStack.EMPTY;

        ItemStack taken = displayStack.split(amount);
        if (displayStack.getCount() <= 0) displayStack = ItemStack.EMPTY;
        return taken;
    }

    // simple static dummy inventory backing these free slots
    private static class DummyInventory implements Container {
        @Override public int getContainerSize() { return 0; }
        @Override public boolean isEmpty() { return true; }
        @Override public ItemStack getItem(int slot) { return ItemStack.EMPTY; }
        @Override public ItemStack removeItem(int slot, int amount) { return ItemStack.EMPTY; }
        @Override public ItemStack removeItemNoUpdate(int slot) { return ItemStack.EMPTY; }
        @Override public void setItem(int slot, ItemStack stack) {}
        @Override public void setChanged() {}
        @Override public boolean stillValid(net.minecraft.world.entity.player.Player player) { return true; }
        @Override public void clearContent() {}
    }
}
