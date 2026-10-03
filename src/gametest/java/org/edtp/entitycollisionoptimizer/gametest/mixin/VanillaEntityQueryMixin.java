package org.edtp.entitycollisionoptimizer.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.edtp.entitycollisionoptimizer.gametest.VanillaEntityQueries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Only loaded by unit GameTests, never by the mod or performance benchmarks. */
@Mixin(EntitySectionStorage.class)
public abstract class VanillaEntityQueryMixin<T extends EntityAccess> {
    @Shadow
    public abstract void forEachAccessibleNonEmptySection(
            AABB box, AbortableIterationConsumer<EntitySection<T>> consumer);

    @WrapMethod(method = "getEntities(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V")
    private void eco$vanillaQuery(AABB box, AbortableIterationConsumer<T> consumer, Operation<Void> original) {
        if (VanillaEntityQueries.active()) {
            // These are the original method's delegates; neither is intercepted by the native query mixin.
            forEachAccessibleNonEmptySection(box, section -> section.getEntities(box, consumer));
        } else {
            original.call(box, consumer);
        }
    }

    @WrapMethod(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V")
    private <U extends T> void eco$vanillaTypedQuery(EntityTypeTest<T, U> type, AABB box,
                                                   AbortableIterationConsumer<U> consumer, Operation<Void> original) {
        if (VanillaEntityQueries.active()) {
            forEachAccessibleNonEmptySection(box, section -> section.getEntities(type, box, consumer));
        } else {
            original.call(type, box, consumer);
        }
    }
}
