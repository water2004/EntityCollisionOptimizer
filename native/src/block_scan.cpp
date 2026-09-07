#include "collision_api.h"
#include <algorithm>
#include <bit>
#include <cstdint>

// Query: minXYZ, maxXYZ, cursorXYZ, done. Descriptor order: section Z/Y/X.
// Output records: world XYZ and descriptor index. Capacity is pagination, never a candidate cap.
int scanCollisionBlocks(const std::uint16_t* const* rows, int* query, int* output, int capacity) {
    if (!rows || !query || !output || capacity <= 0) return -1;
    int count = 0;
    const int width = (query[3] >> 4) - (query[0] >> 4) + 1;
    const int height = (query[4] >> 4) - (query[1] >> 4) + 1;
    while (!query[9] && count < capacity) {
        int x = query[6], y = query[7], z = query[8];
        if (x > query[3]) {
            query[6] = query[0];
            if (y == query[4]) {
                query[7] = query[1];
                if (z == query[5]) query[9] = 1;
                else ++query[8];
            } else ++query[7];
            continue;
        }
        int section = (((z >> 4) - (query[2] >> 4)) * height + (y >> 4) - (query[1] >> 4))
                      * width + (x >> 4) - (query[0] >> 4);
        int end = std::min(query[3], (x & ~15) + 15);
        const auto* planes = rows[section];
        if (planes) {
            int edges = (y == query[1] || y == query[4]) + (z == query[2] || z == query[5]);
            int offset = (((y & 15) << 4) | (z & 15)) * 3 + edges;
            unsigned mask = planes[offset];
            unsigned boundary = 0;
            if ((x >> 4) == (query[0] >> 4)) boundary |= 1u << (query[0] & 15);
            if ((x >> 4) == (query[3] >> 4)) boundary |= 1u << (query[3] & 15);
            mask = (mask & ~boundary) | ((edges == 2 ? 0u : planes[offset + 1]) & boundary);
            mask &= (0xffffu << (x & 15)) & (0xffffu >> (15 - (end & 15)));
            while (mask && count < capacity) {
                int selected = (x & ~15) + std::countr_zero(mask);
                mask &= mask - 1;
                int* record = output + count++ * 4;
                record[0] = selected; record[1] = y; record[2] = z; record[3] = section;
                query[6] = selected + 1;
            }
            if (mask) break;
        }
        query[6] = end + 1;
    }
    return count;
}
