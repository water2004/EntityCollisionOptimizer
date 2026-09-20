#include "eco/collision_api.h"
#include <algorithm>
#include <bit>
#include <cstdint>

namespace {
// Fixed work tile, independent of entity density and output capacity.
constexpr int ROW_BATCH = 8;

struct Cursor {
    int x, y, z, done;
    void publish(int* queryState) const {
        queryState[6] = x; queryState[7] = y; queryState[8] = z; queryState[9] = done;
    }
};

struct Bounds {
    int minX, minY, minZ, maxX, maxY, maxZ;
    int minSectionX, maxSectionX, minSectionY, minSectionZ, width, height;
    unsigned firstBoundary, lastBoundary, lastRange;

    explicit Bounds(const int* queryState)
        : minX(queryState[0]), minY(queryState[1]), minZ(queryState[2]),
          maxX(queryState[3]), maxY(queryState[4]), maxZ(queryState[5]),
          minSectionX(minX >> 4), maxSectionX(maxX >> 4),
          minSectionY(minY >> 4), minSectionZ(minZ >> 4),
          width(maxSectionX - minSectionX + 1), height((maxY >> 4) - minSectionY + 1),
          firstBoundary(1u << (minX & 15)), lastBoundary(1u << (maxX & 15)),
          lastRange(0xffffu >> (15 - (maxX & 15))) {}

    void advanceRow(Cursor& cursor) const {
        cursor.x = minX;
        if (cursor.y != maxY) ++cursor.y;
        else {
            cursor.y = minY;
            if (cursor.z == maxZ) cursor.done = 1;
            else ++cursor.z;
        }
    }
};

struct Row {
    int baseX, y, z, section, end;
};

struct RowBatch {
    Row rows[ROW_BATCH];
    alignas(32) unsigned selected[ROW_BATCH], outer[ROW_BATCH];
    alignas(32) unsigned boundary[ROW_BATCH], range[ROW_BATCH], masks[ROW_BATCH];

    // Independent lanes: no cursor updates, output compaction or pointer chasing.
    // Unused lanes are zeroed by prepare(), so this loop always has eight lanes.
    void filter() {
        for (int i = 0; i < ROW_BATCH; ++i)
            masks[i] = ((selected[i] & ~boundary[i]) | (outer[i] & boundary[i])) & range[i];
    }
};

int prepare(const std::uint16_t* const* collisionRows, const Bounds& bounds,
            Cursor& cursor, RowBatch& batch) {
    int preparedRowCount = 0;
    while (!cursor.done && preparedRowCount < ROW_BATCH) {
        if (cursor.x > bounds.maxX) {
            bounds.advanceRow(cursor);
            continue;
        }
        const int edges = (cursor.y == bounds.minY || cursor.y == bounds.maxY)
                        + (cursor.z == bounds.minZ || cursor.z == bounds.maxZ);
        const int offset = (((cursor.y & 15) << 4) | (cursor.z & 15)) * 3 + edges;
        int sectionX = cursor.x >> 4;
        int section = (((cursor.z >> 4) - bounds.minSectionZ) * bounds.height
                     + (cursor.y >> 4) - bounds.minSectionY) * bounds.width
                     + sectionX - bounds.minSectionX;
        do {
            const int baseX = cursor.x & ~15;
            const int end = std::min(bounds.maxX, baseX + 15);
            const auto* planes = collisionRows[section];
            batch.rows[preparedRowCount] = {baseX, cursor.y, cursor.z, section, end};
            batch.selected[preparedRowCount] = planes ? planes[offset] : 0;
            batch.outer[preparedRowCount] = planes && edges != 2 ? planes[offset + 1] : 0;
            batch.boundary[preparedRowCount] = (sectionX == bounds.minSectionX ? bounds.firstBoundary : 0u)
                                  | (sectionX == bounds.maxSectionX ? bounds.lastBoundary : 0u);
            batch.range[preparedRowCount] = (0xffffu << (cursor.x & 15))
                               & (sectionX == bounds.maxSectionX ? bounds.lastRange : 0xffffu);
            cursor.x = end + 1;
            ++sectionX;
            ++section;
            ++preparedRowCount;
        } while (cursor.x <= bounds.maxX && preparedRowCount < ROW_BATCH);
    }
    for (int i = 0; i < ROW_BATCH; ++i)
        if (i >= preparedRowCount) {
            batch.selected[i] = batch.outer[i] = batch.boundary[i] = batch.range[i] = 0;
        }
    batch.filter();
    return preparedRowCount;
}
}

// Query: minXYZ, maxXYZ, cursorXYZ, done. Descriptor order: section Z/Y/X.
// Output records: world XYZ and descriptor index. Capacity paginates; it never caps candidates.
int scanCollisionBlocks(
        const std::uint16_t* const* collisionRows,
        int* queryState,
        int* outputRecords,
        int outputCapacity
) {
    if (!collisionRows || !queryState || !outputRecords || outputCapacity <= 0) return -1;
    Cursor cursor{queryState[6], queryState[7], queryState[8], queryState[9]};
    if (cursor.done) return 0;
    const Bounds bounds(queryState);
    RowBatch batch;
    int recordCount = 0;
    while (!cursor.done) {
        const int prepared = prepare(collisionRows, bounds, cursor, batch);
        for (int i = 0; i < prepared; ++i) {
            const Row& row = batch.rows[i];
            unsigned mask = batch.masks[i];
            while (mask) {
                const int selected = row.baseX + std::countr_zero(mask);
                mask &= mask - 1;
                int* record = outputRecords + recordCount++ * 4;
                record[0] = selected; record[1] = row.y; record[2] = row.z; record[3] = row.section;
                if (recordCount == outputCapacity) {
                    // Only consumed work is published. A following call rereads shared
                    // collisionRows, including any palette mutations between output pages.
                    Cursor{mask ? selected + 1 : row.end + 1, row.y, row.z, 0}.publish(queryState);
                    return recordCount;
                }
            }
        }
    }
    // No Java callback can observe intermediate progress within this FFM call.
    cursor.publish(queryState);
    return recordCount;
}
