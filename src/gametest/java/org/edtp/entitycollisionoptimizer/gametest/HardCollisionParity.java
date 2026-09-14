package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class HardCollisionParity {
    private HardCollisionParity() {
    }

    static void verify(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 anchor = new Vec3(0.5, 1.0, 0.5);
        Zombie source = CollisionTestSupport.spawnZombie(helper, anchor);
        List<Entity> extras = new ArrayList<>();
        extras.add(CollisionTestSupport.spawnZombie(helper, anchor.add(0.2, 0.0, 0.0)));
        extras.add(CollisionTestSupport.spawnEntity(helper, EntityTypes.OAK_BOAT, anchor.add(0.3, 0.0, 0.0)));
        extras.add(CollisionTestSupport.spawnEntity(helper, EntityTypes.OAK_BOAT, anchor.add(source.getBbWidth() + 0.4, 0.0, 0.0)));
        extras.add(CollisionTestSupport.spawnZombie(helper, anchor.add(0.0, 0.0, 3.0)));
        Entity boatSource = CollisionTestSupport.spawnEntity(helper, EntityTypes.OAK_BOAT, anchor.add(0.0, 0.0, 0.4));
        extras.add(boatSource);
        int comparisons = 0;
        try {
            CollisionFrame.begin(level);
            AABB sourceBox = source.getBoundingBox();
            AABB[] scans = {
                    sourceBox,
                    sourceBox.expandTowards(1.5, 0.0, 0.0),
                    sourceBox.expandTowards(0.0, 0.0, 2.5),
                    sourceBox.inflate(1.0E-8)
            };
            for (AABB scan : scans) {
                compare(helper, level, source, scan, "zombie " + scan);
                compare(helper, level, boatSource, scan, "boat " + scan);
                comparisons += 2;
            }
        } finally {
            source.discard();
            extras.forEach(Entity::discard);
            CollisionFrame.end(level);
        }
        EntityCollisionOptimizer.LOGGER.info(
                "ECO_HARD_COLLISION_PARITY scan_comparisons={} boats=2 zombies=2 result=passed",
                comparisons);
    }

    private static void compare(GameTestHelper helper, ServerLevel level, Entity source, AABB scan, String label) {
        List<VoxelShape> expected = level.getEntityCollisions(source, scan);
        List<VoxelShape> actual = level.getEntityCollisions(source, scan);
        helper.assertTrue(bounds(actual).equals(bounds(expected)),
                "hard collision parity " + label
                        + " vanilla=" + bounds(expected)
                        + " accelerated=" + bounds(actual));
    }

    private static Set<AABB> bounds(List<VoxelShape> shapes) {
        Set<AABB> boxes = new HashSet<>();
        for (VoxelShape shape : shapes) boxes.add(shape.bounds());
        return boxes;
    }
}
