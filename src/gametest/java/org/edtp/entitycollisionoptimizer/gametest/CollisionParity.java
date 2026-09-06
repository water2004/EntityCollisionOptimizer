package org.edtp.entitycollisionoptimizer.gametest;

import com.mojang.authlib.GameProfile;
import org.edtp.entitycollisionoptimizer.collision.VanillaEntityCollision;
import org.edtp.entitycollisionoptimizer.compat.CarpetCompatibility;
import org.edtp.entitycollisionoptimizer.config.CollisionOptimizerConfig;
import org.edtp.entitycollisionoptimizer.mixin.LivingEntityInvoker;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import org.edtp.entitycollisionoptimizer.natives.FFMBackend;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class CollisionParity {
    private CollisionParity() {
    }

    static void verifyLowDensity(GameTestHelper helper) {
        boolean originalCollisionMode = CollisionOptimizerConfig.enableEntityCollision;
        ServerLevel level = helper.getLevel();
        int originalCramming = level.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
        try {
            verifyPushabilityPredicate(helper);
            verifyLiveSpatialIndex(helper);
            level.getGameRules().set(GameRules.MAX_ENTITY_CRAMMING, 0, level.getServer());
            verifyPushOutcome(helper, false);
            verifyPushOutcome(helper, true);
            verifyCrammingOutcome(helper);
            verifyExactOverlapCrammingOutcome(helper);
            verifyPassengerCrammingOutcome(helper);
            verifyEntityDispatchMatrix(helper);
            verifySameFrameStateTransitions(helper);
            verifyRepeatedFrameParity(helper);
            verifyCarpetOwnership(helper);
        } finally {
            CollisionOptimizerConfig.enableEntityCollision = originalCollisionMode;
            level.getGameRules().set(GameRules.MAX_ENTITY_CRAMMING, originalCramming, level.getServer());
            CollisionFrame.end(level);
        }
    }

    static void verifyMediumDensity(GameTestHelper helper) {
        boolean originalCollisionMode = CollisionOptimizerConfig.enableEntityCollision;
        ServerLevel level = helper.getLevel();
        int originalCramming = level.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
        try {
            level.getGameRules().set(GameRules.MAX_ENTITY_CRAMMING, 0, level.getServer());
            verifyMediumGroup(helper, "all-rules-always", 20, false, 0.5);
            verifyMediumGroup(helper, "mixed-rules-and-state", 24, true, 8.5);
        } finally {
            CollisionOptimizerConfig.enableEntityCollision = originalCollisionMode;
            level.getGameRules().set(GameRules.MAX_ENTITY_CRAMMING, originalCramming, level.getServer());
            CollisionFrame.end(level);
        }
    }

    static void verifyConcurrentLevelIsolation(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        ServerLevel end = helper.getLevel().getServer().getLevel(Level.END);
        helper.assertTrue(nether != null, "Nether level must be available");
        helper.assertTrue(end != null, "End level must be available");

        List<LevelQueryGroup> groups = List.of(
                createLevelQueryGroup(overworld, 3, 1_000_000.5),
                createLevelQueryGroup(nether, 5, 1_001_000.5),
                createLevelQueryGroup(end, 7, 1_002_000.5)
        );
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(groups.size())) {
            List<Future<?>> futures = new ArrayList<>();
            for (LevelQueryGroup group : groups) {
                futures.add(executor.submit(() -> {
                    start.await();
                    verifyConcurrentLevelGroup(group, 300);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent level collision test was interrupted", failure);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("Concurrent level collision test failed", failure);
        } finally {
            for (LevelQueryGroup group : groups) {
                CollisionFrame.end(group.level());
            }
        }
    }

    private static LevelQueryGroup createLevelQueryGroup(
            ServerLevel level,
            int entityCount,
            double coordinate
    ) {
        CollisionFrame.end(level);
        CollisionFrame.begin(level);
        List<Zombie> entities = new ArrayList<>(entityCount);
        Vec3 position = new Vec3(coordinate, 64.0, coordinate);
        for (int index = 0; index < entityCount; index++) {
            Zombie zombie = new WorldlessPushableZombie(level);
            zombie.setPos(position);
            CollisionFrame.addEntity(zombie);
            entities.add(zombie);
        }
        return new LevelQueryGroup(level, List.copyOf(entities));
    }

    private static void verifyConcurrentLevelGroup(LevelQueryGroup group, int iterations) {
        List<Zombie> entities = group.entities();
        Set<Entity> expected = identitySet(new ArrayList<>(entities));
        for (int iteration = 0; iteration < iterations; iteration++) {
            for (Zombie source : entities) {
                FFMBackend.QueryResult spatial = CollisionFrame.query(source);
                if (spatial.size() != entities.size() - 1) {
                    throw new AssertionError(
                            group.level().dimension().identifier()
                                    + " spatial result size was " + spatial.size()
                    );
                }
                Set<Entity> observed = identitySet(List.of());
                for (int index = 0; index < spatial.size(); index++) {
                    Entity candidate = CollisionFrame.entity(source, spatial.get(index));
                    if (candidate == source || !expected.contains(candidate) || !observed.add(candidate)) {
                        throw new AssertionError(
                                group.level().dimension().identifier()
                                        + " returned a foreign or duplicate entity"
                        );
                    }
                }

                PlayerTeam sourceTeam = source.getTeam();
                FFMBackend.QueryResult pushable = CollisionFrame.queryPushable(
                        source,
                        sourceTeam,
                        VanillaEntityCollision.collisionRule(sourceTeam),
                        VanillaEntityCollision.usesVanillaDoPush(source)
                );
                int expectedPushable = entities.size() - 1;
                if (pushable.pushableCount() != expectedPushable
                        || pushable.nonPassengerCount() != expectedPushable) {
                    throw new AssertionError(
                            group.level().dimension().identifier()
                                    + " pushable counts were " + pushable.pushableCount()
                                    + "/" + pushable.nonPassengerCount()
                    );
                }
            }
        }
    }

    private record LevelQueryGroup(ServerLevel level, List<Zombie> entities) {
    }

    /** Avoids touching chunks from the worker threads used by the isolation test. */
    private static final class WorldlessPushableZombie extends Zombie {
        private WorldlessPushableZombie(ServerLevel level) {
            super(level);
        }

        @Override
        public boolean isPushable() {
            return true;
        }
    }

    private static void verifyCarpetOwnership(GameTestHelper helper) {
        if (!CarpetCompatibility.isCarpetLoaded()) {
            return;
        }

        try {
            Field collisionLimit = Class.forName("carpet.CarpetSettings")
                    .getField("maxEntityCollisions");
            int originalLimit = collisionLimit.getInt(null);
            try {
                collisionLimit.setInt(null, 1);
                helper.assertTrue(
                        CarpetCompatibility.ownsEntityCollisions(),
                        "Carpet must own collisions when maxEntityCollisions is active"
                );
                verifyPushOutcome(helper, false);
            } finally {
                collisionLimit.setInt(null, originalLimit);
                CarpetCompatibility.refreshCollisionOwnership();
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot exercise Carpet collision ownership", failure);
        }
    }

    private static void verifyPushabilityPredicate(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 anchor = new Vec3(0.5, 1.0, 0.5);
        Zombie source = spawnZombie(helper, anchor);
        Zombie allied = spawnZombie(helper, anchor);
        Zombie other = spawnZombie(helper, anchor);
        Entity unpushable = helper.spawn(EntityTypes.END_CRYSTAL, anchor);
        Entity spectator = helper.makeMockPlayer(GameType.SPECTATOR);

        Scoreboard scoreboard = level.getScoreboard();
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        PlayerTeam sourceTeam = scoreboard.addPlayerTeam("ar_s_" + suffix);
        PlayerTeam otherTeam = scoreboard.addPlayerTeam("ar_o_" + suffix);
        scoreboard.addPlayerToTeam(source.getScoreboardName(), sourceTeam);
        scoreboard.addPlayerToTeam(allied.getScoreboardName(), sourceTeam);
        scoreboard.addPlayerToTeam(other.getScoreboardName(), otherTeam);

        try {
            assertPredicateMatches(helper, source, allied, "allied/always");
            assertPredicateMatches(helper, source, other, "other/always");
            assertPredicateMatches(helper, source, unpushable, "unpushable");
            assertPredicateMatches(helper, source, spectator, "spectator");

            sourceTeam.setCollisionRule(Team.CollisionRule.NEVER);
            assertPredicateMatches(helper, source, allied, "source/never");
            sourceTeam.setCollisionRule(Team.CollisionRule.PUSH_OWN_TEAM);
            assertPredicateMatches(helper, source, allied, "source/push-own/allied");
            assertPredicateMatches(helper, source, other, "source/push-own/other");
            sourceTeam.setCollisionRule(Team.CollisionRule.PUSH_OTHER_TEAMS);
            assertPredicateMatches(helper, source, allied, "source/push-other/allied");
            assertPredicateMatches(helper, source, other, "source/push-other/other");

            sourceTeam.setCollisionRule(Team.CollisionRule.ALWAYS);
            otherTeam.setCollisionRule(Team.CollisionRule.NEVER);
            assertPredicateMatches(helper, source, other, "target/never");
            otherTeam.setCollisionRule(Team.CollisionRule.PUSH_OWN_TEAM);
            assertPredicateMatches(helper, source, other, "target/push-own/other");
            otherTeam.setCollisionRule(Team.CollisionRule.PUSH_OTHER_TEAMS);
            assertPredicateMatches(helper, source, other, "target/push-other/other");
        } finally {
            scoreboard.removePlayerTeam(sourceTeam);
            scoreboard.removePlayerTeam(otherTeam);
            source.discard();
            allied.discard();
            other.discard();
            unpushable.discard();
            spectator.discard();
        }
    }

    private static void assertPredicateMatches(
            GameTestHelper helper,
            Entity source,
            Entity target,
            String scenario
    ) {
        boolean vanilla = EntitySelector.pushableBy(source).test(target);
        PlayerTeam sourceTeam = source.getTeam();
        boolean accelerated = VanillaEntityCollision.isPushableBy(
                source,
                sourceTeam,
                VanillaEntityCollision.collisionRule(sourceTeam),
                target
        );
        helper.assertValueEqual(accelerated, vanilla, "pushableBy parity: " + scenario);
    }

    private static void verifyLiveSpatialIndex(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 anchor = new Vec3(0.5, 1.0, 0.5);
        Zombie source = spawnZombie(helper, anchor);
        Zombie overlapping = spawnZombie(helper, anchor.add(0.2, 0.0, 0.0));
        Zombie touching = spawnZombie(helper, anchor.add(source.getBbWidth(), 0.0, 0.0));
        Zombie tinyOverlap = spawnZombie(helper, anchor.add(source.getBbWidth() - 1.0E-10, 0.0, 0.0));
        Zombie vertical = spawnZombie(helper, anchor.add(0.0, source.getBbHeight() + 0.1, 0.0));
        List<Entity> observed = new ArrayList<>(List.of(overlapping, touching, tinyOverlap, vertical));

        try {
            CollisionFrame.begin(level);
            assertSpatialQueryMatches(helper, source, observed, "initial/boundaries");

            touching.setPos(source.position().add(0.1, 0.0, 0.0));
            assertSpatialQueryMatches(helper, source, observed, "moved after frame start");

            Zombie addedAfterFrameStart = spawnZombie(helper, anchor.add(0.0, 0.0, 0.2));
            observed.add(addedAfterFrameStart);
            assertSpatialQueryMatches(helper, source, observed, "added after frame start");

            Zombie largeBounds = spawnZombie(helper, anchor.add(5.0, 0.0, 5.0));
            AABB sourceBox = source.getBoundingBox();
            largeBounds.setBoundingBox(new AABB(
                    sourceBox.minX - 2.25,
                    sourceBox.minY - 0.25,
                    sourceBox.minZ - 2.25,
                    sourceBox.maxX + 2.25,
                    sourceBox.maxY + 0.25,
                    sourceBox.maxZ + 2.25
            ));
            observed.add(largeBounds);
            assertSpatialQueryMatches(helper, source, observed, "multi-cell bounds");
        } finally {
            source.discard();
            for (Entity entity : observed) {
                entity.discard();
            }
            CollisionFrame.end(level);
        }
    }

    private static void assertSpatialQueryMatches(
            GameTestHelper helper,
            Entity source,
            List<Entity> observed,
            String scenario
    ) {
        Set<Entity> observedSet = identitySet(observed);
        Set<Entity> vanilla = identitySet(source.level().getEntities(
                source,
                source.getBoundingBox(),
                observedSet::contains
        ));

        FFMBackend.QueryResult result = CollisionFrame.query(source);
        Set<Entity> accelerated = identitySet(List.of());
        for (int index = 0; index < result.size(); index++) {
            Entity candidate = CollisionFrame.entity(source, result.get(index));
            if (observedSet.contains(candidate)
                    && isInVanillaLookupSections(source.getBoundingBox(), candidate.blockPosition())) {
                accelerated.add(candidate);
            }
        }
        long trackedEntities = observed.stream().filter(CollisionFrame::contains).count();
        helper.assertTrue(
                accelerated.equals(vanilla),
                "spatial query parity: " + scenario
                        + ", vanilla=" + vanilla.size()
                        + ", accelerated=" + accelerated.size()
                        + ", nativeTracked=" + trackedEntities + "/" + observed.size()
        );
    }

    private static boolean isInVanillaLookupSections(AABB query, BlockPos position) {
        int sectionX = SectionPos.blockToSectionCoord(position.getX());
        int sectionY = SectionPos.blockToSectionCoord(position.getY());
        int sectionZ = SectionPos.blockToSectionCoord(position.getZ());
        return sectionX >= SectionPos.posToSectionCoord(query.minX - 2.0)
                && sectionX <= SectionPos.posToSectionCoord(query.maxX + 2.0)
                && sectionY >= SectionPos.posToSectionCoord(query.minY - 4.0)
                && sectionY <= SectionPos.posToSectionCoord(query.maxY)
                && sectionZ >= SectionPos.posToSectionCoord(query.minZ - 2.0)
                && sectionZ <= SectionPos.posToSectionCoord(query.maxZ + 2.0);
    }

    private static void verifyPushOutcome(GameTestHelper helper, boolean playerSource) {
        Vec3 anchor = new Vec3(playerSource ? 3.5 : 0.5, 1.0, 3.5);
        LivingEntity source = playerSource
                ? spawnPlayer(helper, anchor)
                : spawnZombie(helper, anchor);
        Zombie first = spawnZombie(helper, anchor.add(0.18, 0.0, 0.07));
        Zombie second = spawnZombie(helper, anchor.add(-0.11, 0.0, 0.21));
        Zombie teamBlocked = spawnZombie(helper, anchor.add(0.05, 0.0, -0.16));
        Zombie exactOverlap = spawnZombie(helper, anchor);
        List<LivingEntity> entities = List.of(source, first, second, teamBlocked, exactOverlap);

        Scoreboard scoreboard = helper.getLevel().getScoreboard();
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        PlayerTeam blockedTeam = scoreboard.addPlayerTeam("ar_b_" + suffix);
        blockedTeam.setCollisionRule(Team.CollisionRule.NEVER);
        scoreboard.addPlayerToTeam(teamBlocked.getScoreboardName(), blockedTeam);

        try {
            CollisionOptimizerConfig.enableEntityCollision = false;
            zeroVelocities(entities);
            ((LivingEntityInvoker) source).entityCollisionOptimizer$invokePushEntities();
            List<Vec3> vanilla = velocities(entities);

            CollisionOptimizerConfig.enableEntityCollision = true;
            zeroVelocities(entities);
            CollisionFrame.begin(helper.getLevel());
            ((LivingEntityInvoker) source).entityCollisionOptimizer$invokePushEntities();
            List<Vec3> accelerated = velocities(entities);

            for (int index = 0; index < entities.size(); index++) {
                Vec3 expected = vanilla.get(index);
                Vec3 actual = accelerated.get(index);
                helper.assertTrue(
                        expected.distanceToSqr(actual) <= 1.0E-24,
                        "push velocity parity (playerSource=" + playerSource + ", entity=" + index
                                + "): expected=" + expected + ", actual=" + actual
                );
            }
        } finally {
            scoreboard.removePlayerTeam(blockedTeam);
            for (LivingEntity entity : entities) {
                entity.discard();
            }
            CollisionFrame.end(helper.getLevel());
        }
    }

    private static void verifyEntityDispatchMatrix(GameTestHelper helper) {
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
        verifyParrotPlayerCollision(helper);
        verifyZombiePlayerCollision(helper, GameType.SURVIVAL, scenario++);
        verifyZombiePlayerCollision(helper, GameType.CREATIVE, scenario++);
        verifyZombiePlayerCollision(helper, GameType.SPECTATOR, scenario);
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
            CollisionOptimizerConfig.enableEntityCollision = false;
            ((LivingEntityInvoker) vanillaSource).entityCollisionOptimizer$invokePushEntities();

            CollisionOptimizerConfig.enableEntityCollision = true;
            CollisionFrame.begin(level);
            ((LivingEntityInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();

            assertEntityOutcomeMatches(
                    helper,
                    vanillaSource,
                    acceleratedSource,
                    "source: " + scenario
            );
            assertEntityOutcomeMatches(
                    helper,
                    vanillaTarget,
                    acceleratedTarget,
                    "target: " + scenario
            );
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

    private static void verifyParrotPlayerCollision(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 anchor = new Vec3(0.5, 1.0, 35.0);
        ServerPlayer player = spawnPlayer(helper, anchor.add(0.18, 0.0, 0.07));
        Parrot vanillaSource = (Parrot) spawnEntity(helper, EntityTypes.PARROT, anchor);
        Parrot acceleratedSource = null;
        try {
            zeroVelocities(List.of(player, vanillaSource));
            CollisionOptimizerConfig.enableEntityCollision = false;
            ((LivingEntityInvoker) vanillaSource).entityCollisionOptimizer$invokePushEntities();
            Vec3 vanillaPlayerVelocity = player.getDeltaMovement();
            Vec3 vanillaSourceVelocity = vanillaSource.getDeltaMovement();

            vanillaSource.discard();
            player.setDeltaMovement(Vec3.ZERO);
            acceleratedSource = (Parrot) spawnEntity(helper, EntityTypes.PARROT, anchor);
            acceleratedSource.setDeltaMovement(Vec3.ZERO);
            CollisionOptimizerConfig.enableEntityCollision = true;
            CollisionFrame.begin(level);
            ((LivingEntityInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();

            assertVectorEqual(
                    helper,
                    acceleratedSource.getDeltaMovement(),
                    vanillaSourceVelocity,
                    "parrot source velocity against player"
            );
            assertVectorEqual(
                    helper,
                    player.getDeltaMovement(),
                    vanillaPlayerVelocity,
                    "parrot must not push player"
            );
        } finally {
            player.discard();
            vanillaSource.discard();
            if (acceleratedSource != null) {
                acceleratedSource.discard();
            }
            CollisionFrame.end(level);
        }
    }

    private static void verifyZombiePlayerCollision(
            GameTestHelper helper,
            GameType gameType,
            int scenarioIndex
    ) {
        ServerLevel level = helper.getLevel();
        Vec3 anchor = new Vec3(1.5 + (scenarioIndex % 4) * 8.0, 1.0, 32.0);
        Vec3 playerPosition = anchor.add(0.18, 0.0, 0.07);
        Player player = spawnMockServerPlayer(helper, playerPosition, gameType);
        Zombie vanillaSource = spawnZombie(helper, anchor);
        Zombie acceleratedSource = null;
        try {
            helper.assertValueEqual(player.gameMode(), gameType, "player mode setup: " + gameType);
            zeroVelocities(List.of(player, vanillaSource));
            CollisionFrame.end(level);
            CollisionOptimizerConfig.enableEntityCollision = false;
            ((LivingEntityInvoker) vanillaSource).entityCollisionOptimizer$invokePushEntities();
            Vec3 vanillaPlayerVelocity = player.getDeltaMovement();
            Vec3 vanillaSourceVelocity = vanillaSource.getDeltaMovement();

            vanillaSource.discard();
            player.setPos(helper.absoluteVec(playerPosition));
            player.setDeltaMovement(Vec3.ZERO);
            acceleratedSource = spawnZombie(helper, anchor);
            acceleratedSource.setDeltaMovement(Vec3.ZERO);
            CollisionOptimizerConfig.enableEntityCollision = true;
            CollisionFrame.begin(level);
            ((LivingEntityInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();

            assertVectorEqual(
                    helper,
                    acceleratedSource.getDeltaMovement(),
                    vanillaSourceVelocity,
                    "zombie source velocity against " + gameType + " player"
            );
            assertVectorEqual(
                    helper,
                    player.getDeltaMovement(),
                    vanillaPlayerVelocity,
                    gameType + " player target velocity"
            );
            if (gameType == GameType.SPECTATOR) {
                assertVectorEqual(helper, vanillaSourceVelocity, Vec3.ZERO, "spectator source exclusion");
                assertVectorEqual(helper, vanillaPlayerVelocity, Vec3.ZERO, "spectator target exclusion");
            }
        } finally {
            player.discard();
            vanillaSource.discard();
            if (acceleratedSource != null) {
                acceleratedSource.discard();
            }
            CollisionFrame.end(level);
        }
    }

    private static void verifySameFrameStateTransitions(GameTestHelper helper) {
        verifySameFramePushabilityTransition(
                helper,
                "alive -> dead",
                0,
                TargetMutation.NONE,
                (level, target) -> target.setHealth(0.0F)
        );
        verifySameFramePushabilityTransition(
                helper,
                "dead -> alive",
                1,
                (level, target) -> target.setHealth(0.0F),
                (level, target) -> target.setHealth(target.getMaxHealth())
        );
        verifySameFramePushabilityTransition(
                helper,
                "ground -> climbing",
                2,
                TargetMutation.NONE,
                (level, target) -> level.setBlockAndUpdate(
                        target.blockPosition(),
                        Blocks.SCAFFOLDING.defaultBlockState()
                )
        );
        verifySameFramePushabilityTransition(
                helper,
                "climbing -> ground",
                3,
                (level, target) -> level.setBlockAndUpdate(
                        target.blockPosition(),
                        Blocks.SCAFFOLDING.defaultBlockState()
                ),
                (level, target) -> level.removeBlock(target.blockPosition(), false)
        );
        verifySameFrameTeamTransition(helper, false, 4);
        verifySameFrameTeamTransition(helper, true, 5);
        verifySameFramePlayerModeTransition(
                helper,
                GameType.SURVIVAL,
                GameType.SPECTATOR,
                6
        );
        verifySameFramePlayerModeTransition(
                helper,
                GameType.SPECTATOR,
                GameType.SURVIVAL,
                7
        );
        verifySameFrameWardenPoseTransition(helper, false, 8);
        verifySameFrameWardenPoseTransition(helper, true, 9);
        verifySameFrameHorseVehicleTransition(helper, false, 10);
        verifySameFrameHorseVehicleTransition(helper, true, 11);
    }

    private static void verifySameFramePushabilityTransition(
            GameTestHelper helper,
            String scenario,
            int scenarioIndex,
            TargetMutation initialState,
            TargetMutation transition
    ) {
        ServerLevel level = helper.getLevel();
        double baseZ = 1.0 + scenarioIndex * 3.0;
        Vec3 vanillaAnchor = new Vec3(0.75, 1.0, baseZ);
        Vec3 acceleratedAnchor = new Vec3(8.75, 1.0, baseZ);
        Zombie vanillaSource = spawnZombie(helper, vanillaAnchor);
        Zombie vanillaTarget = spawnZombie(helper, vanillaAnchor.add(0.55, 0.0, 0.0));
        Zombie acceleratedSource = spawnZombie(helper, acceleratedAnchor);
        Zombie acceleratedTarget = spawnZombie(helper, acceleratedAnchor.add(0.55, 0.0, 0.0));
        try {
            initialState.apply(level, vanillaTarget);
            initialState.apply(level, acceleratedTarget);

            CollisionFrame.end(level);
            CollisionOptimizerConfig.enableEntityCollision = false;
            ((LivingEntityInvoker) vanillaSource).entityCollisionOptimizer$invokePushEntities();
            transition.apply(level, vanillaTarget);
            zeroVelocities(List.of(vanillaSource, vanillaTarget));
            ((LivingEntityInvoker) vanillaSource).entityCollisionOptimizer$invokePushEntities();

            CollisionOptimizerConfig.enableEntityCollision = true;
            CollisionFrame.begin(level);
            ((LivingEntityInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();
            transition.apply(level, acceleratedTarget);
            zeroVelocities(List.of(acceleratedSource, acceleratedTarget));
            ((LivingEntityInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();

            assertEntityOutcomeMatches(helper, vanillaSource, acceleratedSource, scenario + " source");
            assertEntityOutcomeMatches(helper, vanillaTarget, acceleratedTarget, scenario + " target");
        } finally {
            level.removeBlock(vanillaTarget.blockPosition(), false);
            level.removeBlock(acceleratedTarget.blockPosition(), false);
            vanillaSource.discard();
            vanillaTarget.discard();
            acceleratedSource.discard();
            acceleratedTarget.discard();
            CollisionFrame.end(level);
        }
    }

    private static void verifySameFrameTeamTransition(
            GameTestHelper helper,
            boolean initiallyBlocked,
            int scenarioIndex
    ) {
        ServerLevel level = helper.getLevel();
        Scoreboard scoreboard = level.getScoreboard();
        String suffix = UUID.randomUUID().toString().substring(0, 5);
        PlayerTeam vanillaAllowed = scoreboard.addPlayerTeam("tva" + suffix);
        PlayerTeam vanillaBlocked = scoreboard.addPlayerTeam("tvb" + suffix);
        PlayerTeam acceleratedAllowed = scoreboard.addPlayerTeam("taa" + suffix);
        PlayerTeam acceleratedBlocked = scoreboard.addPlayerTeam("tab" + suffix);
        vanillaBlocked.setCollisionRule(Team.CollisionRule.NEVER);
        acceleratedBlocked.setCollisionRule(Team.CollisionRule.NEVER);

        double baseZ = 1.0 + scenarioIndex * 3.0;
        Vec3 vanillaAnchor = new Vec3(0.75, 1.0, baseZ);
        Vec3 acceleratedAnchor = new Vec3(8.75, 1.0, baseZ);
        Zombie vanillaSource = spawnZombie(helper, vanillaAnchor);
        Zombie vanillaTarget = spawnZombie(helper, vanillaAnchor.add(0.55, 0.0, 0.0));
        Zombie acceleratedSource = spawnZombie(helper, acceleratedAnchor);
        Zombie acceleratedTarget = spawnZombie(helper, acceleratedAnchor.add(0.55, 0.0, 0.0));
        try {
            scoreboard.addPlayerToTeam(
                    vanillaTarget.getScoreboardName(),
                    initiallyBlocked ? vanillaBlocked : vanillaAllowed
            );
            scoreboard.addPlayerToTeam(
                    acceleratedTarget.getScoreboardName(),
                    initiallyBlocked ? acceleratedBlocked : acceleratedAllowed
            );

            CollisionFrame.end(level);
            CollisionOptimizerConfig.enableEntityCollision = false;
            ((LivingEntityInvoker) vanillaSource).entityCollisionOptimizer$invokePushEntities();
            scoreboard.addPlayerToTeam(
                    vanillaTarget.getScoreboardName(),
                    initiallyBlocked ? vanillaAllowed : vanillaBlocked
            );
            zeroVelocities(List.of(vanillaSource, vanillaTarget));
            ((LivingEntityInvoker) vanillaSource).entityCollisionOptimizer$invokePushEntities();

            CollisionOptimizerConfig.enableEntityCollision = true;
            CollisionFrame.begin(level);
            ((LivingEntityInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();
            scoreboard.addPlayerToTeam(
                    acceleratedTarget.getScoreboardName(),
                    initiallyBlocked ? acceleratedAllowed : acceleratedBlocked
            );
            zeroVelocities(List.of(acceleratedSource, acceleratedTarget));
            ((LivingEntityInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();

            String scenario = initiallyBlocked ? "team never -> always" : "team always -> never";
            assertEntityOutcomeMatches(helper, vanillaSource, acceleratedSource, scenario + " source");
            assertEntityOutcomeMatches(helper, vanillaTarget, acceleratedTarget, scenario + " target");
        } finally {
            vanillaSource.discard();
            vanillaTarget.discard();
            acceleratedSource.discard();
            acceleratedTarget.discard();
            scoreboard.removePlayerTeam(vanillaAllowed);
            scoreboard.removePlayerTeam(vanillaBlocked);
            scoreboard.removePlayerTeam(acceleratedAllowed);
            scoreboard.removePlayerTeam(acceleratedBlocked);
            CollisionFrame.end(level);
        }
    }

    private static void verifySameFramePlayerModeTransition(
            GameTestHelper helper,
            GameType initialMode,
            GameType transitionedMode,
            int scenarioIndex
    ) {
        ServerLevel level = helper.getLevel();
        double baseZ = 1.0 + scenarioIndex * 3.0;
        Vec3 vanillaAnchor = new Vec3(0.75, 1.0, baseZ);
        Vec3 acceleratedAnchor = new Vec3(8.75, 1.0, baseZ);
        ServerPlayer player = spawnMockServerPlayer(
                helper,
                vanillaAnchor.add(0.55, 0.0, 0.0),
                initialMode
        );
        Zombie vanillaSource = spawnZombie(helper, vanillaAnchor);
        Zombie acceleratedSource = null;
        try {
            CollisionFrame.end(level);
            CollisionOptimizerConfig.enableEntityCollision = false;
            ((LivingEntityInvoker) vanillaSource).entityCollisionOptimizer$invokePushEntities();
            player.setGameMode(transitionedMode);
            helper.assertValueEqual(
                    player.gameMode(),
                    transitionedMode,
                    initialMode + " -> " + transitionedMode + " vanilla mode transition"
            );
            zeroVelocities(List.of(vanillaSource, player));
            ((LivingEntityInvoker) vanillaSource).entityCollisionOptimizer$invokePushEntities();
            Vec3 vanillaSourceVelocity = vanillaSource.getDeltaMovement();
            Vec3 vanillaPlayerVelocity = player.getDeltaMovement();

            vanillaSource.discard();
            player.setGameMode(initialMode);
            player.setPos(helper.absoluteVec(acceleratedAnchor.add(0.55, 0.0, 0.0)));
            player.setDeltaMovement(Vec3.ZERO);
            acceleratedSource = spawnZombie(helper, acceleratedAnchor);
            acceleratedSource.setDeltaMovement(Vec3.ZERO);

            CollisionOptimizerConfig.enableEntityCollision = true;
            CollisionFrame.begin(level);
            ((LivingEntityInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();
            player.setGameMode(transitionedMode);
            helper.assertValueEqual(
                    player.gameMode(),
                    transitionedMode,
                    initialMode + " -> " + transitionedMode + " accelerated mode transition"
            );
            zeroVelocities(List.of(acceleratedSource, player));
            ((LivingEntityInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();

            String scenario = initialMode + " -> " + transitionedMode;
            assertVectorEqual(
                    helper,
                    acceleratedSource.getDeltaMovement(),
                    vanillaSourceVelocity,
                    scenario + " source"
            );
            assertVectorEqual(
                    helper,
                    player.getDeltaMovement(),
                    vanillaPlayerVelocity,
                    scenario + " player target"
            );
            if (transitionedMode == GameType.SPECTATOR) {
                assertVectorEqual(helper, vanillaSourceVelocity, Vec3.ZERO, scenario + " exclusion");
                assertVectorEqual(helper, vanillaPlayerVelocity, Vec3.ZERO, scenario + " target exclusion");
            } else {
                helper.assertTrue(
                        vanillaPlayerVelocity.horizontalDistanceSqr() > 0.0,
                        scenario + " must restore player collision"
                );
            }
        } finally {
            player.discard();
            vanillaSource.discard();
            if (acceleratedSource != null) {
                acceleratedSource.discard();
            }
            CollisionFrame.end(level);
        }
    }

    private static void verifySameFrameWardenPoseTransition(
            GameTestHelper helper,
            boolean initiallyEmerging,
            int scenarioIndex
    ) {
        ServerLevel level = helper.getLevel();
        double baseZ = 1.0 + scenarioIndex * 3.0;
        Vec3 vanillaAnchor = new Vec3(0.75, 1.0, baseZ);
        Vec3 acceleratedAnchor = new Vec3(8.75, 1.0, baseZ);
        Zombie vanillaSource = spawnZombie(helper, vanillaAnchor);
        Warden vanillaTarget = (Warden) spawnEntity(
                helper,
                EntityTypes.WARDEN,
                vanillaAnchor.add(0.18, 0.0, 0.07)
        );
        Zombie acceleratedSource = spawnZombie(helper, acceleratedAnchor);
        Warden acceleratedTarget = (Warden) spawnEntity(
                helper,
                EntityTypes.WARDEN,
                acceleratedAnchor.add(0.18, 0.0, 0.07)
        );
        Pose initialPose = initiallyEmerging ? Pose.EMERGING : Pose.STANDING;
        Pose transitionedPose = initiallyEmerging ? Pose.STANDING : Pose.EMERGING;
        try {
            vanillaTarget.setPose(initialPose);
            acceleratedTarget.setPose(initialPose);

            CollisionFrame.end(level);
            CollisionOptimizerConfig.enableEntityCollision = false;
            ((LivingEntityInvoker) vanillaSource).entityCollisionOptimizer$invokePushEntities();
            vanillaTarget.setPose(transitionedPose);
            zeroVelocities(List.of(vanillaSource, vanillaTarget));
            ((LivingEntityInvoker) vanillaSource).entityCollisionOptimizer$invokePushEntities();

            CollisionOptimizerConfig.enableEntityCollision = true;
            CollisionFrame.begin(level);
            ((LivingEntityInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();
            acceleratedTarget.setPose(transitionedPose);
            zeroVelocities(List.of(acceleratedSource, acceleratedTarget));
            ((LivingEntityInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();

            String scenario = initiallyEmerging
                    ? "warden emerging -> standing"
                    : "warden standing -> emerging";
            assertEntityOutcomeMatches(helper, vanillaSource, acceleratedSource, scenario + " source");
            assertEntityOutcomeMatches(helper, vanillaTarget, acceleratedTarget, scenario + " target");
            if (transitionedPose == Pose.EMERGING) {
                assertVectorEqual(helper, vanillaTarget.getDeltaMovement(), Vec3.ZERO, scenario + " exclusion");
            } else {
                helper.assertTrue(
                        vanillaTarget.getDeltaMovement().horizontalDistanceSqr() > 0.0,
                        scenario + " must restore target collision"
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

    private static void verifySameFrameHorseVehicleTransition(
            GameTestHelper helper,
            boolean initiallyMounted,
            int scenarioIndex
    ) {
        ServerLevel level = helper.getLevel();
        double baseZ = 1.0 + scenarioIndex * 3.0;
        Vec3 vanillaAnchor = new Vec3(0.75, 1.0, baseZ);
        Vec3 acceleratedAnchor = new Vec3(8.75, 1.0, baseZ);
        Zombie vanillaSource = spawnZombie(helper, vanillaAnchor);
        Horse vanillaTarget = (Horse) spawnEntity(
                helper,
                EntityTypes.HORSE,
                vanillaAnchor.add(0.18, 0.0, 0.07)
        );
        ArmorStand vanillaPassenger = (ArmorStand) spawnEntity(
                helper,
                EntityTypes.ARMOR_STAND,
                vanillaAnchor.add(0.0, 4.0, 0.0)
        );
        Zombie acceleratedSource = spawnZombie(helper, acceleratedAnchor);
        Horse acceleratedTarget = (Horse) spawnEntity(
                helper,
                EntityTypes.HORSE,
                acceleratedAnchor.add(0.18, 0.0, 0.07)
        );
        ArmorStand acceleratedPassenger = (ArmorStand) spawnEntity(
                helper,
                EntityTypes.ARMOR_STAND,
                acceleratedAnchor.add(0.0, 4.0, 0.0)
        );
        try {
            if (initiallyMounted) {
                vanillaPassenger.startRiding(vanillaTarget, true, true);
                acceleratedPassenger.startRiding(acceleratedTarget, true, true);
            }

            CollisionFrame.end(level);
            CollisionOptimizerConfig.enableEntityCollision = false;
            ((LivingEntityInvoker) vanillaSource).entityCollisionOptimizer$invokePushEntities();
            transitionHorsePassenger(vanillaTarget, vanillaPassenger, initiallyMounted);
            zeroVelocities(List.of(vanillaSource, vanillaTarget, vanillaPassenger));
            ((LivingEntityInvoker) vanillaSource).entityCollisionOptimizer$invokePushEntities();

            CollisionOptimizerConfig.enableEntityCollision = true;
            CollisionFrame.begin(level);
            ((LivingEntityInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();
            transitionHorsePassenger(
                    acceleratedTarget,
                    acceleratedPassenger,
                    initiallyMounted
            );
            zeroVelocities(List.of(acceleratedSource, acceleratedTarget, acceleratedPassenger));
            ((LivingEntityInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();

            String scenario = initiallyMounted ? "horse mounted -> unmounted" : "horse unmounted -> mounted";
            assertEntityOutcomeMatches(helper, vanillaSource, acceleratedSource, scenario + " source");
            assertEntityOutcomeMatches(helper, vanillaTarget, acceleratedTarget, scenario + " target");
            helper.assertValueEqual(
                    acceleratedTarget.isVehicle(),
                    vanillaTarget.isVehicle(),
                    scenario + " vehicle state"
            );
            if (acceleratedTarget.isVehicle()) {
                assertVectorEqual(helper, vanillaTarget.getDeltaMovement(), Vec3.ZERO, scenario + " exclusion");
            } else {
                helper.assertTrue(
                        vanillaTarget.getDeltaMovement().horizontalDistanceSqr() > 0.0,
                        scenario + " must restore target collision"
                );
            }
        } finally {
            vanillaSource.discard();
            vanillaTarget.discard();
            vanillaPassenger.discard();
            acceleratedSource.discard();
            acceleratedTarget.discard();
            acceleratedPassenger.discard();
            CollisionFrame.end(level);
        }
    }

    private static void transitionHorsePassenger(
            Horse horse,
            ArmorStand passenger,
            boolean initiallyMounted
    ) {
        if (initiallyMounted) {
            passenger.stopRiding();
            passenger.setPos(horse.position().add(0.0, 4.0, 0.0));
        } else {
            passenger.startRiding(horse, true, true);
        }
    }

    private static void verifyRepeatedFrameParity(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Zombie> vanilla = new ArrayList<>();
        List<Zombie> accelerated = new ArrayList<>();
        try {
            for (int index = 0; index < 6; index++) {
                Vec3 offset = repeatedFrameOffset(index, 0);
                vanilla.add(spawnZombie(helper, new Vec3(18.5, 1.0, 25.0).add(offset)));
                accelerated.add(spawnZombie(helper, new Vec3(30.5, 1.0, 25.0).add(offset)));
            }

            for (int frame = 0; frame < 4; frame++) {
                for (int index = 0; index < vanilla.size(); index++) {
                    Vec3 offset = repeatedFrameOffset(index, frame);
                    vanilla.get(index).setPos(helper.absoluteVec(
                            new Vec3(18.5, 1.0, 25.0).add(offset)
                    ));
                    accelerated.get(index).setPos(helper.absoluteVec(
                            new Vec3(30.5, 1.0, 25.0).add(offset)
                    ));
                    float health = frame == 1 && index == vanilla.size() - 1
                            ? 0.0F
                            : vanilla.get(index).getMaxHealth();
                    vanilla.get(index).setHealth(health);
                    accelerated.get(index).setHealth(health);
                }
                zeroVelocities(vanilla);
                zeroVelocities(accelerated);

                CollisionFrame.end(level);
                CollisionOptimizerConfig.enableEntityCollision = false;
                for (Zombie source : vanilla) {
                    if (source.isAlive()) {
                        ((LivingEntityInvoker) source).entityCollisionOptimizer$invokePushEntities();
                    }
                }

                CollisionOptimizerConfig.enableEntityCollision = true;
                CollisionFrame.begin(level);
                for (Zombie source : accelerated) {
                    assertSpatialQueryMatches(
                            helper,
                            source,
                            new ArrayList<>(accelerated),
                            "repeated frame " + frame + " candidates"
                    );
                }
                for (Zombie source : accelerated) {
                    if (source.isAlive()) {
                        ((LivingEntityInvoker) source).entityCollisionOptimizer$invokePushEntities();
                    }
                }

                for (int index = 0; index < vanilla.size(); index++) {
                    assertEntityOutcomeMatches(
                            helper,
                            vanilla.get(index),
                            accelerated.get(index),
                            "repeated frame " + frame + " entity " + index
                    );
                }
            }
        } finally {
            for (Zombie entity : vanilla) {
                entity.discard();
            }
            for (Zombie entity : accelerated) {
                entity.discard();
            }
            CollisionFrame.end(level);
        }
    }

    private static Vec3 repeatedFrameOffset(int index, int frame) {
        double angle = index * (Math.PI * 2.0 / 6.0) + frame * 0.17;
        double radius = index == 5 && frame >= 2 ? 0.7 : 0.22 + frame * 0.02;
        return new Vec3(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius);
    }

    private static void verifyMediumGroup(
            GameTestHelper helper,
            String scenario,
            int entityCount,
            boolean mixedRulesAndState,
            double baseZ
    ) {
        ServerLevel level = helper.getLevel();
        Scoreboard scoreboard = level.getScoreboard();
        String suffix = UUID.randomUUID().toString().substring(0, 5);
        PlayerTeam[] vanillaTeams = mixedRulesAndState
                ? createCollisionTeams(scoreboard, "mv" + suffix)
                : new PlayerTeam[0];
        PlayerTeam[] acceleratedTeams = mixedRulesAndState
                ? createCollisionTeams(scoreboard, "ma" + suffix)
                : new PlayerTeam[0];
        List<Zombie> vanilla = new ArrayList<>(entityCount);
        List<Zombie> accelerated = new ArrayList<>(entityCount);
        double vanillaBaseX = 2.5;
        double acceleratedBaseX = 14.5;

        if (mixedRulesAndState) {
            helper.setBlock(
                    new BlockPos((int) Math.floor(vanillaBaseX + 0.55), 1, (int) Math.floor(baseZ)),
                    Blocks.SCAFFOLDING
            );
            helper.setBlock(
                    new BlockPos((int) Math.floor(acceleratedBaseX + 0.55), 1, (int) Math.floor(baseZ)),
                    Blocks.SCAFFOLDING
            );
        }

        try {
            for (int index = 0; index < entityCount; index++) {
                Vec3 vanillaPosition = mediumPosition(vanillaBaseX, baseZ, index, entityCount, mixedRulesAndState);
                Vec3 acceleratedPosition = mediumPosition(
                        acceleratedBaseX,
                        baseZ,
                        index,
                        entityCount,
                        mixedRulesAndState
                );
                Zombie vanillaEntity = spawnZombie(helper, vanillaPosition);
                Zombie acceleratedEntity = spawnZombie(helper, acceleratedPosition);
                vanilla.add(vanillaEntity);
                accelerated.add(acceleratedEntity);
                if (mixedRulesAndState) {
                    int teamIndex = index % vanillaTeams.length;
                    scoreboard.addPlayerToTeam(vanillaEntity.getScoreboardName(), vanillaTeams[teamIndex]);
                    scoreboard.addPlayerToTeam(acceleratedEntity.getScoreboardName(), acceleratedTeams[teamIndex]);
                }
            }

            if (mixedRulesAndState) {
                vanilla.get(entityCount - 2).setHealth(0.0F);
                accelerated.get(entityCount - 2).setHealth(0.0F);
                helper.assertTrue(
                        !vanilla.getLast().isPushable() && !accelerated.getLast().isPushable(),
                        "medium-density climbing targets must be unpushable"
                );
            }

            for (int index = 0; index < entityCount; index++) {
                long seed = 0xC0111DEL + index;
                vanilla.get(index).getRandom().setSeed(seed);
                accelerated.get(index).getRandom().setSeed(seed);
            }
            zeroVelocities(vanilla);
            zeroVelocities(accelerated);

            CollisionFrame.end(level);
            CollisionOptimizerConfig.enableEntityCollision = false;
            for (Zombie source : vanilla) {
                if (source.isAlive()) {
                    ((LivingEntityInvoker) source).entityCollisionOptimizer$invokePushEntities();
                }
            }

            CollisionOptimizerConfig.enableEntityCollision = true;
            CollisionFrame.begin(level);
            for (Zombie source : accelerated) {
                if (source.isAlive()) {
                    ((LivingEntityInvoker) source).entityCollisionOptimizer$invokePushEntities();
                }
            }

            for (int index = 0; index < entityCount; index++) {
                assertEntityOutcomeMatches(
                        helper,
                        vanilla.get(index),
                        accelerated.get(index),
                        scenario + " entity " + index
                );
            }
        } finally {
            for (Zombie entity : vanilla) {
                entity.discard();
            }
            for (Zombie entity : accelerated) {
                entity.discard();
            }
            for (PlayerTeam team : vanillaTeams) {
                scoreboard.removePlayerTeam(team);
            }
            for (PlayerTeam team : acceleratedTeams) {
                scoreboard.removePlayerTeam(team);
            }
            CollisionFrame.end(level);
        }
    }

    private static PlayerTeam[] createCollisionTeams(Scoreboard scoreboard, String prefix) {
        Team.CollisionRule[] rules = {
                Team.CollisionRule.ALWAYS,
                Team.CollisionRule.PUSH_OWN_TEAM,
                Team.CollisionRule.PUSH_OTHER_TEAMS,
                Team.CollisionRule.NEVER
        };
        PlayerTeam[] teams = new PlayerTeam[rules.length];
        for (int index = 0; index < rules.length; index++) {
            teams[index] = scoreboard.addPlayerTeam(prefix + index);
            teams[index].setCollisionRule(rules[index]);
        }
        return teams;
    }

    private static Vec3 mediumPosition(
            double baseX,
            double baseZ,
            int index,
            int entityCount,
            boolean includeClimbingTarget
    ) {
        if (includeClimbingTarget && index == entityCount - 1) {
            return new Vec3(baseX + 0.55, 1.0, baseZ);
        }
        int column = index % 5;
        int row = index / 5;
        return new Vec3(
                baseX + (column - 2) * 0.08,
                1.0,
                baseZ + (row - 2) * 0.08
        );
    }

    private static void assertEntityOutcomeMatches(
            GameTestHelper helper,
            Entity vanilla,
            Entity accelerated,
            String scenario
    ) {
        assertVectorEqual(
                helper,
                accelerated.getDeltaMovement(),
                vanilla.getDeltaMovement(),
                scenario + " delta movement"
        );
        helper.assertValueEqual(accelerated.isAlive(), vanilla.isAlive(), scenario + " alive state");
        if (vanilla instanceof LivingEntity vanillaLiving
                && accelerated instanceof LivingEntity acceleratedLiving) {
            helper.assertValueEqual(
                    acceleratedLiving.getHealth(),
                    vanillaLiving.getHealth(),
                    scenario + " health"
            );
        }
    }

    private static void assertVectorEqual(
            GameTestHelper helper,
            Vec3 actual,
            Vec3 expected,
            String scenario
    ) {
        helper.assertTrue(
                expected.distanceToSqr(actual) <= 1.0E-24,
                scenario + ": expected=" + expected + ", actual=" + actual
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Entity spawnEntity(
            GameTestHelper helper,
            EntityType<? extends Entity> type,
            Vec3 position
    ) {
        Entity entity = helper.spawn((EntityType) type, position);
        entity.setNoGravity(true);
        entity.setInvulnerable(false);
        entity.setSilent(true);
        if (entity instanceof Mob mob) {
            mob.setNoAi(true);
            mob.setPersistenceRequired();
        }
        return entity;
    }

    private static void verifyCrammingOutcome(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int previousCramming = level.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
        Vec3 vanillaAnchor = new Vec3(6.5, 1.0, 0.5);
        Vec3 acceleratedAnchor = new Vec3(9.5, 1.0, 0.5);
        Zombie vanillaSource = spawnZombie(helper, vanillaAnchor);
        Zombie acceleratedSource = spawnZombie(helper, acceleratedAnchor);
        List<Zombie> entities = new ArrayList<>(List.of(vanillaSource, acceleratedSource));
        entities.add(spawnZombie(helper, vanillaAnchor.add(0.1, 0.0, 0.0)));
        entities.add(spawnZombie(helper, vanillaAnchor.add(-0.1, 0.0, 0.0)));
        entities.add(spawnZombie(helper, acceleratedAnchor.add(0.1, 0.0, 0.0)));
        entities.add(spawnZombie(helper, acceleratedAnchor.add(-0.1, 0.0, 0.0)));

        try {
            vanillaSource.setInvulnerable(false);
            acceleratedSource.setInvulnerable(false);
            level.getGameRules().set(GameRules.MAX_ENTITY_CRAMMING, 1, level.getServer());
            long seed = seedWhoseNextIntSucceeds(vanillaSource, 4);

            CollisionOptimizerConfig.enableEntityCollision = false;
            vanillaSource.getRandom().setSeed(seed);
            ((LivingEntityInvoker) vanillaSource).entityCollisionOptimizer$invokePushEntities();

            CollisionOptimizerConfig.enableEntityCollision = true;
            acceleratedSource.getRandom().setSeed(seed);
            CollisionFrame.begin(level);
            ((LivingEntityInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();

            helper.assertValueEqual(
                    acceleratedSource.getHealth(),
                    vanillaSource.getHealth(),
                    "cramming damage parity"
            );
            helper.assertTrue(
                    vanillaSource.getHealth() < vanillaSource.getMaxHealth(),
                    "cramming parity setup must trigger damage"
            );
        } finally {
            level.getGameRules().set(GameRules.MAX_ENTITY_CRAMMING, previousCramming, level.getServer());
            for (Zombie entity : entities) {
                entity.discard();
            }
            CollisionFrame.end(level);
        }
    }

    private static void verifyPassengerCrammingOutcome(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int previousCramming = level.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
        Vec3 vanillaAnchor = new Vec3(7.5, 1.0, 8.5);
        Vec3 acceleratedAnchor = new Vec3(10.5, 1.0, 8.5);
        Zombie vanillaSource = spawnZombie(helper, vanillaAnchor);
        Zombie vanillaPassenger = spawnZombie(helper, vanillaAnchor);
        Zombie acceleratedSource = spawnZombie(helper, acceleratedAnchor);
        Zombie acceleratedPassenger = spawnZombie(helper, acceleratedAnchor);
        List<Zombie> entities = List.of(
                vanillaSource,
                vanillaPassenger,
                acceleratedSource,
                acceleratedPassenger
        );

        try {
            vanillaSource.setInvulnerable(false);
            acceleratedSource.setInvulnerable(false);
            vanillaPassenger.startRiding(vanillaSource, true, true);
            acceleratedPassenger.startRiding(acceleratedSource, true, true);
            vanillaPassenger.setPos(vanillaSource.position());
            acceleratedPassenger.setPos(acceleratedSource.position());
            helper.assertTrue(
                    vanillaPassenger.isPassenger() && acceleratedPassenger.isPassenger(),
                    "passenger cramming setup must create passengers"
            );
            level.getGameRules().set(GameRules.MAX_ENTITY_CRAMMING, 1, level.getServer());
            long seed = seedWhoseNextIntSucceeds(vanillaSource, 4);

            CollisionOptimizerConfig.enableEntityCollision = false;
            vanillaSource.getRandom().setSeed(seed);
            ((LivingEntityInvoker) vanillaSource).entityCollisionOptimizer$invokePushEntities();

            CollisionOptimizerConfig.enableEntityCollision = true;
            acceleratedSource.getRandom().setSeed(seed);
            CollisionFrame.begin(level);
            ((LivingEntityInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();

            helper.assertValueEqual(
                    acceleratedSource.getHealth(),
                    vanillaSource.getHealth(),
                    "passengers excluded from cramming parity"
            );
            helper.assertValueEqual(
                    vanillaSource.getHealth(),
                    vanillaSource.getMaxHealth(),
                    "passengers must not count toward cramming damage"
            );
        } finally {
            level.getGameRules().set(GameRules.MAX_ENTITY_CRAMMING, previousCramming, level.getServer());
            for (Zombie entity : entities) {
                entity.discard();
            }
            CollisionFrame.end(level);
        }
    }

    private static void verifyExactOverlapCrammingOutcome(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int previousCramming = level.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
        Vec3 vanillaAnchor = new Vec3(13.5, 1.0, 0.5);
        Vec3 acceleratedAnchor = new Vec3(16.5, 1.0, 0.5);
        Zombie vanillaSource = spawnZombie(helper, vanillaAnchor);
        Zombie vanillaFirst = spawnZombie(helper, vanillaAnchor);
        Zombie vanillaSecond = spawnZombie(helper, vanillaAnchor);
        Zombie acceleratedSource = spawnZombie(helper, acceleratedAnchor);
        Zombie acceleratedFirst = spawnZombie(helper, acceleratedAnchor);
        Zombie acceleratedSecond = spawnZombie(helper, acceleratedAnchor);
        List<Zombie> entities = List.of(
                vanillaSource,
                vanillaFirst,
                vanillaSecond,
                acceleratedSource,
                acceleratedFirst,
                acceleratedSecond
        );

        try {
            vanillaSource.setInvulnerable(false);
            acceleratedSource.setInvulnerable(false);
            level.getGameRules().set(GameRules.MAX_ENTITY_CRAMMING, 1, level.getServer());
            long seed = seedWhoseNextIntSucceeds(vanillaSource, 4);

            CollisionOptimizerConfig.enableEntityCollision = false;
            zeroVelocities(entities);
            vanillaSource.getRandom().setSeed(seed);
            ((LivingEntityInvoker) vanillaSource).entityCollisionOptimizer$invokePushEntities();

            CollisionOptimizerConfig.enableEntityCollision = true;
            zeroVelocities(entities);
            acceleratedSource.getRandom().setSeed(seed);
            CollisionFrame.begin(level);
            ((LivingEntityInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();

            helper.assertValueEqual(
                    acceleratedSource.getHealth(),
                    vanillaSource.getHealth(),
                    "exact-overlap no-op targets retain cramming damage"
            );
            helper.assertTrue(
                    vanillaSource.getHealth() < vanillaSource.getMaxHealth(),
                    "exact-overlap cramming setup must trigger damage"
            );
            for (Zombie entity : entities) {
                helper.assertTrue(
                        entity.getDeltaMovement().equals(Vec3.ZERO),
                        "exact-overlap vanilla push must remain a velocity no-op"
                );
            }
        } finally {
            level.getGameRules().set(GameRules.MAX_ENTITY_CRAMMING, previousCramming, level.getServer());
            for (Zombie entity : entities) {
                entity.discard();
            }
            CollisionFrame.end(level);
        }
    }

    private static long seedWhoseNextIntSucceeds(LivingEntity entity, int bound) {
        for (long seed = 0; seed < 10_000; seed++) {
            entity.getRandom().setSeed(seed);
            if (entity.getRandom().nextInt(bound) == 0) {
                return seed;
            }
        }
        throw new IllegalStateException("Could not find a deterministic random seed for bound " + bound);
    }

    private static long seedWhoseNextIntFails(LivingEntity entity, int bound) {
        for (long seed = 0; seed < 10_000; seed++) {
            entity.getRandom().setSeed(seed);
            if (entity.getRandom().nextInt(bound) != 0) {
                return seed;
            }
        }
        throw new IllegalStateException("Could not find a deterministic failing random seed for bound " + bound);
    }

    private static Zombie spawnZombie(GameTestHelper helper, Vec3 position) {
        Zombie zombie = helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, position);
        zombie.setNoGravity(true);
        zombie.setInvulnerable(true);
        zombie.setSilent(true);
        zombie.setPersistenceRequired();
        return zombie;
    }

    @SuppressWarnings("removal")
    private static ServerPlayer spawnPlayer(GameTestHelper helper, Vec3 position) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 absolutePosition = helper.absoluteVec(position);
        player.teleportTo(absolutePosition.x, absolutePosition.y, absolutePosition.z);
        return player;
    }

    private static ServerPlayer spawnMockServerPlayer(
            GameTestHelper helper,
            Vec3 position,
            GameType gameType
    ) {
        UUID playerId = UUID.randomUUID();
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(playerId, "ar-test-" + playerId.toString().substring(0, 8)),
                ClientInformation.createDefault()
        );
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(player.getGameProfile(), false);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(gameType);
        Vec3 absolutePosition = helper.absoluteVec(position);
        player.teleportTo(absolutePosition.x, absolutePosition.y, absolutePosition.z);
        return player;
    }

    private static void zeroVelocities(List<? extends Entity> entities) {
        for (Entity entity : entities) {
            entity.setDeltaMovement(Vec3.ZERO);
        }
    }

    private static List<Vec3> velocities(List<? extends Entity> entities) {
        List<Vec3> result = new ArrayList<>(entities.size());
        for (Entity entity : entities) {
            result.add(entity.getDeltaMovement());
        }
        return result;
    }

    private static Set<Entity> identitySet(List<? extends Entity> entities) {
        Set<Entity> result = Collections.newSetFromMap(new IdentityHashMap<>());
        result.addAll(entities);
        return result;
    }

    @FunctionalInterface
    private interface PairSetup {
        PairSetup NONE = (source, target) -> {
        };

        void apply(LivingEntity source, Entity target);
    }

    @FunctionalInterface
    private interface TargetMutation {
        TargetMutation NONE = (level, target) -> {
        };

        void apply(ServerLevel level, Zombie target);
    }
}
