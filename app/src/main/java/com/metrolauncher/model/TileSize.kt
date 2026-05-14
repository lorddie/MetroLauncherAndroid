package com.metrolauncher.model

/**
 * Dimensioni delle tile, identiche a Windows Phone 10.
 * La griglia base è 4 colonne.
 *   SMALL  -> 1x1 (mini tile, solo icona)
 *   MEDIUM -> 2x2 (icona + label in basso)
 *   WIDE   -> 4x2 (live tile orizzontale, ideale per notifiche con testo)
 *   LARGE  -> 4x4 (live tile grande con anteprima ricca)
 */
enum class TileSize(val cols: Int, val rows: Int) {
    SMALL(1, 1),
    MEDIUM(2, 2),
    WIDE(4, 2),
    LARGE(4, 4);

    fun next(): TileSize = when (this) {
        SMALL -> MEDIUM
        MEDIUM -> WIDE
        WIDE -> LARGE
        LARGE -> SMALL
    }
}
