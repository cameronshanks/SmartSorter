package net.shaddii.smartsorter.util;

/**
 * Mixed onto every {@code BlockEntity} by {@code BlockEntityChangeCounterMixin}:
 * a counter bumped on every markDirty(). Vanilla containers call markDirty()
 * after every content change (setStack, removeStack, hopper transfers, screen
 * slot edits), so "counter unchanged" means "contents unchanged" and the
 * chest-contents caches can skip rescanning.
 */
public interface ChangeCounter {
    long smartsorter$getChangeCount();
}
