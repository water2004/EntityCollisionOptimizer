package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import org.edtp.entitycollisionoptimizer.collision.VanillaMethodDetector;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import org.edtp.entitycollisionoptimizer.natives.PushBatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.identitySet;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.spawnZombie;

final class CollisionPredicateParity {
    private CollisionPredicateParity() {
    }

    static void verifyPushabilityPredicate(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 anchor = new Vec3(0.5, 1.0, 0.5);
        Zombie source = spawnZombie(helper, anchor);
        Zombie allied = spawnZombie(helper, anchor);
        Zombie other = spawnZombie(helper, anchor);
        Entity unpushable = helper.spawn(EntityType.END_CRYSTAL, anchor);
        Entity spectator = helper.makeMockPlayer(GameType.SPECTATOR);

        Scoreboard scoreboard = level.getScoreboard();
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        PlayerTeam sourceTeam = scoreboard.addPlayerTeam("ar_s_" + suffix);
        PlayerTeam otherTeam = scoreboard.addPlayerTeam("ar_o_" + suffix);
        scoreboard.addPlayerToTeam(source.getScoreboardName(), sourceTeam);
        scoreboard.addPlayerToTeam(allied.getScoreboardName(), sourceTeam);
        scoreboard.addPlayerToTeam(other.getScoreboardName(), otherTeam);

        try {
            CollisionFrame.begin(level);
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
            CollisionFrame.end(level);
            scoreboard.removePlayerTeam(sourceTeam);
            scoreboard.removePlayerTeam(otherTeam);
            source.discard();
            allied.discard();
            other.discard();
            unpushable.discard();
            spectator.discard();
        }
    }

    static void verifyLiveSpatialIndex(GameTestHelper helper) {
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

    static void assertSpatialQueryMatches(
            GameTestHelper helper,
            Entity source,
            List<Entity> observed,
            String scenario
    ) {
        Set<Entity> observedSet = identitySet(observed);
        AABB query = source.getBoundingBox();
        Set<Entity> expected = identitySet(observed.stream()
                .filter(candidate -> candidate != source
                        && !candidate.isRemoved()
                        && query.intersects(candidate.getBoundingBox())
                        && isInVanillaLookupSections(query, candidate.blockPosition()))
                .toList());
        Set<Entity> actual = identitySet(source.level().getEntities(
                source,
                query,
                observedSet::contains
        ));
        helper.assertTrue(
                actual.equals(expected),
                "spatial query parity: " + scenario
                        + ", expected=" + expected.size()
                        + ", actual=" + actual.size()
        );
    }

    private static void assertPredicateMatches(
            GameTestHelper helper,
            Entity source,
            Entity target,
            String scenario
    ) {
        boolean vanilla = EntitySelector.pushableBy(source).test(target);
        PlayerTeam sourceTeam = source.getTeam();
        boolean accelerated = false;
        LivingEntity livingSource = (LivingEntity) source;
        try (PushBatch batch = CollisionFrame.collectPushable(
                livingSource,
                sourceTeam,
                sourceTeam == null
                        ? Team.CollisionRule.ALWAYS : sourceTeam.getCollisionRule(),
                VanillaMethodDetector.usesVanillaDoPush(livingSource)
        )) {
            for (int index = 0; index < batch.size(); index++) {
                if (batch.target(index) == target) {
                    accelerated = true;
                    break;
                }
            }
        }
        helper.assertValueEqual(accelerated, vanilla, "pushableBy parity: " + scenario);
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
}
