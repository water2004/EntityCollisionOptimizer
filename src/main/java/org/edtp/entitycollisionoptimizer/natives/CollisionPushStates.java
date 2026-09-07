package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.world.entity.Entity;
import org.edtp.entitycollisionoptimizer.collision.CollisionCacheEpochs;
import org.edtp.entitycollisionoptimizer.collision.CollisionCacheState;

import java.util.Arrays;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.edtp.entitycollisionoptimizer.natives.CollisionStateTable.*;

/** Change-driven derived inputs. Native consumes these flags, not Java's per-candidate predicates. */
final class CollisionPushStates {
    private final CollisionStateTable table;
    private boolean[] marked = new boolean[0];
    private int[] dirty = new int[0];
    private int count;
    private long blocks = Long.MIN_VALUE, vehicles = Long.MIN_VALUE;

    CollisionPushStates(CollisionStateTable table) { this.table = table; }

    void invalidate(int slot) {
        if (slot >= marked.length) {
            marked = Arrays.copyOf(marked, table.capacity());
            dirty = Arrays.copyOf(dirty, table.capacity());
        }
        if (!marked[slot]) { marked[slot] = true; dirty[count++] = slot; }
    }

    void refresh() {
        long nextBlocks = CollisionCacheEpochs.blockRevision();
        long nextVehicles = CollisionCacheEpochs.vehicleRevision();
        if (blocks != nextBlocks || vehicles != nextVehicles) {
            blocks = nextBlocks;
            vehicles = nextVehicles;
            for (int slot = 0; slot < table.size(); slot++) if (table.bound(slot)) invalidate(slot);
        }
        // A slot can be retired/reused while queued; its current binding is the only authority.
        while (count != 0) {
            int slot = dirty[--count];
            marked[slot] = false;
            if (!table.bound(slot)) continue;
            Entity entity = table.entity(slot);
            int flags = ((CollisionCacheState) entity).entityCollisionOptimizer$pushState()
                    | (entity.noPhysics ? CollisionCacheState.NO_PHYSICS : 0);
            int root = table.slot(entity.getRootVehicle());
            table.memory().set(JAVA_INT, (long) slot * STRIDE_BYTES + STATE_OFFSET, flags);
            table.memory().set(JAVA_INT, (long) slot * STRIDE_BYTES + ROOT_OFFSET, root);
        }
    }

    void clear() {
        Arrays.fill(marked, false);
        count = 0;
        blocks = vehicles = Long.MIN_VALUE;
    }
}
