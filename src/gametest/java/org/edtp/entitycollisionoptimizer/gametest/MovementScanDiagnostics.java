package org.edtp.entitycollisionoptimizer.gametest;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Opt-in, sampled diagnostics. Lives only in the GameTest mod, never in the release JAR. */
public final class MovementScanDiagnostics {
    private static final boolean ENABLED = "true".equalsIgnoreCase(System.getenv("ECO_SCAN_DIAGNOSTICS"));
    // Coprime with the 1,000-entity population, so a stable tick order does not
    // repeatedly sample only the same residue class of entities.
    private static final int SAMPLE_INTERVAL = 67;
    private static final ThreadLocal<Probe> CURRENT = new ThreadLocal<>();
    private static final Set<Entity> POPULATION = Collections.newSetFromMap(new IdentityHashMap<>());
    private static Totals baseline = new Totals("baseline");
    private static Totals optimized = new Totals("optimized");
    private static volatile Totals active;

    private MovementScanDiagnostics() {}

    static void start() {
        if (!ENABLED) return;
        active = null;
        baseline = new Totals("baseline");
        optimized = new Totals("optimized");
        POPULATION.clear();
    }

    static void population(List<? extends Entity> entities) {
        if (!ENABLED) return;
        POPULATION.clear();
        POPULATION.addAll(entities);
    }

    static void beginTick(boolean optimizedMode, boolean measured) {
        if (!ENABLED) return;
        Totals totals = optimizedMode ? optimized : baseline;
        totals.normal.unique.clear();
        totals.step.unique.clear();
        active = measured ? totals : null;
    }

    static void endTick() {
        Totals totals = active;
        active = null;
        if (totals == null) return;
        totals.ticks++;
        totals.normal.uniquePerTick += totals.normal.unique.size();
        totals.step.uniquePerTick += totals.step.unique.size();
    }

    static void finish() {
        if (!ENABLED) return;
        endTick();
        baseline.report();
        optimized.report();
        POPULATION.clear();
    }

    public static Probe begin(Entity entity, Vec3 requested) {
        Totals totals = active;
        if (totals == null || !POPULATION.contains(entity)) return null;
        long ordinal = totals.moves++;
        if (ordinal % SAMPLE_INTERVAL != 0) return null;
        Probe probe = new Probe(totals, CURRENT.get(), requested.horizontalDistance());
        CURRENT.set(probe);
        return probe;
    }

    public static void end(Probe probe, Vec3 actual) {
        if (probe == null) return;
        CURRENT.set(probe.previous);
        if (actual == null) return;
        Totals totals = probe.totals;
        totals.requested.add(probe.requested);
        totals.actual.add(actual.horizontalDistance());
        totals.normal.readsPerMove.add(probe.normalReads);
        totals.step.readsPerMove.add(probe.stepReads);
        if (probe.stepped) totals.stepMoves++;
        if (actual.horizontalDistance() + 1.0E-9 < probe.requested) totals.clippedMoves++;
    }

    public static Probe current() {
        return active == null ? null : CURRENT.get();
    }

    public static final class Probe {
        private final Totals totals;
        private final Probe previous;
        private final double requested;
        public boolean step;
        private boolean stepped;
        private long normalReads;
        private long stepReads;

        private Probe(Totals totals, Probe previous, double requested) {
            this.totals = totals;
            this.previous = previous;
            this.requested = requested;
        }

        public void enteringStep() {
            step = true;
            stepped = true;
        }

        public void scan(AABB box, boolean stepScan, boolean owned) {
            Phase phase = stepScan ? totals.step : totals.normal;
            if (owned) phase.ownedScans++;
            // Exact Cursor3D dimensions used by BlockCollisions, including its halo.
            double x = Math.floor(box.maxX + 1.0E-7) - Math.floor(box.minX - 1.0E-7) + 3;
            double y = Math.floor(box.maxY + 1.0E-7) - Math.floor(box.minY - 1.0E-7) + 3;
            double z = Math.floor(box.maxZ + 1.0E-7) - Math.floor(box.minZ - 1.0E-7) + 3;
            phase.cursorCells.add(x * y * z);
        }

        public void read(BlockPos pos, boolean stepScan, boolean air) {
            read(pos.asLong(), stepScan, air);
        }

        public void read(long pos, boolean stepScan, boolean air) {
            Phase phase = stepScan ? totals.step : totals.normal;
            phase.reads++;
            if (air) phase.airReads++;
            phase.unique.add(pos);
            if (stepScan) stepReads++;
            else normalReads++;
        }

        public void rejectedByHalo(boolean stepScan) {
            (stepScan ? totals.step : totals.normal).haloRejectedReads++;
        }
    }

    private static final class Totals {
        private final String mode;
        private long ticks;
        private long moves;
        private long stepMoves;
        private long clippedMoves;
        private final ScanDistribution requested = new ScanDistribution();
        private final ScanDistribution actual = new ScanDistribution();
        private final Phase normal = new Phase();
        private final Phase step = new Phase();

        private Totals(String mode) { this.mode = mode; }

        private void report() {
            if (ticks == 0) return;
            if (requested.size() != (moves + SAMPLE_INTERVAL - 1) / SAMPLE_INTERVAL) {
                throw new IllegalStateException("Incomplete movement scan samples for " + mode);
            }
            EntityCollisionOptimizer.LOGGER.info(
                    "ECO_SCAN_RESULT mode={} ticks={} moves={} sample_interval={} sampled_moves={} "
                            + "sampled_step_moves={} sampled_clipped_moves={} requested_horizontal={} actual_horizontal={}",
                    mode, ticks, moves, SAMPLE_INTERVAL, requested.size(), stepMoves, clippedMoves,
                    requested.summary(), actual.summary());
            normal.report(mode, "normal");
            step.report(mode, "step");
        }
    }

    private static final class Phase {
        private long reads;
        private long airReads;
        private long haloRejectedReads;
        private long ownedScans;
        private long uniquePerTick;
        private final LongOpenHashSet unique = new LongOpenHashSet();
        private final ScanDistribution cursorCells = new ScanDistribution();
        private final ScanDistribution readsPerMove = new ScanDistribution();

        private void report(String mode, String phase) {
            EntityCollisionOptimizer.LOGGER.info(
                    "ECO_SCAN_PHASE mode={} phase={} owned_scans={} vanilla_scans={} "
                            + "cursor_cells={} state_reads={} air_reads={} halo_rejected_reads={} sum_sampled_unique_positions_per_tick={} reads_per_sampled_move={}",
                    mode, phase, ownedScans, cursorCells.size() - ownedScans, cursorCells.summary(), reads, airReads, haloRejectedReads, uniquePerTick,
                    readsPerMove.summary());
        }
    }
}
