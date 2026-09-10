#pragma once

#ifndef ECO_VANILLA_ORDER
#define ECO_VANILLA_ORDER 1
#endif

#include "spatial/cell_geometry.h"

#include <cstddef>
#include <cstdint>
#include <memory_resource>
#include <vector>

namespace eco {

struct Cell {
    std::int64_t x;
    std::int64_t y;
    std::int64_t z;

    bool operator==(const Cell&) const = default;
};

struct CellHash {
    std::size_t operator()(const Cell& cell) const noexcept;
};

struct CellMembers {
    std::vector<int> ids;
    // Hard members resident in this cell; hard-only scans skip empty cells outright.
    std::size_t hardCount = 0;
#if ECO_VANILLA_ORDER
    bool orderDirty = true;
#endif
    CellGeometry geometry;
    CellMembers* poolNext = nullptr;

    explicit CellMembers(
            std::pmr::memory_resource* resource = std::pmr::get_default_resource()
    ) : geometry(resource) {}
};

// Flat open-addressing cell lookup: linear probing over contiguous entries
// instead of node-based bucket walks.  Values are pool-owned, so rehashes never
// move the CellMembers that backreferences point at.
class CellMap {
public:
    CellMap() : entries(MIN_CAPACITY) {}

    CellMembers* find(const Cell& key) const noexcept {
        std::size_t index = CellHash{}(key) & mask;
        while (true) {
            const Entry& probe = entries[index];
            if (probe.value == nullptr) return nullptr;
            if (probe.key == key) return probe.value;
            index = (index + 1) & mask;
        }
    }

    // Single-probe find-or-insert; a null reference marks a fresh slot to fill.
    CellMembers*& entry(const Cell& key) {
        if ((used + 1) * 10 >= entries.size() * 7) rehash(entries.size() * 2);
        std::size_t index = CellHash{}(key) & mask;
        while (entries[index].value != nullptr) {
            if (entries[index].key == key) return entries[index].value;
            index = (index + 1) & mask;
        }
        ++used;
        entries[index].key = key;
        return entries[index].value;
    }

    void erase(const Cell& key) noexcept {
        std::size_t hole = CellHash{}(key) & mask;
        while (true) {
            const Entry& probe = entries[hole];
            if (probe.value == nullptr) return;
            if (probe.key == key) break;
            hole = (hole + 1) & mask;
        }
        // Backward-shift deletion keeps probe chains tight without tombstones.
        for (std::size_t next = (hole + 1) & mask; entries[next].value != nullptr;
                next = (next + 1) & mask) {
            const std::size_t home = CellHash{}(entries[next].key) & mask;
            const bool within = hole < next
                    ? (home > hole && home <= next)
                    : (home > hole || home <= next);
            if (!within) {
                entries[hole] = entries[next];
                hole = next;
            }
        }
        entries[hole].value = nullptr;
        --used;
    }

    void clear() noexcept {
        for (Entry& entry : entries) {
            entry.value = nullptr;
        }
        used = 0;
    }

private:
    struct Entry {
        Cell key{};
        CellMembers* value = nullptr;
    };

    static constexpr std::size_t MIN_CAPACITY = 64;

    std::vector<Entry> entries;
    std::size_t used = 0;
    std::size_t mask = MIN_CAPACITY - 1;

    void rehash(std::size_t capacity) {
        std::vector<Entry> next(capacity);
        const std::size_t nextMask = capacity - 1;
        for (const Entry& entry : entries) {
            if (entry.value == nullptr) continue;
            std::size_t index = CellHash{}(entry.key) & nextMask;
            while (next[index].value != nullptr) {
                index = (index + 1) & nextMask;
            }
            next[index] = entry;
        }
        entries = std::move(next);
        mask = nextMask;
    }
};

} // namespace eco