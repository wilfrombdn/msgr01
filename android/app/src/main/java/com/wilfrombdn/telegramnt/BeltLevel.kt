package com.wilfrombdn.telegramnt

import java.util.Random

/**
 * The conveyor run of level 3: three raised belts side by side, rows of trouble coming down them, always a way through. New kinds
 * of trouble arrive as you go: crates and spanners, then low barriers you have to jump, holes in the belts, swinging cables and
 * hooks, presses, sparks, barrels, thrown barrels, a runaway forklift two belts wide, a saw sweeping across all three belts, crates
 * dropped from the crane, and slaloms and chains that need leap after leap. Coins lead the way (some hang in the air over barriers).
 */
internal object BeltLevel {
    private val laneX = floatArrayOf(3.2f, 7.0f, 10.8f)
    const val END = 420f

    private class Gen(seed: Int, val obs: MutableList<BeltObs>, val coins: MutableList<BeltCoin>) {
        val r = Random(seed.toLong())
        var s = 13f; var row = 0; var prevFree = 1
        private var bag = mutableListOf<Int>(); private var bagKey: List<Int> = emptyList()

        private fun speed(ss: Float) = 5.2f + 5.2f * (ss.coerceIn(0f, END) / END)
        private fun lanes(vararg l: Int) = l.toList()
        private fun coinRun(lane: Int, s0: Float, n: Int, high: Boolean = false) { for (i in 0 until n) coins += BeltCoin(s0 + i * 1.15f, lane, high) }

        fun fill(upTo: Float) { while (s < upTo) one() }

        private fun one() {
            val p = (s / END).coerceIn(0f, 1f)
            val sp = speed(s)
            val kinds = when {
                row == 2 -> listOf(8)
                row < 2 -> listOf(0)
                p < 0.12f -> listOf(0, 1, 8, 0)
                p < 0.28f -> listOf(0, 1, 2, 8, 9, 13)
                p < 0.50f -> listOf(2, 3, 4, 5, 8, 9, 11, 13)
                p < 0.72f -> listOf(3, 4, 5, 6, 7, 9, 10, 11, 12, 13, 14)
                else -> listOf(4, 6, 7, 9, 10, 11, 12, 13, 14, 5, 3)
            }
            if (bag.isEmpty() || bagKey != kinds) { bag = kinds.shuffled(r).toMutableList(); bagKey = kinds }
            val k = bag.removeAt(0)
            val ph = r.nextFloat() * 3f
            var free = r.nextInt(3)
            var advance = maxOf(8.0f, sp * 0.95f)
            when (k) {
                0 -> { // crates in one or two belts
                    for (l in 0..2) if (l != free && (r.nextInt(3) != 0 || row < 3)) obs += BeltObs(BeltWorld.BOX, s, laneX[l], 0.8f, 0.8f)
                    if (obs.none { it.s == s }) obs += BeltObs(BeltWorld.BOX, s, laneX[(free + 1) % 3], 0.8f, 0.8f)
                    coinRun(free, s - 7f, 4)
                }
                1 -> { // a big spanner and a crate behind it
                    val a = (free + 1) % 3; val b = (free + 2) % 3
                    obs += BeltObs(BeltWorld.TOOL, s, laneX[a], 0.6f, 1.3f)
                    if (r.nextBoolean()) obs += BeltObs(BeltWorld.BOX, s + 2.2f, laneX[b], 0.8f, 0.8f)
                    coinRun(free, s - 7f, 4)
                }
                2, 5 -> { // a cable (or a crane hook) swinging between two belts; the third is clear
                    val a = if (free == 0) 1 else 0; val b = if (free == 2) 1 else 2
                    val mid = (laneX[a] + laneX[b]) / 2f; val amp = (laneX[b] - laneX[a]) / 2f
                    obs += BeltObs(if (k == 2) BeltWorld.CABLE else BeltWorld.HOOK, s, mid, if (k == 2) 0.5f else 0.8f, if (k == 2) 0.5f else 0.8f, amp, 2.6f + r.nextFloat() * 0.8f, ph)
                    coinRun(free, s - 7f, 4)
                }
                3 -> { // a press slamming on one belt and a crate on another
                    val a = (free + 1) % 3; val b = (free + 2) % 3
                    obs += BeltObs(BeltWorld.PRESS, s, laneX[a], 1.3f, 1.0f, 0f, 2.4f, ph, 0.5f)
                    obs += BeltObs(BeltWorld.BOX, s, laneX[b], 0.8f, 0.8f)
                    coinRun(free, s - 7f, 4)
                }
                4 -> { // sparks arcing across two belts
                    free = if (r.nextBoolean()) 0 else 2
                    val a = if (free == 0) 1 else 0; val b = a + 1
                    obs += BeltObs(BeltWorld.ARC, s, (laneX[a] + laneX[b]) / 2f, (laneX[b] - laneX[a]) / 2f + 1.4f, 0.8f, 0f, 2.2f, ph, 0.55f)
                    coinRun(free, s - 7f, 4)
                }
                6 -> { // barrels standing in two belts
                    for (l in 0..2) if (l != free) obs += BeltObs(BeltWorld.BARREL, s + (if (l % 2 == 0) 0f else 1.3f), laneX[l], 0.7f, 0.7f)
                    coinRun(free, s - 7f, 4)
                }
                7 -> { // a barrel thrown down the belt, with a crate beside it
                    val a = (free + 1) % 3; val b = (free + 2) % 3
                    obs += BeltObs(BeltWorld.FLYER, s + 8f, laneX[a], 0.7f, 0.9f)
                    obs += BeltObs(BeltWorld.BOX, s, laneX[b], 0.8f, 0.8f)
                    coinRun(free, s - 7f, 4)
                }
                8 -> { // low barriers across all three belts: jump! (coins hang in the air over them)
                    for (l in 0..2) obs += BeltObs(BeltWorld.SMALL, s, laneX[l], 1.0f, 0.5f)
                    coinRun(1, s - 7f, 3); coinRun(1, s - 2.2f, 5, true)
                    free = 1
                }
                9 -> { // holes in the belts: jump, or move to a belt that is whole
                    val all = r.nextInt(3) == 0
                    for (l in 0..2) if (all || l != free) obs += BeltObs(BeltWorld.GAP, s, laneX[l], 1.45f, 1.1f)
                    if (all) coinRun(1, s - 2.0f, 4, true) else coinRun(free, s - 7f, 4)
                }
                10 -> { // a runaway forklift two belts wide, coming at you
                    val a = if (free == 0) 1 else 0; val b = a + 1
                    obs += BeltObs(BeltWorld.TRUCK, s + 10f, (laneX[a] + laneX[b]) / 2f, (laneX[b] - laneX[a]) / 2f + 0.9f, 1.7f)
                    coinRun(free, s - 7f, 5)
                }
                11 -> { // a saw sweeping back and forth across all three belts
                    obs += BeltObs(BeltWorld.SAWB, s, laneX[1], 0.9f, 0.9f, 3.8f, 2.4f + r.nextFloat() * 0.7f, ph)
                    coinRun(1, s - 7f, 4)
                }
                12 -> { // crates dropped from the crane on two belts, one after the other
                    val a = (free + 1) % 3; val b = (free + 2) % 3
                    obs += BeltObs(BeltWorld.DROP, s, laneX[a], 1.0f, 1.0f, 0f, 2.8f, ph, 0.3f)
                    obs += BeltObs(BeltWorld.DROP, s + 1.5f, laneX[b], 1.0f, 1.0f, 0f, 2.8f, ph + 1.4f, 0.3f)
                    coinRun(free, s - 7f, 4)
                }
                13 -> { // a slalom: three rows close together, the open belt changing each time
                    val path = when (r.nextInt(3)) { 0 -> listOf(0, 1, 2); 1 -> listOf(2, 1, 0); else -> listOf(1, 0, 1) }
                    val g2 = maxOf(6.2f, sp * 0.7f)
                    var ss = s
                    for (f in path) { for (l in 0..2) if (l != f) obs += BeltObs(BeltWorld.BOX, ss, laneX[l], 0.8f, 0.8f); coinRun(f, ss - 4.5f, 3); ss += g2 }
                    free = path.last(); advance = g2 * 2f + maxOf(8f, sp * 0.95f)
                }
                else -> { // a chain: barriers to jump, then straight away a wall of crates to leap round
                    for (l in 0..2) obs += BeltObs(BeltWorld.SMALL, s, laneX[l], 1.0f, 0.5f)
                    coinRun(1, s - 2.2f, 5, true)
                    val g2 = maxOf(7.5f, sp * 0.8f)
                    free = r.nextInt(3)
                    for (l in 0..2) if (l != free) obs += BeltObs(BeltWorld.BOX, s + g2, laneX[l], 0.8f, 0.8f)
                    coinRun(free, s + g2 - 4f, 3)
                    advance = g2 + maxOf(8f, sp * 0.95f)
                }
            }
            prevFree = free
            s += advance; row++
        }
    }

    /** A fresh run every attempt: things are made ahead of you as the belts go on. */
    val warehouse: BeltSpec get() {
        val obs = ArrayList<BeltObs>(); val coins = ArrayList<BeltCoin>()
        val g = Gen(21, obs, coins)
        return BeltSpec(obs, coins, END, laneX, 100) { upTo -> g.fill(upTo) }
    }
}
