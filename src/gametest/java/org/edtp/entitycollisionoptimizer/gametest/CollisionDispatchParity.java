package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.gametest.mixin.LivingEntityTestInvoker;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.List;

import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.assertEntityOutcomeMatches;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.seedWhoseNextIntFails;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.seedWhoseNextIntSucceeds;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.spawnEntity;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.zeroVelocities;

final class CollisionDispatchParity {
    private CollisionDispatchParity() {
    }

    static void verify(GameTestHelper helper) {
        int scenario = 0;
        verifyPair(helper, scenario++, "living -> living", EntityTypes.ZOMBIE, EntityTypes.ZOMBIE, PairSetup.NONE);
        verifyPair(helper, scenario++, "dead living target", EntityTypes.ZOMBIE, EntityTypes.ZOMBIE,
                (source, target) -> ((LivingEntity) target).setHealth(0.0F));
        verifyPair(helper, scenario++, "living -> shulker", EntityTypes.ZOMBIE, EntityTypes.SHULKER, PairSetup.NONE);
        verifyPair(helper, scenario++, "living -> cube mob", EntityTypes.ZOMBIE, EntityTypes.SLIME,
                (source, target) -> ((Slime) target).setSize(2, false));
        verifyPair(helper, scenario++, "living -> boat", EntityTypes.ZOMBIE, EntityTypes.OAK_BOAT, PairSetup.NONE);
        verifyPair(helper, scenario++, "living -> lower boat vertical rejection",
                EntityTypes.ZOMBIE, EntityTypes.OAK_BOAT,
                (source, target) -> target.setPos(target.getX(), target.getY() - 0.3, target.getZ()));
        verifyPair(helper, scenario++, "living -> minecart", EntityTypes.ZOMBIE, EntityTypes.MINECART, PairSetup.NONE);
        verifyPair(helper, scenario++, "no-physics source", EntityTypes.ZOMBIE, EntityTypes.ZOMBIE,
                (source, target) -> source.noPhysics = true);
        verifyPair(helper, scenario++, "no-physics target", EntityTypes.ZOMBIE, EntityTypes.ZOMBIE,
                (source, target) -> target.noPhysics = true);
        verifyPair(helper, scenario++, "sleeping living target", EntityTypes.ZOMBIE, EntityTypes.ZOMBIE,
                (source, target) -> ((LivingEntity) target).startSleeping(target.blockPosition()));
        verifyPair(helper, scenario++, "sleeping living source", EntityTypes.ZOMBIE, EntityTypes.ZOMBIE,
                (source, target) -> source.startSleeping(source.blockPosition()));
        verifyPair(helper, scenario++, "living passenger relation", EntityTypes.ZOMBIE, EntityTypes.ZOMBIE,
                (source, target) -> {
                    source.startRiding(target);
                    source.setPos(target.position());
                });
        verifyPair(helper, scenario++, "minecart passenger relation",
                EntityTypes.ZOMBIE, EntityTypes.MINECART,
                (source, target) -> {
                    source.startRiding(target);
                    source.setPos(target.position());
                });
        verifyPair(helper, scenario++, "living -> unmounted horse",
                EntityTypes.ZOMBIE, EntityTypes.HORSE, PairSetup.NONE);
        verifyPair(helper, scenario++, "living -> movable creaking",
                EntityTypes.ZOMBIE, EntityTypes.CREAKING,
                (source, target) -> {
                    if (!((Creaking) target).canMove()) {
                        throw new IllegalStateException("Creaking setup must be movable");
                    }
                });
        verifyPair(helper, scenario++, "living -> standing warden",
                EntityTypes.ZOMBIE, EntityTypes.WARDEN,
                (source, target) -> target.setPose(Pose.STANDING));
        verifyPair(helper, scenario++, "living -> emerging warden exclusion",
                EntityTypes.ZOMBIE, EntityTypes.WARDEN,
                (source, target) -> target.setPose(Pose.EMERGING));
        verifyPair(helper, scenario++, "iron golem -> damaging cube mob",
                EntityTypes.IRON_GOLEM, EntityTypes.SLIME,
                (source, target) -> ((Slime) target).setSize(3, false));
        verifyPair(helper, scenario++, "iron golem -> creeper exclusion",
                EntityTypes.IRON_GOLEM, EntityTypes.CREEPER, PairSetup.NONE);
        verifyPair(helper, scenario++, "iron golem -> passive entity",
                EntityTypes.IRON_GOLEM, EntityTypes.COW, PairSetup.NONE);
        verifyPair(helper, scenario++, "iron golem failed hostile target roll",
                EntityTypes.IRON_GOLEM, EntityTypes.ZOMBIE, PairSetup.NONE, true);
        verifyPair(helper, scenario++, "parrot source", EntityTypes.PARROT, EntityTypes.ZOMBIE, PairSetup.NONE);
        verifyPair(helper, scenario++, "sulfur cube source",
                EntityTypes.SULFUR_CUBE, EntityTypes.ZOMBIE, PairSetup.NONE);
        verifyPair(helper, scenario++, "hot sulfur cube contact damage",
                EntityTypes.SULFUR_CUBE, EntityTypes.ZOMBIE,
                (source, target) -> prepareHotSulfurCube((SulfurCube) source));
        verifyPair(helper, scenario++, "active warden source", EntityTypes.WARDEN, EntityTypes.ZOMBIE,
                (source, target) -> ((Warden) source).setNoAi(false));
        verifyPair(helper, scenario++, "no-AI warden source", EntityTypes.WARDEN, EntityTypes.ZOMBIE,
                PairSetup.NONE);
        verifyPair(helper, scenario++, "warden touch cooldown already present",
                EntityTypes.WARDEN, EntityTypes.ZOMBIE,
                (source, target) -> {
                    Warden warden = (Warden) source;
                    warden.setNoAi(false);
                    warden.getBrain().setMemory(MemoryModuleType.TOUCH_COOLDOWN, Unit.INSTANCE);
                });
        verifyPair(helper, scenario++, "emerging warden source", EntityTypes.WARDEN, EntityTypes.ZOMBIE,
                (source, target) -> {
                    ((Warden) source).setNoAi(false);
                    source.setPose(Pose.EMERGING);
                });
        verifyPair(helper, scenario++, "bat pushEntities override", EntityTypes.BAT, EntityTypes.ZOMBIE,
                PairSetup.NONE);
        verifyPair(helper, scenario++, "armor stand minecart override",
                EntityTypes.ARMOR_STAND, EntityTypes.MINECART, PairSetup.NONE);
        CollisionPushParity.verifyPlayerTargets(helper, scenario);
    }

    private static void verifyPair(
            GameTestHelper helper,
            int scenarioIndex,
            String scenario,
            EntityType<? extends LivingEntity> sourceType,
            EntityType<? extends Entity> targetType,
            PairSetup setup
    ) {
        verifyPair(helper, scenarioIndex, scenario, sourceType, targetType, setup, false);
    }

    private static void verifyPair(
            GameTestHelper helper,
            int scenarioIndex,
            String scenario,
            EntityType<? extends LivingEntity> sourceType,
            EntityType<? extends Entity> targetType,
            PairSetup setup,
            boolean forceGolemRollFailure
    ) {
        ServerLevel level = helper.getLevel();
        int column = scenarioIndex % 5;
        int row = scenarioIndex / 5;
        double baseX = 0.5 + column * 6.0;
        Vec3 vanillaAnchor = new Vec3(baseX, 1.0, 14.5 + row * 5.0);
        Vec3 acceleratedAnchor = vanillaAnchor.add(3.0, 0.0, 0.0);
        LivingEntity vanillaSource = (LivingEntity) spawnEntity(helper, sourceType, vanillaAnchor);
        Entity vanillaTarget = spawnEntity(helper, targetType, vanillaAnchor.add(0.18, 0.0, 0.07));
        LivingEntity acceleratedSource = (LivingEntity) spawnEntity(helper, sourceType, acceleratedAnchor);
        Entity acceleratedTarget = spawnEntity(helper, targetType, acceleratedAnchor.add(0.18, 0.0, 0.07));

        try {
            setup.apply(vanillaSource, vanillaTarget);
            setup.apply(acceleratedSource, acceleratedTarget);
            long randomSeed = vanillaSource instanceof IronGolem
                    ? (forceGolemRollFailure
                            ? seedWhoseNextIntFails(vanillaSource, 20)
                            : seedWhoseNextIntSucceeds(vanillaSource, 20))
                    : 0x5EEDL + scenarioIndex;
            vanillaSource.getRandom().setSeed(randomSeed);
            acceleratedSource.getRandom().setSeed(randomSeed);
            zeroVelocities(List.of(vanillaSource, vanillaTarget, acceleratedSource, acceleratedTarget));

            CollisionFrame.end(level);
            VanillaReference.pushEntities(vanillaSource);

            CollisionFrame.begin(level);
            ((LivingEntityTestInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();

            assertEntityOutcomeMatches(helper, vanillaSource, acceleratedSource, "source: " + scenario);
            assertEntityOutcomeMatches(helper, vanillaTarget, acceleratedTarget, "target: " + scenario);
            if (vanillaSource instanceof Mob vanillaMob && acceleratedSource instanceof Mob acceleratedMob) {
                helper.assertValueEqual(
                        acceleratedMob.getTarget() == acceleratedTarget,
                        vanillaMob.getTarget() == vanillaTarget,
                        "mob target side effect: " + scenario
                );
            }
            if (vanillaSource instanceof Warden vanillaWarden
                    && acceleratedSource instanceof Warden acceleratedWarden) {
                helper.assertValueEqual(
                        acceleratedWarden.getAngerLevel(),
                        vanillaWarden.getAngerLevel(),
                        "warden anger side effect: " + scenario
                );
                helper.assertValueEqual(
                        acceleratedWarden.getBrain().hasMemoryValue(MemoryModuleType.TOUCH_COOLDOWN),
                        vanillaWarden.getBrain().hasMemoryValue(MemoryModuleType.TOUCH_COOLDOWN),
                        "warden touch cooldown side effect: " + scenario
                );
                assertWardenDisturbanceMatches(helper, vanillaWarden, acceleratedWarden, scenario);
            }
            if (vanillaSource instanceof SulfurCube vanillaSulfurCube
                    && acceleratedSource instanceof SulfurCube acceleratedSulfurCube
                    && vanillaSulfurCube.hasBodyItem()
                    && acceleratedSulfurCube.hasBodyItem()
                    && vanillaTarget instanceof LivingEntity vanillaLivingTarget) {
                helper.assertTrue(
                        vanillaLivingTarget.getHealth() < vanillaLivingTarget.getMaxHealth(),
                        "equipped sulfur cube setup must trigger contact damage: " + scenario
                );
            }
        } finally {
            vanillaSource.discard();
            vanillaTarget.discard();
            acceleratedSource.discard();
            acceleratedTarget.discard();
            CollisionFrame.end(level);
        }
    }

    private static void prepareHotSulfurCube(SulfurCube sulfurCube) {
        sulfurCube.setItemSlot(EquipmentSlot.BODY, Items.MAGMA_BLOCK.getDefaultInstance());
        sulfurCube.tick();
        if (!sulfurCube.hasBodyItem()) {
            throw new IllegalStateException("Hot sulfur cube setup did not equip its body item");
        }
    }

    private static void assertWardenDisturbanceMatches(
            GameTestHelper helper,
            Warden vanilla,
            Warden accelerated,
            String scenario
    ) {
        var vanillaDisturbance = vanilla.getBrain().getMemory(MemoryModuleType.DISTURBANCE_LOCATION);
        var acceleratedDisturbance = accelerated.getBrain().getMemory(MemoryModuleType.DISTURBANCE_LOCATION);
        helper.assertValueEqual(
                acceleratedDisturbance.isPresent(),
                vanillaDisturbance.isPresent(),
                "warden disturbance memory presence: " + scenario
        );
        if (vanillaDisturbance.isPresent() && acceleratedDisturbance.isPresent()) {
            BlockPos vanillaOffset = vanillaDisturbance.get().subtract(vanilla.blockPosition());
            BlockPos acceleratedOffset = acceleratedDisturbance.get().subtract(accelerated.blockPosition());
            helper.assertValueEqual(
                    acceleratedOffset,
                    vanillaOffset,
                    "warden disturbance memory position: " + scenario
            );
        }
    }

    @FunctionalInterface
    private interface PairSetup {
        PairSetup NONE = (source, target) -> {
        };

        void apply(LivingEntity source, Entity target);
    }
}
