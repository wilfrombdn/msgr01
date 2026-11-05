package com.wilfrombdn.telegramnt

import java.util.Random

/** The conveyor run of level 3: three raised belts side by side, rows of trouble coming down them, always one safe belt per row. */
internal object BeltLevel {
    private val laneX = floatArrayOf(3.2f, 7.0f, 10.8f)
    const val END = 140f

    private fun build(seed: Int): BeltSpec {
        val r = Random(seed.toLong())
        val obs = ArrayList<BeltObs>()
        var s = 13f; var row = 0
        val w = BeltWorld(BeltSpec(emptyList(), END, laneX))
        while (s < END - 10f) {
            val p = s / END
            val sp = w.speedAt(s)
            val gap = maxOf(8.0f, sp * 0.95f)
            val kinds = when {
                row < 3 -> listOf(0)
                p < 0.30f -> listOf(0, 1, 2)
                p < 0.55f -> listOf(1, 2, 3, 4, 5)
                p < 0.80f -> listOf(2, 3, 4, 5, 6, 7)
                else -> listOf(3, 4, 5, 6, 7, 2)
            }
            val k = kinds[r.nextInt(kinds.size)]
            val ph = r.nextFloat() * 3f
            when (k) {
                0 -> { // crates in one or two belts
                    val free = r.nextInt(3)
                    for (l in 0..2) if (l != free && (r.nextInt(3) != 0 || row < 3)) obs += BeltObs(BeltWorld.BOX, s, laneX[l], 0.8f, 0.8f)
                    if (obs.none { it.s == s }) obs += BeltObs(BeltWorld.BOX, s, laneX[(free + 1) % 3], 0.8f, 0.8f)
                }
                1 -> { // a big spanner and a toolbox
                    val free = r.nextInt(3); val a = (free + 1) % 3; val b = (free + 2) % 3
                    obs += BeltObs(BeltWorld.TOOL, s, laneX[a], 0.6f, 1.3f)
                    if (r.nextBoolean()) obs += BeltObs(BeltWorld.BOX, s + 2.2f, laneX[b], 0.8f, 0.8f)
                }
                2, 5 -> { // a cable (or a crane hook) swinging between two belts; the third is clear
                    val free = r.nextInt(3); val a = if (free == 0) 1 else 0; val b = if (free == 2) 1 else 2
                    val mid = (laneX[a] + laneX[b]) / 2f; val amp = (laneX[b] - laneX[a]) / 2f
                    obs += BeltObs(if (k == 2) BeltWorld.CABLE else BeltWorld.HOOK, s, mid, if (k == 2) 0.5f else 0.8f, if (k == 2) 0.5f else 0.8f, amp, 2.6f + r.nextFloat() * 0.8f, ph)
                }
                3 -> { // a press slamming on one belt and a crate on another
                    val free = r.nextInt(3); val a = (free + 1) % 3; val b = (free + 2) % 3
                    obs += BeltObs(BeltWorld.PRESS, s, laneX[a], 1.3f, 1.0f, 0f, 2.4f, ph, 0.5f)
                    obs += BeltObs(BeltWorld.BOX, s, laneX[b], 0.8f, 0.8f)
                }
                4 -> { // sparks arcing across two belts
                    val free = if (r.nextBoolean()) 0 else 2
                    val a = if (free == 0) 1 else 0; val b = a + 1
                    obs += BeltObs(BeltWorld.ARC, s, (laneX[a] + laneX[b]) / 2f, (laneX[b] - laneX[a]) / 2f + 1.4f, 0.8f, 0f, 2.2f, ph, 0.55f)
                }
                6 -> { // barrels standing in two belts
                    val free = r.nextInt(3)
                    for (l in 0..2) if (l != free) obs += BeltObs(BeltWorld.BARREL, s + (if (l % 2 == 0) 0f else 1.3f), laneX[l], 0.7f, 0.7f)
                }
                else -> { // a barrel thrown down the belt, with a crate beside it
                    val free = r.nextInt(3); val a = (free + 1) % 3; val b = (free + 2) % 3
                    obs += BeltObs(BeltWorld.FLYER, s + 8f, laneX[a], 0.7f, 0.9f)
                    obs += BeltObs(BeltWorld.BOX, s, laneX[b], 0.8f, 0.8f)
                }
            }
            s += gap; row++
        }
        return BeltSpec(obs, END, laneX)
    }

    /** A fresh copy every attempt (the flying barrels remember when they were thrown). */
    val warehouse: BeltSpec get() = build(21)
}
