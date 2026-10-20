package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** Normal movement/pushing with many soft entities and a small hard subset in one section. */
final class MixedCollisionChamber extends BenchmarkScenario {
    private static final ResourceKey<Level> VOID = ResourceKey.create(Registries.DIMENSION,
            Identifier.fromNamespaceAndPath("entity_collision_optimizer", "benchmark_void"));
    private static final int PIGLINS = 600, BOATS = 20, WARMUP_TICKS = 1200;
    private final GameTestHelper helper;
    private final List<Entity> residents = new ArrayList<>(PIGLINS + BOATS);
    private ServerLevel level;
    private boolean forced;
    private int warmup;

    MixedCollisionChamber(GameTestHelper helper) { this.helper = helper; }
    @Override int durationTicks() { return 400; }
    @Override String name() { return "mixed_hard_collision"; }
    @Override String description() {
        return "piglins=600 boats=20 section=8,2,8 chamber_inner=10x10 ai=default gravity=default "
                + "invulnerable=true warmup_ticks=1200 measurement_ticks=400";
    }

    @Override void start() {
        level = helper.getLevel().getServer().getLevel(VOID);
        if (level == null) throw new IllegalStateException("Missing benchmark void dimension");
        forced = level.setChunkForced(8, 8, true);
        level.getChunk(8, 8);
        for (int x = 130; x <= 141; x++) for (int z = 130; z <= 141; z++) {
            for (int y = 32; y <= 37; y++) {
                boolean wall = y == 32 || x == 130 || x == 141 || z == 130 || z == 141;
                level.setBlockAndUpdate(new BlockPos(x, y, z), (wall ? Blocks.STONE : Blocks.AIR).defaultBlockState());
            }
        }
    }

    @Override boolean ready() {
        if (!level.isPositionEntityTicking(new BlockPos(135, 33, 135))) return false;
        if (residents.isEmpty()) {
            Random random = new Random(0xEC0262L);
            for (int index = 0; index < PIGLINS + BOATS; index++) {
                Entity entity = index < PIGLINS ? EntityType.ZOMBIFIED_PIGLIN.create(level, EntitySpawnReason.COMMAND)
                        : EntityType.OAK_BOAT.create(level, EntitySpawnReason.COMMAND);
                if (entity == null) throw new IllegalStateException("Failed to create mixed collision resident");
                entity.setUUID(new UUID(0xEC026200L, index + 1));
                entity.getRandom().setSeed(0xEC0262L + index);
                entity.setPos(135.5 + (random.nextDouble() - .5) * 2, 33, 135.5 + (random.nextDouble() - .5) * 2);
                entity.setInvulnerable(true);
                if (entity instanceof Mob mob) mob.setPersistenceRequired();
                if (!level.addFreshEntity(entity)) throw new IllegalStateException("Failed to add mixed collision resident");
                residents.add(entity);
            }
        }
        // Keep ticking the real entities during warmup, outside the measurement window.
        return warmup++ >= WARMUP_TICKS;
    }

    @Override void tick(int tick) {}
    @Override void population(int tick) {
        EntityCollisionOptimizer.LOGGER.info("ECO_MIXED_POPULATION tick={} resident={} grounded={} passengers={}",
                tick, residents.size(), residents.stream().filter(Entity::onGround).count(),
                residents.stream().filter(Entity::isPassenger).count());
    }
    @Override void verify(int tick) {
        if (residents.size() != PIGLINS + BOATS) throw new IllegalStateException("Incomplete mixed population");
        for (Entity entity : residents) {
            if (!entity.isAlive() || entity.isRemoved() || entity.tickCount < WARMUP_TICKS + tick
                    || entity.isNoGravity() || (entity instanceof Mob mob && mob.isNoAi())
                    || entity.getX() < 130 || entity.getX() > 142 || entity.getZ() < 130 || entity.getZ() > 142) {
                throw new IllegalStateException("Mixed resident left its normal ticking state: " + entity);
            }
        }
    }
    @Override String summary() { return "resident=" + residents.size() + " warmup_ticks=" + WARMUP_TICKS; }
    @Override void cleanup() {
        residents.forEach(Entity::discard);
        residents.clear();
        if (level == null) return;
        for (int x = 130; x <= 141; x++) for (int z = 130; z <= 141; z++) for (int y = 32; y <= 37; y++) {
            level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
        }
        if (forced) level.setChunkForced(8, 8, false);
        CollisionFrame.end(level);
    }
}
