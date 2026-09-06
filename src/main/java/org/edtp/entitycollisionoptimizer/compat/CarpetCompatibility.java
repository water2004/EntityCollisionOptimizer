package org.edtp.entitycollisionoptimizer.compat;

import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;

/**
 * Gives Carpet exclusive ownership of LivingEntity.pushEntities whenever its
 * maxEntityCollisions rule is active.
 */
public final class CarpetCompatibility {
    private static final Field MAX_ENTITY_COLLISIONS = findCollisionLimitField();
    private static volatile boolean carpetOwnsEntityCollisions;

    private CarpetCompatibility() {
    }

    public static boolean refreshCollisionOwnership() {
        int collisionLimit = collisionLimit();
        boolean previous = carpetOwnsEntityCollisions;
        carpetOwnsEntityCollisions = collisionLimit > 0;
        if (previous != carpetOwnsEntityCollisions) {
            if (carpetOwnsEntityCollisions) {
                EntityCollisionOptimizer.LOGGER.info(
                        "Carpet maxEntityCollisions={} is active; Carpet owns entity collisions",
                        collisionLimit
                );
            } else {
                EntityCollisionOptimizer.LOGGER.info(
                        "Carpet maxEntityCollisions=0; FFM owns entity collisions"
                );
            }
        }
        return carpetOwnsEntityCollisions;
    }

    public static boolean ownsEntityCollisions() {
        return refreshCollisionOwnership();
    }

    public static boolean isCarpetLoaded() {
        return MAX_ENTITY_COLLISIONS != null;
    }

    private static int collisionLimit() {
        if (MAX_ENTITY_COLLISIONS == null) {
            return 0;
        }
        try {
            return MAX_ENTITY_COLLISIONS.getInt(null);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot read Carpet maxEntityCollisions", failure);
        }
    }

    private static Field findCollisionLimitField() {
        if (!FabricLoader.getInstance().isModLoaded("carpet")) {
            return null;
        }

        try {
            return Class.forName("carpet.CarpetSettings")
                    .getField("maxEntityCollisions");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "Carpet is installed but its maxEntityCollisions rule is unavailable",
                    failure
            );
        }
    }
}
