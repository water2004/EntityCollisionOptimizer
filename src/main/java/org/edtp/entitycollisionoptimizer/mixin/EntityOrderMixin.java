package org.edtp.entitycollisionoptimizer.mixin;

import net.minecraft.world.entity.Entity;
import org.edtp.entitycollisionoptimizer.collision.CollisionCacheState;
import org.edtp.entitycollisionoptimizer.collision.CollisionOrderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Installed only when vanilla candidate ordering was selected at startup. */
@Mixin(Entity.class)
public abstract class EntityOrderMixin implements CollisionOrderState {
    @Unique private long eco$sectionOrder;
    @Override public long eco$sectionOrder() { return eco$sectionOrder; }
    @Override public void eco$sectionOrder(long order) {
        eco$sectionOrder = order;
        ((CollisionCacheState) this).entityCollisionOptimizer$invalidateCollisionCache();
    }
}
