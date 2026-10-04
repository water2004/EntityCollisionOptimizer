package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.gametest.mixin.EntityCollisionInvoker;

import java.util.List;

/**
 * Compares the mod's takeover of Entity.collideBoundingBox with the vanilla routine for
 * several block colliders and offsets.
 *
 * Minecraft 1.21.11 has no CollisionContext overload of collideBoundingBox, so the explicit
 * context matrix of the 26.3 version has no entry point here. Instead the vanilla oracle is
 * rebuilt from the unreplaced primitives: Entity.collectAllColliders is exactly the collector
 * collideBoundingBox uses internally with an empty extra list, and collideWithShapes is the
 * clipping routine the mod replaces.
 */
final class BlockContextParity {
    static int verify(GameTestHelper helper, Entity entity) {
        var cart = (AbstractMinecart) CollisionTestSupport.spawnEntity(helper, EntityType.MINECART, new Vec3(3.5, 1, 3.5));
        int count = 0;
        try {
            for (Block block : List.of(Blocks.STONE_SLAB, Blocks.RAIL, Blocks.POWDER_SNOW, Blocks.LAVA, Blocks.WATER)) {
                helper.setBlock(new BlockPos(4, 1, 3), block);
                for (Vec3 requested : List.of(new Vec3(1, -0.3, 0.1), new Vec3(0, -1, 0), new Vec3(-1, 0.2, 1))) {
                    AABB box = entity.getBoundingBox();
                    for (Entity source : new Entity[] {null, entity}) {
                        Vec3 expected = EntityCollisionInvoker.eco$collideWithShapes(requested, box,
                                Entity.collectAllColliders(source, helper.getLevel(), box.expandTowards(requested)));
                        Vec3 actual = Entity.collideBoundingBox(source, requested, box, helper.getLevel(), List.of());
                        CollisionTestSupport.assertVectorEqual(helper, actual, expected, "block collider " + block);
                        count++;
                    }
                }
            }
        } finally {
            cart.discard();
        }
        return count;
    }
}
