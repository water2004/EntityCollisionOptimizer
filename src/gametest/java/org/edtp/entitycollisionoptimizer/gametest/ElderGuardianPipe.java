package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Real one-block bore: original elder-guardian dimensions may overlap the walls. */
final class ElderGuardianPipe extends BenchmarkScenario {
    private static final int SPAWN_Y = -50, BOTTOM_Y = -64, SPAWN_PER_TICK = 50;
    private static final int DRAIN_TICKS = 100;
    private final GameTestHelper helper;
    private final List<Resident> residents = new ArrayList<>();
    private final Random random = new Random(0xEC0262L);
    private static final ResourceKey<Level> VOID = ResourceKey.create(Registries.DIMENSION,
            Identifier.fromNamespaceAndPath("entity_collision_optimizer", "benchmark_void"));
    private static final ChunkPos CENTER = new ChunkPos(0, 0);
    private static final int LOAD_RADIUS = 4;
    private ServerLevel level;
    private boolean forced;
    private int spawned, removed, enteredVoid;
    private double minimumY = SPAWN_Y;

    private static final class Resident {
        final LivingEntity entity;
        boolean below;
        Resident(LivingEntity entity) { this.entity = entity; }
    }

    ElderGuardianPipe(GameTestHelper helper) { this.helper = helper; }
    @Override String name() { return "elder_guardian_void_pipe"; }
    @Override String description() {
        return "world=benchmark_void generator=empty pipe_inner=1x1 spawn_y=-50 bottom_y=-64 spawn_per_tick=50 total_spawned=10000 "
                + "walls=stone floor=none water=none health=default ai=default gravity=default players=0 warmup_ticks=0";
    }

    @Override void start() {
        level = helper.getLevel().getServer().getLevel(VOID);
        if (level == null || !(level.getChunkSource().getGenerator() instanceof FlatLevelSource generator)
                || !generator.settings().getLayersInfo().isEmpty()) {
            throw new IllegalStateException("Benchmark requires an empty-layer void dimension");
        }
        level.getChunkSource().addTicketAndLoadWithRadius(TicketType.FORCED, CENTER, LOAD_RADIUS);
        forced = true;
        level.getChunk(0, 0);
        // Absolute heights, independent of the GameTest structure's placement height.
        for (int y = BOTTOM_Y; y <= SPAWN_Y + 3; y++) {
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                var block = x == 0 && z == 0 ? Blocks.AIR : Blocks.STONE;
                level.setBlockAndUpdate(new BlockPos(x, y, z),
                        block.defaultBlockState());
            }
        }
        if (!level.getBlockState(new BlockPos(0, BOTTOM_Y, 0)).isAir()) {
            throw new IllegalStateException("Pipe bottom is obstructed");
        }
    }

    @Override boolean ready() {
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            if (!level.isPositionEntityTicking(new BlockPos(x * 16, SPAWN_Y, z * 16))) return false;
        }
        return true;
    }

    private void observe() {
        var iterator = residents.iterator();
        while (iterator.hasNext()) {
            Resident resident = iterator.next();
            double y = resident.entity.getY();
            minimumY = Math.min(minimumY, y);
            if (y < BOTTOM_Y && !resident.below) { resident.below = true; enteredVoid++; }
            if (resident.entity.isRemoved()) { removed++; iterator.remove(); }
        }
    }

    @Override void tick(int tick) {
        observe();
        for (int i = 0; i < SPAWN_PER_TICK; i++) {
            var entity = EntityTypes.ELDER_GUARDIAN.create(level, EntitySpawnReason.COMMAND);
            if (entity == null) throw new IllegalStateException("Failed to create elder guardian");
            entity.setPos(0.5 + (random.nextDouble() - 0.5) * 0.04, SPAWN_Y,
                    0.5 + (random.nextDouble() - 0.5) * 0.04);
            if (!level.addFreshEntity(entity)) throw new IllegalStateException("Failed to add elder guardian");
            if (entity.isNoGravity() || entity.isNoAi() || entity.getHealth() != entity.getMaxHealth()) {
                throw new IllegalStateException("Guardian must retain default health, AI and gravity");
            }
            if (spawned == 0) EntityCollisionOptimizer.LOGGER.info(
                    "ECO_PIPE_ENTITY width={} height={} spawn_y={} health={}",
                    entity.getBbWidth(), entity.getBbHeight(), entity.getY(), entity.getHealth());
            residents.add(new Resident(entity));
            spawned++;
        }
    }

    @Override void population(int tick) {
        observe();
        int alive = 0, grounded = 0;
        for (Resident resident : residents) {
            if (resident.entity.isAlive()) alive++;
            if (resident.entity.onGround()) grounded++;
        }
        EntityCollisionOptimizer.LOGGER.info(
                "ECO_PIPE_POPULATION tick={} spawned={} resident={} alive={} grounded={} removed={} entered_void={} min_y={}",
                tick, spawned, residents.size(), alive, grounded, removed, enteredVoid, minimumY);
    }

    @Override void verify(int ticks) {
        if (spawned != ticks * SPAWN_PER_TICK) throw new IllegalStateException("Incomplete guardian spawning");
        // Report physical obstruction rather than changing entity dimensions to manufacture a fall.
    }
    @Override int drainTicks() { return DRAIN_TICKS; }
    @Override void drain(int tick) {
        observe();
        if (tick % 20 == 0) population(200 + tick);
    }
    @Override void verifyDrain(int ticks) {
        observe();
        if (removed == 0 || residents.size() >= spawned) {
            throw new IllegalStateException("Void drain did not remove guardians: removed=" + removed
                    + " resident=" + residents.size() + " spawned=" + spawned);
        }
    }
    @Override String summary() {
        return "spawned=" + spawned + " removed=" + removed + " entered_void=" + enteredVoid + " min_y=" + minimumY;
    }
    @Override void cleanup() {
        residents.forEach(resident -> resident.entity.discard());
        residents.clear();
        if (level != null) {
            CollisionFrame.end(level);
            if (forced) level.getChunkSource().removeTicketWithRadius(TicketType.FORCED, CENTER, LOAD_RADIUS);
        }
        forced = false;
    }
}
