package com.metrolauncher.util

import com.metrolauncher.model.Tile
import com.metrolauncher.model.TileSize

/**
 * Handles tile positioning in a fixed-column grid.
 * Resolves collisions and finds empty spaces.
 */
object GridPacker {

    /**
     * Finds the first free position for a tile of a given size.
     */
    fun findFreeSlot(allTiles: List<Tile>, size: TileSize, cols: Int): Pair<Int, Int> {
        val occupancy = buildOccupancy(allTiles, cols)
        for (r in 0 until 1000) {
            for (c in 0..cols - size.cols) {
                if (fits(occupancy, r, c, size, cols)) return r to c
            }
        }
        return 0 to 0
    }

    /**
     * Realigns all tiles, eliminating holes and collisions.
     */
    fun autoLayout(allTiles: List<Tile>, cols: Int): List<Tile> {
        val sorted = allTiles.sortedWith(compareBy({ it.row }, { it.col }))
        val result = mutableListOf<Tile>()
        for (t in sorted) {
            val (r, c) = findFreeSlot(result, t.size, cols)
            t.row = r; t.col = c; result.add(t)
        }
        return result
    }

    fun totalRows(allTiles: List<Tile>): Int = allTiles.maxOfOrNull { it.row + it.size.rows } ?: 0

    fun hasOverlaps(allTiles: List<Tile>): Boolean {
        for (i in allTiles.indices) {
            val a = allTiles[i]
            for (j in i + 1 until allTiles.size) {
                val b = allTiles[j]
                if (overlap(a.row, a.col, a.size.rows, a.size.cols,
                            b.row, b.col, b.size.rows, b.size.cols)) return true
            }
        }
        return false
    }

    fun sanitize(allTiles: List<Tile>, cols: Int): Boolean {
        var changed = false
        for (t in allTiles) {
            if (t.col + t.size.cols > cols) {
                t.col = 0
                t.size = if (t.size.cols > cols) TileSize.MEDIUM else t.size
                changed = true
            }
        }
        if (hasOverlaps(allTiles)) {
            autoLayout(allTiles, cols)
            changed = true
        }
        return changed
    }

    fun canPlace(allTiles: List<Tile>, size: TileSize, row: Int, col: Int, cols: Int, skipId: String? = null): Boolean {
        if (col + size.cols > cols) return false
        for (t in allTiles) {
            if (t.id == skipId) continue
            if (overlap(row, col, size.rows, size.cols, t.row, t.col, t.size.rows, t.size.cols)) return false
        }
        return true
    }

    /**
     * Result of an interactive movement (push down).
     */
    class MoveResult(val movingTile: Tile, val shifted: Map<String, Pair<Int, Int>>)

    /**
     * Interactive collision algorithm: pushes down tiles that collide 
     * with the target position of the dragged tile.
     */
    fun pushDown(
        allTiles: List<Tile>,
        moving: Tile,
        targetRow: Int,
        targetCol: Int,
        cols: Int
    ): MoveResult? {
        if (targetCol < 0 || targetRow < 0) return null
        if (targetCol + moving.size.cols > cols) return null

        data class Slot(val id: String, var row: Int, val col: Int, val rh: Int, val cw: Int)

        // Prepare data excluding the moving tile
        val slots = allTiles.filter { it.id != moving.id }.map {
            Slot(it.id, it.row, it.col, it.size.rows, it.size.cols)
        }.toMutableList()

        val movingSlot = Slot(moving.id, targetRow, targetCol, moving.size.rows, moving.size.cols)

        // Sort slots to process them from top to bottom (visual stability)
        slots.sortWith(compareBy({ it.row }, { it.col }))

        var progressed = true
        var safety = 200
        while (progressed && safety-- > 0) {
            progressed = false
            
            // 1. Collisions with the dragged tile
            for (s in slots) {
                if (overlap(movingSlot.row, movingSlot.col, movingSlot.rh, movingSlot.cw,
                            s.row, s.col, s.rh, s.cw)) {
                    val newRow = movingSlot.row + movingSlot.rh
                    if (newRow > s.row) {
                        s.row = newRow
                        progressed = true
                    }
                }
            }
            
            // 2. Cascade collisions between pushed tiles
            // To avoid holes, we maintain the relative order
            for (i in slots.indices) {
                val a = slots[i]
                for (j in slots.indices) {
                    if (i == j) continue
                    val b = slots[j]
                    if (overlap(a.row, a.col, a.rh, a.cw, b.row, b.col, b.rh, b.cw)) {
                        val newRow = a.row + a.rh
                        if (newRow > b.row) {
                            b.row = newRow
                            progressed = true
                        }
                    }
                }
            }
        }

        val shifted = mutableMapOf<String, Pair<Int, Int>>()
        val originalsById = allTiles.associateBy { it.id }
        for (s in slots) {
            val orig = originalsById[s.id] ?: continue
            if (s.row != orig.row || s.col != orig.col) {
                shifted[s.id] = s.row to s.col
            }
        }

        val updatedMoving = moving.copy(row = movingSlot.row, col = movingSlot.col)
        return MoveResult(updatedMoving, shifted)
    }

    private fun overlap(r1: Int, c1: Int, h1: Int, w1: Int, r2: Int, c2: Int, h2: Int, w2: Int): Boolean {
        return !(c1 + w1 <= c2 || c2 + w2 <= c1 || r1 + h1 <= r2 || r2 + h2 <= r1)
    }

    private fun buildOccupancy(allTiles: List<Tile>, cols: Int): Array<BooleanArray> {
        val maxR = (allTiles.maxOfOrNull { it.row + it.size.rows } ?: 0) + 10
        val grid = Array(maxR) { BooleanArray(cols) }
        for (t in allTiles) {
            for (r in t.row until t.row + t.size.rows) {
                for (c in t.col until t.col + t.size.cols) {
                    if (r < maxR && c < cols) grid[r][c] = true
                }
            }
        }
        return grid
    }

    private fun fits(grid: Array<BooleanArray>, row: Int, col: Int, size: TileSize, cols: Int): Boolean {
        if (col + size.cols > cols) return false
        if (row + size.rows > grid.size) return true // "infinite" space below
        for (r in row until row + size.rows) {
            for (c in col until col + size.cols) {
                if (grid[r][c]) return false
            }
        }
        return true
    }
}
