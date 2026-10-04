package org.edtp.entitycollisionoptimizer.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Name source only, no behaviour.
 *
 * <p>{@link org.edtp.entitycollisionoptimizer.collision.VanillaMethodDetector} has to look these
 * members up reflectively to detect overrides, and reflection needs the <em>runtime</em> names,
 * which are not the names written in this module's source on intermediary-based Minecraft
 * versions (1.21.11 and earlier). Loom rewrites {@code @Invoker} values to the runtime namespace
 * exactly like {@code @Accessor} values, so reading them back yields the correct names on every
 * version without hardcoding a namespace. The invoker method names are our own and are therefore
 * stable, which makes the pairing unambiguous.
 */
@Mixin(Entity.class)
public interface EntityMemberNames {
    @Invoker("getTeam")
    PlayerTeam eco$invokeGetTeam();

    @Invoker("canBeCollidedWith")
    boolean eco$invokeCanBeCollidedWith(Entity entity);

    @Invoker("canCollideWith")
    boolean eco$invokeCanCollideWith(Entity entity);

    @Invoker("push")
    void eco$invokePushEntity(Entity entity);

    @Invoker("push")
    void eco$invokePushVector(double x, double y, double z);

    @Invoker("getDeltaMovement")
    Vec3 eco$invokeGetDeltaMovement();

    @Invoker("setDeltaMovement")
    void eco$invokeSetDeltaMovement(Vec3 movement);
}
