package net.shaddii.smartsorter.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.shaddii.smartsorter.util.ChangeCounter;

/**
 * One long increment per markDirty() on any block entity - the cheapest
 * possible "did this chest change?" signal for ChestSnapshot. Reading it is a
 * field load; nothing else happens here.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityChangeCounterMixin implements ChangeCounter {

    @Unique
    private long smartsorter$changeCount;

    @Inject(method = "setChanged()V", at = @At("HEAD"))
    private void smartsorter$countChange(CallbackInfo ci) {
        this.smartsorter$changeCount++;
    }

    @Override
    public long smartsorter$getChangeCount() {
        return this.smartsorter$changeCount;
    }
}
