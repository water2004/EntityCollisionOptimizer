package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.OptimizerSwitches;

import java.util.ArrayList;
import java.util.List;

/**
 * Minecarts sliding inside a crowd: the reported regression workload.
 *
 * <p>Minecarts and boats override {@code canCollideWith}, so their movement solve takes the
 * "every entity in the query box" collider path instead of the hard-collider fast path used by
 * most mobs. This scenario keeps a fixed set of carts pressed into each other and into a crowd of
 * pushable zombies, so the per-tick movement solve, the minecart entity queries and the push path
 * all run at a constant rate.
 *
 * <p>Run with {@code -PecoVanillaPaths} to replay the identical workload with every optimizer
 * subsystem switched back to the vanilla path; the two runs are directly comparable because the
 * scenario is deterministic.
 */
final class MinecartBenchmarkChamber extends BenchmarkScenario {
    private static final int MINECART_COUNT = 24;
    private static final int ZOMBIE_COUNT = 64;
    private static final double SPACING = 1.05;
    private static final double SPEED = 0.3;
    private static final int FLOOR = 1;
    private final GameTestHelper helper;
    private final List<AbstractMinecart> minecarts = new ArrayList<>(MINECART_COUNT);
    private final List<Zombie> zombies = new ArrayList<>(ZOMBIE_COUNT);
    private boolean vanillaPaths;

    MinecartBenchmarkChamber(GameTestHelper helper) {
        this.helper = helper;
    }

    @Override String name() { return "sliding_minecarts"; }

    @Override String description() {
        return "minecarts=" + MINECART_COUNT + " zombies=" + ZOMBIE_COUNT
                + " spacing=" + SPACING + " speed=" + SPEED
                + " vanilla_paths=" + vanillaPaths;
    }

    @Override void start() {
        vanillaPaths = Boolean.getBoolean("eco.benchmark.vanillaPaths");
        if (vanillaPaths) {
            OptimizerSwitches.all(false);
        }
        buildFloor();
        spawnMinecarts();
        spawnZombies();
    }

    private void buildFloor() {
        int width = 4 + (int) Math.ceil(MINECART_COUNT * SPACING) + 2;
        for (int x = 0; x <= width; x++) {
            for (int z = 0; z <= 8; z++) {
                boolean wall = x == 0 || x == width || z == 0 || z == 8;
                helper.setBlock(new BlockPos(x, FLOOR, z), wall ? Blocks.STONE : Blocks.STONE);
            }
        }
        for (int x = 1; x <= width - 1; x++) {
            helper.setBlock(new BlockPos(x, FLOOR + 1, 4), Blocks.AIR);
        }
    }

    private void spawnMinecarts() {
        for (int index = 0; index < MINECART_COUNT; index++) {
            // Two facing rows pressed into each other, so every cart collides every tick.
            double x = 2.5 + (index / 2) * SPACING;
            double z = index % 2 == 0 ? 3.5 : 4.5;
            AbstractMinecart minecart = helper.spawn(EntityType.MINECART, new Vec3(x, FLOOR + 1.0, z));
            minecart.setDeltaMovement(SPEED, 0.0, 0.0);
            minecarts.add(minecart);
        }
    }

    private void spawnZombies() {
        for (int index = 0; index < ZOMBIE_COUNT; index++) {
            double x = 1.5 + (index % 16) * 1.2;
            double z = 1.5 + (index / 16) * 1.0;
            Zombie zombie = helper.spawn(EntityType.ZOMBIE, new Vec3(x, FLOOR + 1.0, z));
            zombie.setNoAi(true);
            zombie.setNoGravity(true);
            zombie.setInvulnerable(true);
            zombie.setPersistenceRequired();
            zombie.setDeltaMovement(Vec3.ZERO);
            zombies.add(zombie);
        }
    }

    @Override void tick(int tick) {
        for (AbstractMinecart minecart : minecarts) {
            if (minecart.isRemoved()) continue;
            // Keep the workload constant: every cart keeps pressing forward.
            minecart.setDeltaMovement(SPEED, minecart.getDeltaMovement().y, 0.0);
        }
    }

    @Override void population(int tick) {
        long resident = minecarts.stream().filter(cart -> !cart.isRemoved()).count();
        long passengers = minecarts.stream().mapToLong(cart -> cart.getPassengers().size()).sum();
        EntityCollisionOptimizer.LOGGER.info(
                "ECO_MINECART_POPULATION tick={} minecarts={} passengers={} zombies={}",
                tick, resident, passengers, zombies.stream().filter(z -> !z.isRemoved()).count());
    }

    @Override void verify(int tick) {
        long resident = minecarts.stream().filter(cart -> !cart.isRemoved()).count();
        if (resident != MINECART_COUNT) {
            throw new IllegalStateException("Minecart population changed: " + resident);
        }
        if (zombies.stream().filter(Entity::isRemoved).count() != 0) {
            throw new IllegalStateException("Zombie population changed");
        }
    }

    @Override String summary() {
        return "minecarts=" + minecarts.size()
                + " passengers=" + minecarts.stream().mapToLong(cart -> cart.getPassengers().size()).sum()
                + " vanilla_paths=" + vanillaPaths;
    }

    @Override void cleanup() {
        minecarts.forEach(Entity::discard);
        minecarts.clear();
        zombies.forEach(Entity::discard);
        zombies.clear();
        if (vanillaPaths) {
            OptimizerSwitches.all(true);
            vanillaPaths = false;
        }
    }
}
