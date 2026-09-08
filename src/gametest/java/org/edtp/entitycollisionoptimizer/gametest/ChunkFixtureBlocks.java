package org.edtp.entitycollisionoptimizer.gametest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Reversible test geometry, including an identical room in both Nether locations. */
final class ChunkFixtureBlocks implements AutoCloseable {
    private record Location(ServerLevel level, BlockPos pos) {}
    private final Map<Location, BlockState> saved = new LinkedHashMap<>();

    void set(ServerLevel level, BlockPos pos, BlockState state, int flags) {
        saved.putIfAbsent(new Location(level, pos.immutable()), level.getBlockState(pos));
        level.setBlock(pos, state, flags);
    }

    void room(ServerLevel level, ChunkPos chunk) {
        BlockPos origin = chunk.getWorldPosition();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 69; y <= 80; y++) {
            boolean wall = x == 0 || x == 15 || z == 0 || z == 15 || y == 69 || y == 80;
            set(level, origin.offset(x, y, z), (wall ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 2);
        }
    }

    @Override public void close() {
        var entries = new ArrayList<>(saved.entrySet());
        for (int i = entries.size() - 1; i >= 0; i--) {
            var entry = entries.get(i);
            entry.getKey().level.setBlock(entry.getKey().pos, entry.getValue(), 2);
        }
        saved.clear();
    }
}
