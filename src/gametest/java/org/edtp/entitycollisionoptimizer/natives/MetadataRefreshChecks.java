package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.edtp.entitycollisionoptimizer.collision.CollisionCacheState;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Contract checks for lazy metadata publication and reentrant world callbacks. */
public final class MetadataRefreshChecks {
    private MetadataRefreshChecks() { }

    public static void verify(GameTestHelper helper) {
        nativeFieldRequests(helper);
        spatialTeams(helper);
        nestedQuery(helper);
        retirementAndOutputGrowth(helper);
        restartedTeamEpoch(helper);
        predicateRevision(helper);
    }

    private static void nativeFieldRequests(GameTestHelper helper) {
        AABB near = new AABB(0, 0, 0, 1, 1, 1);
        try (var context = FFMBackend.createContext()) {
            for (int id = 0; id < 5; id++) {
                boolean remote = id == 2;
                FFMBackend.insertEntity(context, id, remote ? near.move(160, 0, 0) : near,
                        remote ? 10 : 0, 0, 0, id == 1, id != 3);
                FFMBackend.updateEntityState(context, id, id != 4, false, true, true, id, id == 1);
                FFMBackend.updateEntityTeam(context, id, -1, 0, 0);
            }
            // Registration publishes hard eligibility without invoking any Java predicate.
            var hard = FFMBackend.queryHard(context, near, 0, true, 5);
            helper.assertTrue(hard.size() == 1 && hard.get(0) == 1, "hard eligibility at insertion");
            var miss = FFMBackend.queryPushable(context, near, 0, -1, 0, true, 1, 5);
            helper.assertTrue(miss.metadataRequired() && miss.size() == 1 && miss.get(0) == 1
                    && miss.requiredFields(0) == 2, "only the intersecting selectable derived team is stale");
            FFMBackend.invalidateEntityPushEligibilityCache(context, 1);
            miss = FFMBackend.queryPushable(context, near, 0, -1, 0, true, 1, 5);
            helper.assertTrue(miss.requiredFields(0) == 3, "independent state and team request bits");
            FFMBackend.updateEntityState(context, 1, true, false, true, true, 1, true);
            miss = FFMBackend.queryPushable(context, near, 0, -1, 0, true, 1, 5);
            helper.assertTrue(miss.requiredFields(0) == 2, "state refresh must not certify a team");
            FFMBackend.updateEntityTeam(context, 1, -1, 0, 1);
            var ready = FFMBackend.queryPushable(context, near, 0, -1, 0, true, 1, 5);
            helper.assertTrue(!ready.metadataRequired() && ready.size() == 2, "same-query retry reuses its team epoch");
            miss = FFMBackend.queryPushable(context, near, 0, -1, 0, true, 2, 5);
            helper.assertTrue(miss.metadataRequired() && miss.size() == 1 && miss.requiredFields(0) == 2,
                    "next logical query rereads only a spatial derived team");
        }
    }

    private static void spatialTeams(GameTestHelper helper) {
        try (var fixture = new Fixture(helper)) {
            Probe source = fixture.add(0);
            Probe near = fixture.add(0.1);
            Probe remote = fixture.add(1_000_000);
            helper.assertTrue(near.pushReads == 0 && remote.pushReads == 0, "insertion must not evaluate pushability");
            fixture.expect(source, near);
            int pushReads = near.pushReads;
            int teamReads = near.teamReads;
            PlayerTeam blocked = new PlayerTeam(helper.getLevel().getScoreboard(), "eco-derived-test");
            blocked.setCollisionRule(Team.CollisionRule.NEVER);
            near.team = blocked; // An owner-derived team can change without a scoreboard revision.
            fixture.expect(source);
            near.team = null;
            fixture.expect(source, near);
            helper.assertValueEqual(near.pushReads, pushReads, "team-only refresh must not evaluate pushability");
            helper.assertValueEqual(near.teamReads, teamReads + 2, "derived team is read live per spatial query");
            helper.assertTrue(remote.pushReads == 0 && remote.teamReads == 0,
                    "remote derived teams must not be read by a local query");
        }
    }

    private static void nestedQuery(GameTestHelper helper) {
        try (var fixture = new Fixture(helper)) {
            Probe source = fixture.add(0);
            Probe first = fixture.add(0.1);
            Probe second = fixture.add(0.2);
            Probe innerSource = fixture.add(64);
            Probe innerTarget = fixture.add(64.1);
            first.onTeam = () -> fixture.expect(innerSource, innerTarget);
            // Inner queries overwrite the shared QueryResult and its count, even without membership changes.
            fixture.expect(source, first, second);
            helper.assertTrue(first.teamReads == 1 && second.teamReads == 1,
                    "outer miss snapshot must survive a smaller nested result");
        }
    }

    private static void retirementAndOutputGrowth(GameTestHelper helper) {
        try (var fixture = new Fixture(helper)) {
            Probe source = fixture.add(0);
            Probe first = fixture.add(0.1);
            Probe retired = fixture.add(0.2);
            Probe innerSource = fixture.add(64);
            Probe innerTarget = fixture.add(64.1);
            Probe[] replacement = new Probe[1];
            int retiredId = fixture.ids.getNativeId(retired);
            first.onPush = () -> {
                fixture.frame.removeEntity(retired);
                replacement[0] = fixture.add(0.3);
                helper.assertValueEqual(fixture.ids.getNativeId(replacement[0]), retiredId, "fixture reuses retired ID");
                // Force arena replacement during a nested query, not just overwriting existing output.
                for (int index = 0; index < 768; index++) fixture.add(256 + index);
                fixture.expect(innerSource, innerTarget);
            };
            var result = fixture.query(source);
            fixture.expectResult(result, first, replacement[0]);
            helper.assertTrue(retired.pushReads == 0 && retired.teamReads == 0,
                    "retired requests must not be refreshed or published into a reused ID");
        }
    }

    private static void predicateRevision(GameTestHelper helper) {
        try (var fixture = new Fixture(helper)) {
            Probe source = fixture.add(0);
            Probe target = fixture.add(0.1);
            target.onPush = () -> {
                target.pushable = false;
                ((CollisionCacheState) (Object) target).entityCollisionOptimizer$invalidateCollisionCache();
                fixture.frame.invalidateEntity(target);
            };
            fixture.expect(source);
            helper.assertValueEqual(target.pushReads, 2, "predicate result predating its revision must be recomputed");
            helper.assertValueEqual(target.teamReads, 0, "non-selectable target needs no team lookup");
        }
    }

    private static void restartedTeamEpoch(GameTestHelper helper) {
        try (var fixture = new Fixture(helper)) {
            Probe source = fixture.add(0);
            Probe first = fixture.add(0.1);
            Probe second = fixture.add(0.2);
            Probe owner = fixture.add(64);
            PlayerTeam blocked = new PlayerTeam(helper.getLevel().getScoreboard(), "eco-retired-owner");
            blocked.setCollisionRule(Team.CollisionRule.NEVER);
            second.onPush = () -> {
                first.team = blocked;
                fixture.frame.removeEntity(owner);
            };
            fixture.expect(source, second);
            helper.assertValueEqual(first.teamReads, 2, "world restart must reread an earlier derived team");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final LevelCollisionFrame frame = new LevelCollisionFrame();
        final PersistentEntityIds ids;
        final Vec3 origin;

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            origin = helper.absoluteVec(new Vec3(2, 2, 2));
            try {
                // Test-only empty bootstrap, so synthetic entities cannot alter the level's actual frame.
                Field initialized = LevelCollisionFrame.class.getDeclaredField("initialized");
                initialized.setAccessible(true);
                initialized.setBoolean(frame, true);
                Field idField = LevelCollisionFrame.class.getDeclaredField("ids");
                idField.setAccessible(true);
                ids = (PersistentEntityIds) idField.get(frame);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("Cannot inject isolated metadata fixture", failure);
            }
        }

        Probe add(double offset) {
            Probe entity = new Probe(helper.getLevel());
            entity.setPos(origin.add(offset, 0, 0));
            entity.pushReads = entity.teamReads = 0;
            frame.addEntity(entity);
            return entity;
        }

        FFMBackend.QueryResult query(Probe source) {
            return frame.queryPushable(source, null, Team.CollisionRule.ALWAYS, true);
        }

        void expect(Probe source, Probe... targets) {
            expectResult(query(source), targets);
        }

        void expectResult(FFMBackend.QueryResult result, Probe... targets) {
            List<Integer> expected = new ArrayList<>(), actual = new ArrayList<>();
            for (Probe target : targets) expected.add(ids.getNativeId(target));
            for (int index = 0; index < result.size(); index++) actual.add(result.get(index));
            helper.assertTrue(!result.metadataRequired(), "metadata must converge");
            helper.assertValueEqual(actual, expected, "live candidate IDs after callback");
        }

        @Override
        public void close() {
            frame.close();
        }
    }

    private static final class Probe extends Zombie {
        int pushReads, teamReads;
        boolean pushable = true;
        PlayerTeam team;
        Runnable onPush, onTeam;

        Probe(ServerLevel level) {
            super(EntityType.ZOMBIE, level);
        }

        @Override
        public boolean isPushable() {
            pushReads++;
            boolean result = pushable;
            Runnable callback = onPush;
            onPush = null;
            if (callback != null) callback.run();
            return result;
        }

        @Override
        public PlayerTeam getTeam() {
            teamReads++;
            Runnable callback = onTeam;
            onTeam = null;
            if (callback != null) callback.run();
            return team;
        }

        @Override
        public boolean canBeCollidedWith(Entity source) {
            return true;
        }
    }
}
