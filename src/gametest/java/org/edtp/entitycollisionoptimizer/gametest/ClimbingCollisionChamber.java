package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;

import java.util.ArrayList;
import java.util.List;

/** Dense vanilla climbing case: every zombie is a source but no zombie is a pushable target. */
final class ClimbingCollisionChamber extends BenchmarkScenario {
    private static final int ENTITY_COUNT = 1024;
    private static final BlockPos FEET = new BlockPos(2, 1, 2);
    private final GameTestHelper helper;
    private final List<Zombie> zombies = new ArrayList<>(ENTITY_COUNT);

    ClimbingCollisionChamber(GameTestHelper helper) {
        this.helper = helper;
    }

    @Override String name() { return "dense_scaffolding"; }

    @Override String description() {
        return "entities=1024 type=zombie overlap=single_block climbable=scaffolding ai=false gravity=false";
    }

    @Override void start() {
        helper.setBlock(FEET.below(), Blocks.STONE);
        helper.setBlock(FEET, Blocks.SCAFFOLDING);
        helper.setBlock(FEET.above(), Blocks.SCAFFOLDING);
        Vec3 position = new Vec3(2.5, 1.0, 2.5);
        for (int index = 0; index < ENTITY_COUNT; index++) {
            Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, position);
            zombie.setNoAi(true);
            zombie.setNoGravity(true);
            zombie.setPermanentlyInvulnerable(true);
            zombie.setPersistenceRequired();
            zombie.setDeltaMovement(Vec3.ZERO);
            zombies.add(zombie);
        }
        verifyPopulation();
    }

    @Override void tick(int tick) {
    }

    @Override void population(int tick) {
        long climbing = zombies.stream().filter(zombie -> !zombie.isRemoved() && zombie.onClimbable()).count();
        EntityCollisionOptimizer.LOGGER.info(
                "ECO_CLIMBING_POPULATION tick={} resident={} climbing={}", tick, zombies.size(), climbing);
    }

    @Override void verify(int tick) {
        verifyPopulation();
    }

    private void verifyPopulation() {
        if (zombies.size() != ENTITY_COUNT) {
            throw new IllegalStateException("Incomplete climbing population");
        }
        for (Zombie zombie : zombies) {
            if (zombie.isRemoved() || !zombie.isAlive() || !zombie.onClimbable() || zombie.isPushable()) {
                throw new IllegalStateException("Climbing zombie left the unpushable benchmark state");
            }
        }
    }

    @Override String summary() {
        return "resident=" + zombies.size() + " climbing="
                + zombies.stream().filter(Zombie::onClimbable).count();
    }

    @Override void cleanup() {
        zombies.forEach(Entity::discard);
        zombies.clear();
    }
}
