#pragma once

#include "spatial/cell_map.h"

#include <array>
#include <cstddef>
#include <deque>

namespace eco {

// A fine 1x1x1 grid paged by 16x16x16 regions. The outer hash changes only
// when a whole page appears or disappears; cells inside a live page are direct
// array lookups. Empty cells are invalidated independently.
class FineGrid {
public:
    CellMembers* find(const Cell& cell) const noexcept {
        const Cell page = pageOf(cell);
        const Page* value = pages.find(page);
        return value == nullptr ? nullptr : value->cells[localIndex(cell, page)];
    }

    CellMembers*& entry(const Cell& cell) {
        const Cell page = pageOf(cell);
        Page*& value = pages.entry(page);
        if (value == nullptr) value = &acquirePage();
        CellMembers*& member = value->cells[localIndex(cell, page)];
        if (member == nullptr) ++value->occupied;
        return member;
    }

    void erase(const Cell& cell) noexcept {
        const Cell page = pageOf(cell);
        Page* value = pages.find(page);
        if (value == nullptr) return;
        CellMembers*& member = value->cells[localIndex(cell, page)];
        if (member == nullptr) return;
        member = nullptr;
        if (--value->occupied == 0) {
            pages.erase(page);
            retirePage(value);
        }
    }

    void clear() noexcept {
        pages.clear();
        pagePool.clear();
        freePages = nullptr;
    }

private:
    struct Page {
        std::array<CellMembers*, 16 * 16 * 16> cells{};
        std::size_t occupied = 0;
        Page* poolNext = nullptr;
    };

    BasicCellMap<Page> pages;
    std::deque<Page> pagePool;
    Page* freePages = nullptr;

    static std::int64_t pageCoordinate(std::int64_t coordinate) noexcept {
        std::int64_t page = coordinate / 16;
        if (coordinate < 0 && coordinate % 16 != 0) --page;
        return page;
    }

    static Cell pageOf(const Cell& cell) noexcept {
        return {
                pageCoordinate(cell.x),
                pageCoordinate(cell.y),
                pageCoordinate(cell.z)
        };
    }

    static std::size_t localIndex(const Cell& cell, const Cell& page) noexcept {
        const auto x = static_cast<std::size_t>(cell.x - page.x * 16);
        const auto y = static_cast<std::size_t>(cell.y - page.y * 16);
        const auto z = static_cast<std::size_t>(cell.z - page.z * 16);
        return (x * 16 + z) * 16 + y;
    }

    Page& acquirePage() {
        if (freePages != nullptr) {
            Page* page = freePages;
            freePages = page->poolNext;
            page->poolNext = nullptr;
            return *page;
        }
        pagePool.emplace_back();
        return pagePool.back();
    }

    void retirePage(Page* page) noexcept {
        page->cells.fill(nullptr);
        page->occupied = 0;
        page->poolNext = freePages;
        freePages = page;
    }
};

} // namespace eco
