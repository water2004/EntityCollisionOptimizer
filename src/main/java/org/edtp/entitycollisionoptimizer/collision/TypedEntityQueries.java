package org.edtp.entitycollisionoptimizer.collision;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.edtp.entitycollisionoptimizer.mixin.EntitySectionAccessor;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

/** Select a typed query path from existing class collections, without another maintained index. */
public final class TypedEntityQueries {
    private static final Class<?> CLASS_TEST_IMPLEMENTATION = EntityTypeTest.forClass(Entity.class).getClass();

    /** @return true when handled here; false to retain the original typed traversal. */
    public static <T extends Entity> boolean query(ServerLevel level, EntitySectionStorage<Entity> storage,
            EntityTypeTest<Entity, T> type, AABB box, AbortableIterationConsumer<T> consumer) {
        // Custom and exact-class tests may reject members or have observable callbacks.
        if (type.getClass() != CLASS_TEST_IMPLEMENTATION) return false;
        ClassProbe probe = new ClassProbe(type.getBaseClass());
        storage.forEachAccessibleNonEmptySection(box, probe);
        if (probe.preferNative) {
            CollisionFrame.getEntities(level, type, box, consumer);
            return true;
        }
        // Empty queries are complete; selective queries continue through the original method.
        return !probe.found;
    }

    private static final class ClassProbe implements AbortableIterationConsumer<EntitySection<Entity>> {
        private final Class<? extends Entity> entityClass;
        private boolean found;
        private boolean preferNative;

        private ClassProbe(Class<? extends Entity> entityClass) { this.entityClass = entityClass; }

        @Override @SuppressWarnings("unchecked")
        public Continuation accept(EntitySection<Entity> section) {
            var candidates = ((EntitySectionAccessor<Entity>) (Object) section)
                    .eco$entities().find(entityClass);
            if (candidates.isEmpty()) return Continuation.CONTINUE;
            found = true;
            // The first matching section estimates selectivity; do not pre-scan the whole query
            // or turn a dense box query into one downcall per section.
            preferNative = candidates.size() == section.size();
            return Continuation.ABORT;
        }
    }

    private TypedEntityQueries() {}
}
