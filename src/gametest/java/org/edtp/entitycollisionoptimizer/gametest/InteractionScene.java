package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Each oracle/optimized run starts with new entities at identical coordinates and restores its blocks. */
final class InteractionScene implements AutoCloseable {
    final GameTestHelper helper;
    private final int maxX, maxY, maxZ;
    private final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
    private final List<Entity> entities = new ArrayList<>();
    private final Map<GameRule<Boolean>, Boolean> rules = new LinkedHashMap<>();

    InteractionScene(GameTestHelper helper, boolean optimized) {
        this(helper, optimized, 12, 8, 8);
    }

    InteractionScene(GameTestHelper helper, boolean optimized, int maxX, int maxY, int maxZ) {
        this.helper = helper;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        CollisionFrame.end(helper.getLevel());
        // GameTest environments disable some damage rules; compare normal survival interactions.
        for (var rule : List.of(GameRules.PVP, GameRules.FALL_DAMAGE, GameRules.FIRE_DAMAGE,
                GameRules.DROWNING_DAMAGE, GameRules.FREEZE_DAMAGE)) {
            rules.put(rule, helper.getLevel().getGameRules().get(rule));
            helper.getLevel().getGameRules().set(rule, true, helper.getLevel().getServer());
        }
        // Padding can place fixtures below the generated terrain surface. Establish the whole
        // test volume explicitly; otherwise "open air" explosions can be occluded by terrain.
        for (int x = 1; x <= maxX; x++) for (int y = 1; y <= maxY; y++) for (int z = 1; z <= maxZ; z++) {
            block(x, y, z, net.minecraft.world.level.block.Blocks.AIR);
        }
    }

    void block(int x, int y, int z, BlockState state) {
        BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
        blocks.putIfAbsent(pos, helper.getLevel().getBlockState(pos));
        helper.getLevel().setBlock(pos, state, Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
    }

    void block(int x, int y, int z, Block block) { block(x, y, z, block.defaultBlockState()); }

    void floor(Block block) {
        for (int x = 1; x <= maxX; x++) for (int z = 1; z <= maxZ; z++) block(x, 0, z, block);
    }

    void seed(long seed) {
        helper.getLevel().getRandom().setSeed(seed);
        for (int i = 0; i < entities.size(); i++) entities.get(i).getRandom().setSeed(seed + i);
    }

    Entity spawn(EntityType<?> type, Vec3 position) {
        Entity entity;
        if (type == EntityTypes.ITEM) {
            Vec3 absolute = helper.absoluteVec(position);
            entity = new ItemEntity(helper.getLevel(), absolute.x, absolute.y, absolute.z, new ItemStack(Items.DIAMOND, 7));
            // ItemEntity's low-speed motion is staggered by (tickCount + id) % 4.
            // Set an equal, unused ID BEFORE registration so paired runs have the same schedule.
            entity.setId(-1_000_000_000 + entities.size());
            helper.getLevel().addFreshEntity(entity);
        } else {
            entity = type == EntityTypes.PLAYER ? CollisionTestSupport.spawnMockServerPlayer(helper, position, net.minecraft.world.level.GameType.SURVIVAL)
                    : CollisionTestSupport.spawnEntity(helper, type, position);
        }
        if (entity instanceof ItemEntity item) {
            item.setItem(new ItemStack(Items.DIAMOND, 7));
            item.setNeverPickUp();
            item.setUnlimitedLifetime();
        }
        // The helper may adjust spawn placement; these are paired physics fixtures, not spawn tests.
        entity.setPos(helper.absoluteVec(position));
        entity.setOldPosAndRot();
        entity.setNoGravity(false);
        entity.getRandom().setSeed(0xEC0262);
        entities.add(entity);
        return entity;
    }

    void track(Entity entity) { entities.add(entity); }

    static void prepareTick(Entity entity) {
        // ServerLevel normally performs this outside Entity.tick; direct-tick fixtures must do it too.
        entity.setOldPosAndRot();
        entity.tickCount++;
    }

    @Override public void close() {
        CollisionFrame.end(helper.getLevel());
        for (Entity entity : entities) {
            if (entity instanceof net.minecraft.server.level.ServerPlayer player) {
                player.connection.disconnect(net.minecraft.network.chat.Component.literal("GameTest fixture complete"));
            }
            entity.discard();
        }
        for (var entry : blocks.entrySet()) {
            helper.getLevel().removeBlockEntity(entry.getKey());
            helper.getLevel().setBlock(entry.getKey(), entry.getValue(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
        }
        rules.forEach((rule, value) -> helper.getLevel().getGameRules().set(rule, value, helper.getLevel().getServer()));
    }

    record State(Vec3 position, Vec3 velocity, int flags, double fall, float health, int items, int fire, int frozen, int fuse) {
        static State of(Entity entity) {
            int flags = (entity.onGround() ? 1 : 0) | (entity.horizontalCollision ? 2 : 0)
                    | (entity.verticalCollision ? 4 : 0) | (entity.verticalCollisionBelow ? 8 : 0)
                    | (entity.minorHorizontalCollision ? 16 : 0) | (entity.isInWater() ? 32 : 0)
                    | (entity.isRemoved() ? 64 : 0) | (entity.needsSync ? 128 : 0);
            return new State(entity.position(), entity.getDeltaMovement(), flags, entity.fallDistance,
                    entity instanceof LivingEntity living ? living.getHealth() : 0,
                    entity instanceof ItemEntity item ? item.getItem().getCount() : 0,
                    entity.getRemainingFireTicks(), entity.getTicksFrozen(),
                    entity instanceof net.minecraft.world.entity.item.PrimedTnt tnt ? tnt.getFuse() : -1);
        }

        void compare(GameTestHelper helper, State expected, String label) {
            CollisionTestSupport.assertVectorEqual(helper, position, expected.position, label + " position");
            CollisionTestSupport.assertVectorEqual(helper, velocity, expected.velocity, label + " velocity");
            helper.assertValueEqual(flags, expected.flags, label + " flags");
            helper.assertTrue(Math.abs(fall - expected.fall) <= 1E-12, label + " fall distance");
            helper.assertValueEqual(health, expected.health, label + " health");
            helper.assertValueEqual(items, expected.items, label + " item count");
            helper.assertValueEqual(fire, expected.fire, label + " fire");
            helper.assertValueEqual(frozen, expected.frozen, label + " freezing");
            helper.assertValueEqual(fuse, expected.fuse, label + " primed TNT fuse");
        }
    }
}
