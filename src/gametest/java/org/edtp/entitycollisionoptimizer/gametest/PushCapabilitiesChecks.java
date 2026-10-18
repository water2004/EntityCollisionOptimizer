package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.collision.VanillaMethodDetector;

/** Explicit fixture expectations: each override changes only its corresponding capability. */
final class PushCapabilitiesChecks {
    static void verify(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Zombie ordinary = new Zombie(EntityTypes.ZOMBIE, level);
        expect(helper, ordinary, true, true);
        expect(helper, new EntityPushOverride(level), false, true);
        expect(helper, new InheritedPushOverride(level), false, true);
        expect(helper, new VectorPushOverride(level), true, false);
        expect(helper, new VelocityGetterOverride(level), true, false);
        expect(helper, new VelocitySetterOverride(level), true, false);
        Zombie customDoPush = new DoPushOverride(level);
        expect(helper, customDoPush, true, true);
        helper.assertTrue(!VanillaMethodDetector.usesVanillaDoPush(customDoPush), "custom doPush stays separate");
        helper.assertTrue(VanillaMethodDetector.usesVanillaDoPush(ordinary), "inherited vanilla doPush");

        var capabilities = VanillaMethodDetector.pushCapabilities(ordinary);
        ordinary.setHealth(1);
        ordinary.noPhysics = true;
        helper.assertTrue(capabilities == VanillaMethodDetector.pushCapabilities(ordinary), "state does not recreate capabilities");
        helper.assertTrue(capabilities == VanillaMethodDetector.pushCapabilities(new Zombie(EntityTypes.ZOMBIE, level)),
                "capabilities are shared by class, not allocated per entity");
    }

    private static void expect(GameTestHelper helper, Entity entity, boolean vanillaPush, boolean deferredWrites) {
        var capabilities = VanillaMethodDetector.pushCapabilities(entity);
        helper.assertTrue(capabilities.vanillaEntityPush() == vanillaPush
                && capabilities.deferredVelocityWrites() == deferredWrites,
                "push capabilities for " + entity.getClass().getSimpleName());
    }

    private static class EntityPushOverride extends Zombie {
        EntityPushOverride(ServerLevel level) { super(EntityTypes.ZOMBIE, level); }
        @Override public void push(Entity entity) { super.push(entity); }
    }
    private static final class InheritedPushOverride extends EntityPushOverride {
        InheritedPushOverride(ServerLevel level) { super(level); }
    }
    private static final class VectorPushOverride extends Zombie {
        VectorPushOverride(ServerLevel level) { super(EntityTypes.ZOMBIE, level); }
        @Override public void push(double x, double y, double z) { super.push(x, y, z); }
    }
    private static final class VelocityGetterOverride extends Zombie {
        VelocityGetterOverride(ServerLevel level) { super(EntityTypes.ZOMBIE, level); }
        @Override public Vec3 getDeltaMovement() { return super.getDeltaMovement(); }
    }
    private static final class VelocitySetterOverride extends Zombie {
        VelocitySetterOverride(ServerLevel level) { super(EntityTypes.ZOMBIE, level); }
        @Override public void setDeltaMovement(Vec3 velocity) { super.setDeltaMovement(velocity); }
    }
    private static final class DoPushOverride extends Zombie {
        DoPushOverride(ServerLevel level) { super(EntityTypes.ZOMBIE, level); }
        @Override protected void doPush(Entity entity) { super.doPush(entity); }
    }
}
