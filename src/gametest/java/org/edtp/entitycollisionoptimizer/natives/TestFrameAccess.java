package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Test-only injection point. Lives in the gametest source set under the same package as
 * the production natives so it can call package-private LevelCollisionFrame, and reads
 * the private LEVEL_FRAMES registry by reflection. Production code exposes no test hooks.
 */
public final class TestFrameAccess {
    private TestFrameAccess() {
    }

    /**
     * Fully tears down the level frame: detaches and materializes bodies, closes the
     * native context, and drops the frame so the next begin() bootstraps from scratch.
     */
    @SuppressWarnings("unchecked")
    public static void destroy(ServerLevel level) {
        try {
            Field registry = CollisionFrame.class.getDeclaredField("LEVEL_FRAMES");
            registry.setAccessible(true);
            Map<ServerLevel, LevelCollisionFrame> frames =
                    (Map<ServerLevel, LevelCollisionFrame>) registry.get(null);
            LevelCollisionFrame frame = frames.remove(level);
            if (frame != null) {
                frame.close();
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("TestFrameAccess cannot reach CollisionFrame registry", failure);
        }
    }
}
