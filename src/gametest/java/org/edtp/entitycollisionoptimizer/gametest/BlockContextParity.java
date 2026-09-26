package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.ArrayList;
import java.util.List;

/** Tests the entity-derived contexts that actually exist in Minecraft 1.21.1. */
final class BlockContextParity {
    static int verify(GameTestHelper helper, Entity entity) {
        Entity cart = CollisionTestSupport.spawnEntity(helper, EntityType.MINECART, new Vec3(3.5, 1, 3.5));
        int count = 0;
        try {
            for (Block block : List.of(Blocks.STONE_SLAB, Blocks.RAIL, Blocks.POWDER_SNOW, Blocks.LAVA, Blocks.WATER)) {
                helper.setBlock(new BlockPos(4, 1, 3), block);
                for (Vec3 requested : List.of(new Vec3(1, -0.3, 0.1), new Vec3(0, -1, 0), new Vec3(-1, 0.2, 1))) {
                    for (Entity source : new Entity[] {null, entity, cart}) {
                        AABB box = entity.getBoundingBox();
                        List<VoxelShape> shapes = new ArrayList<>();
                        helper.getLevel().getBlockCollisions(source, box.expandTowards(requested)).forEach(shapes::add);
                        Vec3 expected = clip(box, requested, shapes);
                        Vec3 actual = Entity.collideBoundingBox(source, requested, box, helper.getLevel(), List.of());
                        CollisionTestSupport.assertVectorEqual(helper, actual, expected, "1.21.1 entity context " + block);
                        count++;
                    }
                }
            }
        } finally { cart.discard(); }
        return count;
    }

    // Independent Java axis clipping, not the intercepted Entity.collideBoundingBox method.
    static Vec3 clip(AABB box, Vec3 delta, List<VoxelShape> shapes) {
        double x = delta.x, y = delta.y, z = delta.z;
        if (y != 0) { y = Shapes.collide(Direction.Axis.Y, box, shapes, y); if (y != 0) box = box.move(0, y, 0); }
        boolean zFirst = Math.abs(x) < Math.abs(z);
        if (zFirst && z != 0) { z = Shapes.collide(Direction.Axis.Z, box, shapes, z); if (z != 0) box = box.move(0, 0, z); }
        if (x != 0) { x = Shapes.collide(Direction.Axis.X, box, shapes, x); if (!zFirst && x != 0) box = box.move(x, 0, 0); }
        if (!zFirst && z != 0) z = Shapes.collide(Direction.Axis.Z, box, shapes, z);
        return new Vec3(x, y, z);
    }
}
