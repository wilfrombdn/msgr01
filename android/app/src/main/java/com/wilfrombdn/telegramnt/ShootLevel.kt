package com.wilfrombdn.telegramnt

import kotlin.math.PI

// The town hall of Dead Letter level 4: main hall (rows 19-45), hallway with offices (11-17), mayor's office (1-9).
internal object ShootLevel {
    private const val W = 16
    private const val H = 47

    fun townHall(): ShootSpec {
        val g = Array(H) { CharArray(W) { '#' } }
        fun fill(r0: Int, r1: Int, c0: Int, c1: Int, ch: Char = '.') { for (r in r0..r1) for (c in c0..c1) g[r][c] = ch }
        fill(1, 9, 1, 14)                         // mayor's office
        fill(11, 17, 1, 14)                       // hallway
        fill(19, 45, 1, 14)                       // main hall
        g[10][7] = 'D'; g[10][8] = 'D'            // hallway -> office
        g[18][7] = 'D'; g[18][8] = 'D'            // hall -> hallway
        // office partitions in the hallway (with doorways)
        for (r in 11..13) { g[r][5] = '#'; g[r][10] = '#' }
        for (r in 15..17) { g[r][5] = '#'; g[r][10] = '#' }
        // pillars in the main hall
        for (r in intArrayOf(23, 29, 35, 41)) { g[r][4] = 'c'; g[r][11] = 'c' }
        g[32][7] = 'c'; g[32][8] = 'c'
        // desks
        for (c in 2..3) g[26][c] = 'd'; for (c in 12..13) g[26][c] = 'd'
        for (c in 2..3) g[38][c] = 'd'; for (c in 12..13) g[38][c] = 'd'
        for (c in 6..9) g[21][c] = 'd'
        // office furniture
        for (c in 5..10) g[3][c] = 'd'
        g[4][3] = 'c'; g[4][12] = 'c'
        g[43][8] = 'S'; g[2][8] = 'M'
        val map = g.map { String(it) }
        val up = -PI.toFloat() / 2; val down = PI.toFloat() / 2
        val off = listOf(
            Spawn(3.5f, 22.5f, down, 0), Spawn(12.5f, 22.5f, down, 0), Spawn(7.5f, 25.5f, down, 0), Spawn(2.5f, 31.5f, 0f, 0),
            Spawn(13.0f, 31.5f, PI.toFloat(), 0), Spawn(7.5f, 36.5f, down, 0), Spawn(3.5f, 40.5f, 0f, 0),
            Spawn(3.0f, 14.5f, 0f, 1), Spawn(12.5f, 12.5f, PI.toFloat(), 1), Spawn(7.5f, 15.5f, down, 1), Spawn(12.5f, 16.5f, PI.toFloat(), 1),
        )
        val civ = listOf(
            Spawn(5.5f, 28.5f, 0f, 0), Spawn(10.5f, 28.5f, 0f, 0), Spawn(6.5f, 34.5f, 0f, 0),
            Spawn(9.5f, 40.5f, 0f, 0), Spawn(2.5f, 36.5f, 0f, 0), Spawn(13.0f, 24.0f, 0f, 0),
        )
        return ShootSpec(map, off, civ, listOf(ShootRoom(listOf(7 to 18, 8 to 18)), ShootRoom(listOf(7 to 10, 8 to 10))), 9.0f)
    }
}
