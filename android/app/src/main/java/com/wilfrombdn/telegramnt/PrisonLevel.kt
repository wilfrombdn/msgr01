package com.wilfrombdn.telegramnt

// Dead Letter level 5: the prison. The layout follows the real Alcatraz cellhouse (closed in 1963, now a museum), squeezed onto a grid:
//   west (left)   the kitchen, the dining hall, the shower room and the infirmary
//   Times Square  the cross corridor at the west end of the cell blocks (the dining-hall door opens off it, under the clock)
//   the blocks    A (short, top), Michigan Avenue, B, Broadway (the wide middle corridor), C, Seedy Street, then the library and D block
//                 B and C are two rows of cells back to back with a narrow pipe corridor between them (the 1962 escape went this way)
//   east (right)  the east-end corridor, the control centre, the visiting room, the barber, the warden's office and the front gate
//   south         the recreation yard, down the steps from Times Square, and the laundry off its east end (your job)
// 72 tiles wide, 48 tall. You wake up in B block, cell 5 on the Broadway side.
//
// Tiles: # wall   . floor   , yard   = bars   o open cell door   O your cell door (opens after the morning count)
//        b bunk   t toilet + sink   T dining table   k counter   K stove   r shelf   z yard steps   h shower   d desk   c barber chair
//        G glass   L locked door   X the front gate   p pipe corridor   v air vent in a cell's back wall   W guard tower   n infirmary bed
//        laundry: U dirty-laundry cart   M washer   Y dryer   F folding table   C clean-laundry cart   u shelf of guard uniforms
internal class PrisonSpec(val map: List<String>, val startX: Float, val startY: Float, val myDoor: Pair<Int, Int>, val bunk: Pair<Int, Int>) {
    val cols get() = map[0].length
    val rows get() = map.size
    fun tile(c: Int, r: Int): Char = if (r < 0 || r >= rows || c < 0 || c >= cols) '#' else map[r][c]
}

internal object PrisonLevel {
    const val W = 72
    const val H = 48
    /** The cell blocks run from column 28 to 58; each cell is 3 columns (a wall and two inside). */
    const val CX0 = 28
    const val CX1 = 58
    const val MY_CELL = 5

    fun rock(): PrisonSpec {
        val g = Array(H) { CharArray(W) { '#' } }
        fun fill(r0: Int, r1: Int, c0: Int, c1: Int, ch: Char = '.') { for (r in r0..r1) for (c in c0..c1) g[r][c] = ch }
        fun put(r: Int, c: Int, ch: Char) { g[r][c] = ch }

        // ---- the corridors
        fill(1, 36, 24, 27)            // Times Square (west end)
        fill(1, 36, 59, 60)            // the east-end corridor
        fill(5, 7, CX0, CX1)           // Michigan Avenue
        fill(17, 20, CX0, CX1)         // Broadway
        fill(30, 32, CX0, CX1)         // Seedy Street

        // ---- a row of cells. [front] is the row next to the bars, [back] the row against the back wall.
        fun cells(front: Int, back: Int, bars: Int, k0: Int, k1: Int, wallBehind: Int) {
            for (c in CX0..CX1) put(bars, c, '=')
            for (k in k0..k1) {
                val x = CX0 + 3 * k
                put(front, x + 1, '.'); put(front, x + 2, '.')
                put(back, x + 1, 'b'); put(back, x + 2, 't')
                put(bars, x + 1, 'o')
                if (wallBehind >= 0) put(wallBehind, x + 1, 'v')      // the air vent under the sink
            }
        }
        cells(front = 3, back = 2, bars = 4, k0 = 2, k1 = 9, wallBehind = -1)          // A block, facing down onto Michigan Avenue
        for (c in CX0..CX0 + 6) put(4, c, '#')                                          // A is the short block
        cells(front = 9, back = 10, bars = 8, k0 = 0, k1 = 9, wallBehind = 11)          // B block, Michigan side
        cells(front = 15, back = 14, bars = 16, k0 = 0, k1 = 9, wallBehind = 13)        // B block, Broadway side (yours)
        cells(front = 22, back = 23, bars = 21, k0 = 0, k1 = 9, wallBehind = 24)        // C block, Broadway side
        cells(front = 28, back = 27, bars = 29, k0 = 0, k1 = 9, wallBehind = 26)        // C block, Seedy Street side
        cells(front = 34, back = 35, bars = 33, k0 = 3, k1 = 9, wallBehind = -1)        // D block, the treatment cells
        // the pipe corridors inside B and C, locked at both ends
        for (r in intArrayOf(12, 25)) { fill(r, r, CX0 + 1, CX1 - 1, 'p'); put(r, CX0, 'L'); put(r, CX1, 'L') }
        // the library, at the west end of D block
        fill(33, 35, CX0, CX0 + 8)
        for (c in CX0 + 1..CX0 + 7 step 2) { put(34, c, 'r'); put(35, c, 'r') }
        put(33, CX0 + 4, 'd')

        // ---- your cell
        val myX = CX0 + 3 * MY_CELL
        put(16, myX + 1, 'O')

        // ---- west: the showers, the dining hall + kitchen, the infirmary
        fill(1, 6, 1, 22)                                                   // shower room
        for (c in 2..21 step 2) put(1, c, 'h')
        for (c in 4..19 step 3) put(4, c, 'k')                              // benches
        put(3, 23, '.')                                                     // its door
        fill(8, 28, 8, 22)                                                  // dining hall
        for (r in intArrayOf(10, 13, 16, 22, 25)) for (c in 10..20) if (c != 15) put(r, c, 'T')
        for (r in 17..20) put(r, 23, '.')                                   // the big door off Times Square
        fill(8, 28, 1, 6)                                                   // kitchen
        for (r in 9..27 step 2) put(r, 1, 'K')
        for (r in 10..26) if (r % 6 != 0) put(r, 4, 'k')
        for (r in intArrayOf(12, 18, 24)) put(r, 7, 'k')                    // the serving counter through the wall
        put(27, 7, '.')                                                     // the kitchen door
        fill(30, 36, 1, 22)                                                 // infirmary
        for (c in 2..20 step 3) { put(31, c, 'n'); put(35, c, 'n') }
        put(33, 23, '.')

        // ---- south: the recreation yard, down the steps from Times Square
        put(37, 25, '.'); put(37, 26, '.')
        fill(38, 46, 1, 58, ',')
        for (r in 38..40) for (c in 2..22) put(r, c, 'z')                   // the big concrete steps the men sat on
        for (r in 38..40) for (c in 24..27) put(r, c, ',')                  // and the walk down from the door
        put(45, 56, 'W'); put(45, 57, 'W'); put(46, 56, 'W'); put(46, 57, 'W')   // the guard tower in the corner

        // ---- the laundry, through a door at the east end of the yard
        fill(38, 46, 60, 70); put(42, 59, '.')
        put(38, 61, 'M'); put(38, 63, 'M'); put(38, 65, 'M'); put(38, 67, 'Y'); put(38, 69, 'Y')
        put(42, 64, 'F'); put(42, 65, 'F'); put(42, 66, 'F')
        put(45, 61, 'U'); put(45, 69, 'C')
        put(40, 70, 'u'); put(41, 70, 'u')

        // ---- east: the control centre, visiting, the barber, the warden, the front gate
        fill(1, 9, 62, 70); for (r in intArrayOf(2, 3, 4, 6, 7, 8)) put(r, 61, 'G'); put(5, 61, 'L')
        put(3, 64, 'd'); put(3, 65, 'd'); put(6, 68, 'r'); put(7, 68, 'r'); put(2, 69, 'r')
        fill(11, 17, 62, 70); put(14, 61, '.'); for (r in 11..17) if (r != 14) put(r, 66, 'G')
        put(12, 64, 'c'); put(16, 64, 'c'); put(12, 68, 'c'); put(16, 68, 'c')
        fill(19, 23, 62, 70); put(21, 61, '.'); put(20, 64, 'c'); put(20, 67, 'c'); put(22, 69, 'r')
        fill(25, 30, 62, 70); put(27, 61, 'L'); put(26, 65, 'd'); put(26, 66, 'd'); put(29, 69, 'r')
        fill(32, 36, 62, 70); put(34, 61, 'L'); put(34, 71, 'X'); put(35, 71, 'X')

        return PrisonSpec(g.map { String(it) }, myX + 1.5f, 15.5f, (myX + 1) to 16, (myX + 1) to 14)
    }

    /** What the place you are standing in is called (shown at the top of the screen). */
    fun zoneName(s: PrisonSpec, c: Int, r: Int): String {
        val myX = CX0 + 3 * MY_CELL
        if (r in 14..15 && c in myX + 1..myX + 2) return "YOUR CELL  B-${MY_CELL + 1}"
        if (r >= 38) return if (c >= 60) "LAUNDRY" else "RECREATION YARD"
        if (c in 24..27) return "TIMES SQUARE"
        if (c in 59..60) return "EAST CORRIDOR"
        if (c >= 62) return when (r) { in 1..9 -> "CONTROL CENTRE"; in 11..17 -> "VISITING ROOM"; in 19..23 -> "BARBER"; in 25..30 -> "WARDEN'S OFFICE"; else -> "FRONT GATE" }
        if (c <= 22) return when (r) { in 1..6 -> "SHOWERS"; in 30..36 -> "INFIRMARY"; else -> if (c <= 6) "KITCHEN" else "DINING HALL" }
        if (c == 23) return if (r in 17..20) "DINING HALL" else ""
        return when (r) {
            in 1..4 -> "A BLOCK"
            in 5..7 -> "MICHIGAN AVENUE"
            in 8..16 -> if (r == 12) "PIPE CORRIDOR" else "B BLOCK"
            in 17..20 -> "BROADWAY"
            in 21..29 -> if (r == 25) "PIPE CORRIDOR" else "C BLOCK"
            in 30..32 -> "SEEDY STREET"
            else -> if (c <= CX0 + 8) "LIBRARY" else "D BLOCK"
        }
    }
}
