package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.collision.blocks.CollisionBlockMask;

import java.util.Arrays;
import java.util.Locale;

/** Opt-in isolated row-cost probe, not a server throughput benchmark. Never in the release JAR. */
final class BlockRowIndexCost {
    private static volatile long sink;

    static void measure(PalettedContainer<BlockState> input) {
        if (!"true".equalsIgnoreCase(System.getenv("ECO_ROW_INDEX_COST"))) return;
        var template = input.copy();
        BlockState[] states = {Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
                Blocks.OAK_FENCE.defaultBlockState(), Blocks.MOVING_PISTON.defaultBlockState()};
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            template.set(x, y, z, states[(x + y + z) & 3]);
        }
        double[][] samples = new double[3][8];
        for (int round = 0; round < 12; round++) {
            CollisionBlockMask[] palettes = new CollisionBlockMask[32];
            // Copy costs are excluded. Each copy owns a fresh, initially absent collision index.
            for (int i = 0; i < palettes.length; i++) palettes[i] = (CollisionBlockMask) template.copy();
            long checksum = 0;
            long start = System.nanoTime();
            for (var palette : palettes) checksum ^= palette.entityCollisionOptimizer$collisionRow(0, 0, 0, 0);
            long first = System.nanoTime();
            for (var palette : palettes) for (int row = 1; row < 256; row++) {
                checksum ^= palette.entityCollisionOptimizer$collisionRow(row >> 4, row & 15, 0, 0);
            }
            long cold = System.nanoTime();
            for (var palette : palettes) for (int row = 0; row < 256; row++) {
                checksum ^= palette.entityCollisionOptimizer$collisionRow(row >> 4, row & 15, 0, 0);
            }
            long warm = System.nanoTime();
            sink = checksum;
            if (round >= 4) {
                samples[0][round - 4] = (first - start) / 32.0;
                samples[1][round - 4] = (cold - first) / (32.0 * 255);
                samples[2][round - 4] = (warm - cold) / (32.0 * 256);
            }
        }
        for (double[] phase : samples) Arrays.sort(phase);
        EntityCollisionOptimizer.LOGGER.info(String.format(Locale.ROOT,
                "ECO_ROW_INDEX_COST samples=8 palettes_per_sample=32 first_row_median_ns=%.1f "
                        + "remaining_cold_row_median_ns=%.1f warm_row_median_ns=%.1f",
                median(samples[0]), median(samples[1]), median(samples[2])));
    }

    private static double median(double[] samples) { return (samples[3] + samples[4]) * 0.5; }
}
