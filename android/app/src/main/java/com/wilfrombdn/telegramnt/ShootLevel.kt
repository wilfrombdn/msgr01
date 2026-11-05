package com.wilfrombdn.telegramnt

import kotlin.math.PI

// The town hall of Dead Letter level 4, 16 tiles wide and 65 tall, read from the bottom (where you walk in) to the top:
//   rows 33-62  the main lobby: reception desk, a coffee area, statues, and a row of cubicles down the right side where clerks are questioned
//   row 32      the hall door (opens when the lobby is clear)
//   rows 12-31  the hall (cols 6-9) with three rooms off each side: meeting room, office, storage
//   row 11      the mayor's door (you kick it open once everything else is clear)
//   rows 1-10   the mayor's office, trashed, with furniture piled against the window
// Tiles: # wall, . floor, c pillar, u statue, r tall rack (these three block bullets and sight), d desk, t table, f sofa, k cubicle panel,
// y cubicle desk, x crates (low cover: bullets sometimes hit them instead of you), D door.
internal object ShootLevel {
    const val W = 16
    const val H = 65
    const val HALL = 6        // room number of the hall itself
    const val LOBBY = 7       // room number of the lobby

    fun townHall(): ShootSpec {
        val g = Array(H) { CharArray(W) { '#' } }
        fun fill(r0: Int, r1: Int, c0: Int, c1: Int, ch: Char = '.') { for (r in r0..r1) for (c in c0..c1) g[r][c] = ch }
        fun put(r: Int, c: Int, ch: Char) { g[r][c] = ch }

        fill(1, 10, 1, 14)                         // mayor's office
        fill(12, 31, 6, 9)                         // the hall
        for (r0 in intArrayOf(12, 19, 26)) {
            fill(r0, r0 + 5, 1, 4); fill(r0, r0 + 5, 11, 14)
            put(r0 + 2, 5, '.'); put(r0 + 3, 5, '.'); put(r0 + 2, 10, '.'); put(r0 + 3, 10, '.')     // doorways into the rooms
        }
        fill(33, 62, 1, 14)                        // the lobby
        fill(63, 63, 7, 8)                         // the way out to the street
        put(11, 7, 'D'); put(11, 8, 'D'); put(32, 7, 'D'); put(32, 8, 'D')

        // ---- the mayor's office: wrecked. Cabinets and a sofa shoved against the window, the desk on its side.
        for (c in 2..4) put(1, c, 'x'); for (c in 6..8) put(1, c, 'f'); for (c in 10..13) put(1, c, 'x')
        put(2, 3, 'x'); put(2, 11, 'x'); put(2, 7, 'f')
        for (c in 5..8) put(4, c, 'd')
        put(7, 2, 'd'); put(8, 13, 'd')
        put(7, 11, 'M'); put(61, 7, 'S')

        // ---- the six rooms
        for (c in 2..3) for (r in 13..15) put(r, c, 't')                       // 0 meeting room (left)
        put(20, 2, 'd'); put(20, 3, 'd'); put(23, 1, 'd'); put(23, 2, 'd')      // 1 office (left)
        put(27, 1, 'x'); put(27, 2, 'x'); put(29, 3, 'x'); put(29, 4, 'x'); put(30, 1, 'x'); put(31, 4, 'x'); put(26, 3, 'r'); put(26, 4, 'r')   // 2 storage (left)
        put(13, 12, 'd'); put(13, 13, 'd'); put(16, 14, 'd'); put(12, 14, 'r')  // 3 office (right)
        for (c in 12..13) for (r in 20..22) put(r, c, 't')                      // 4 meeting room (right)
        put(27, 13, 'x'); put(27, 14, 'x'); put(29, 11, 'x'); put(29, 12, 'x'); put(30, 14, 'x'); put(31, 11, 'x'); put(26, 11, 'r'); put(26, 12, 'r')   // 5 storage (right)

        // ---- the lobby
        for (r in intArrayOf(35, 40, 45, 50, 55)) { put(r, 4, 'c'); put(r, 11, 'c') }           // colonnades
        for (r in intArrayOf(35, 40, 45, 50, 55)) for (c in 12..14) put(r, c, 'k')              // the cubicle panels
        for (r in intArrayOf(35, 40, 45, 50)) for (rr in r + 1..r + 3) put(rr, 14, 'y')         // the cubicle desks, against the wall
        for (c in 1..3) { put(41, c, 'f'); put(46, c, 'f') }                                    // the coffee area on the left
        put(43, 1, 'f'); put(44, 1, 'f'); for (c in 2..3) for (r in 43..44) put(r, c, 't')
        for (c in 6..9) put(55, c, 't')                                                         // the reception desk
        for (r in 47..48) for (c in 7..8) put(r, c, 'u')                                        // the fountain statue in the middle
        put(34, 5, 'u'); put(34, 10, 'u'); put(37, 2, 'u'); put(58, 2, 'u'); put(58, 13, 'u')
        put(51, 2, 'd'); put(51, 3, 'd'); put(42, 6, 'd'); put(42, 9, 'd')

        val map = g.map { String(it) }
        val up = -PI.toFloat() / 2; val down = PI.toFloat() / 2; val left = PI.toFloat(); val right = 0f
        val off = ArrayList<Spawn>()
        // the lobby: four officers questioning clerks, five SWAT, three more officers
        for (y in floatArrayOf(37.5f, 42.5f, 47.5f, 52.5f)) off += Spawn(12.5f, y, right, LOBBY)
        off += Spawn(3.5f, 36.5f, down, LOBBY, 1); off += Spawn(7.5f, 38.5f, down, LOBBY, 1); off += Spawn(10.5f, 41.5f, down, LOBBY, 1)
        off += Spawn(5.5f, 48.5f, down, LOBBY, 1); off += Spawn(8.5f, 51.5f, down, LOBBY, 1)
        off += Spawn(2.5f, 39.5f, right, LOBBY); off += Spawn(9.5f, 45.5f, left, LOBBY); off += Spawn(3.5f, 53.5f, right, LOBBY)
        // the hall and its rooms
        off += Spawn(7.5f, 22.5f, down, HALL); off += Spawn(8.5f, 16.5f, down, HALL, 1)
        off += Spawn(1.5f, 16.5f, right, 0); off += Spawn(4.5f, 12.5f, down, 0)
        off += Spawn(3.5f, 22.5f, right, 1)
        off += Spawn(2.5f, 28.5f, right, 2); off += Spawn(3.5f, 30.5f, up, 2, 1)
        off += Spawn(13.5f, 16.5f, left, 3); off += Spawn(11.5f, 14.5f, left, 3)
        off += Spawn(14.5f, 19.5f, left, 4); off += Spawn(11.5f, 23.5f, up, 4)
        off += Spawn(13.5f, 28.5f, left, 5); off += Spawn(12.5f, 30.5f, left, 5, 1)
        val civ = listOf(
            Spawn(13.5f, 37.5f, left, LOBBY), Spawn(13.5f, 42.5f, left, LOBBY), Spawn(13.5f, 47.5f, left, LOBBY), Spawn(13.5f, 52.5f, left, LOBBY),
            Spawn(2.5f, 42.5f, 0f, LOBBY), Spawn(2.5f, 45.5f, 0f, LOBBY), Spawn(12.5f, 57.5f, 0f, LOBBY),
        )
        return ShootSpec(map, off, civ, listOf(7 to 32, 8 to 32), listOf(7 to 11, 8 to 11), 7.5f to 63.5f)
    }
}
