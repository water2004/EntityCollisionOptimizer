package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import org.edtp.entitycollisionoptimizer.collision.VanillaEntityCollision;
import org.edtp.entitycollisionoptimizer.gametest.mixin.EntityVelocityAccessor;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Contracts between the spatial index, live entity semantics and external observers. */
final class CollisionContractParity {
    static void teams(GameTestHelper helper) {
        var level = helper.getLevel();
        var source = CollisionTestSupport.spawnZombie(helper, new Vec3(3.5, 2, 3.5));
        var pet = (TamableAnimal) CollisionTestSupport.spawnEntity(helper, EntityTypes.WOLF, new Vec3(3.7, 2, 3.5));
        var owner = CollisionTestSupport.spawnMockServerPlayer(helper, new Vec3(12.5, 2, 3.5),
                net.minecraft.world.level.GameType.SURVIVAL);
        var team = level.getScoreboard().addPlayerTeam("pet_" + UUID.randomUUID().toString().substring(0, 6));
        team.setCollisionRule(Team.CollisionRule.NEVER);
        level.getScoreboard().addPlayerToTeam(owner.getScoreboardName(), team);
        try {
            CollisionFrame.begin(level);
            ordered(helper, source, "wild pet");
            pet.setTame(true, false);
            pet.setOwner(owner);
            helper.assertTrue(pet.getTeam() == team, "pet must inherit owner's team");
            ordered(helper, source, "same-frame tame and owner assignment");
            pet.setOwner(null);
            ordered(helper, source, "same-frame owner removal");
            pet.setOwner(owner);
            ordered(helper, source, "same-frame owner restoration");
            pet.setTame(false, false);
            ordered(helper, source, "same-frame untame");
        } finally {
            source.discard(); pet.discard(); owner.discard();
            level.getScoreboard().removePlayerTeam(team);
            CollisionFrame.end(level);
        }
    }

    static void order(GameTestHelper helper) {
        var level = helper.getLevel();
        List<Entity> entities = new ArrayList<>();
        try {
            var source = CollisionTestSupport.spawnZombie(helper, new Vec3(3.5, 2, 3.5));
            entities.add(source);
            // Deliberately straddle native cells and Minecraft sections, with shuffled insertion order.
            double x = ((int) Math.floor(source.getX()) >> 4) * 16 + 16;
            double z = ((int) Math.floor(source.getZ()) >> 4) * 16 + 16;
            source.setPos(x, source.getY(), z);
            for (int i : new int[]{3, 0, 5, 1, 4, 2}) {
                var target = CollisionTestSupport.spawnZombie(helper, new Vec3(4.5, 2, 4.5));
                target.setPos(x + (i % 3 - 1) * 0.18, source.getY(), z + (i / 3 == 0 ? -0.12 : 0.12));
                entities.add(target);
            }
            CollisionFrame.begin(level);
            ordered(helper, source, "section and cell boundaries");
            Entity moved = entities.get(2);
            Vec3 original = moved.position();
            moved.setPos(original.add(4, 0, 0));
            ordered(helper, source, "cell removal must not reorder survivors");
            moved.setPos(original);
            ordered(helper, source, "same-section reentry");
            moved.setPos(original.add(32, 0, 0));
            moved.setPos(original);
            ordered(helper, source, "section reentry appends to section order");
        } finally {
            entities.forEach(Entity::discard);
            CollisionFrame.end(level);
        }
    }

    static void visibility(GameTestHelper helper) {
        var source = CollisionTestSupport.spawnZombie(helper, new Vec3(3.5, 2, 3.5));
        var target = CollisionTestSupport.spawnZombie(helper, new Vec3(3.7, 2, 3.6));
        try {
            target.push(source);
            Vec3 expected = target.getDeltaMovement();
            target.setDeltaMovement(Vec3.ZERO);
            source.setDeltaMovement(Vec3.ZERO);
            CollisionFrame.begin(helper.getLevel());
            try (var batch = CollisionFrame.collectPushable(source, null, Team.CollisionRule.ALWAYS, true)) {
                helper.assertTrue(batch.size() == 1 && batch.usesNativePush(0), "visibility test must use native batch");
                batch.applyNativeRun(source, 0, 1);
            }
            NativeImpulseParity.exact(helper, ((EntityVelocityAccessor) target).eco$rawVelocity(), expected,
                    "native push must immediately update canonical velocity, without calling its getter");
        } finally {
            source.discard(); target.discard(); CollisionFrame.end(helper.getLevel());
        }
    }

    static void ordered(GameTestHelper helper, LivingEntity source, String label) {
        List<Entity> expected = source.level().getEntities(source, source.getBoundingBox(), EntitySelector.pushableBy(source));
        List<Entity> actual = new ArrayList<>();
        var team = source.getTeam();
        try (var batch = CollisionFrame.collectPushable(source, team, VanillaEntityCollision.collisionRule(team), true)) {
            for (int i = 0; i < batch.size(); i++) actual.add(batch.target(i));
        }
        helper.assertTrue(actual.equals(expected), label + ": ordered candidates expected "
                + expected.stream().map(Entity::getId).toList() + ", got " + actual.stream().map(Entity::getId).toList());
    }
}
