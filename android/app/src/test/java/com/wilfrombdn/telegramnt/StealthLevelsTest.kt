package com.wilfrombdn.telegramnt

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves every Dead Drop building can be beaten without ever being seen: the guards, cameras and lasers move the same way
 * every time, so a search over (where I stand, what time it is, which keys I hold) finds a safe route if one exists.
 */
class StealthLevelsTest {
    private val dtRec = 0.2f
    private val horizon = 900   // 3 minutes

    /** Seconds the safe route takes, or -1 if there is none. */
    private fun plan(spec: LevelSpec, speedMul: Float = 1f): Float {
        val ghost = StealthWorld(spec, speedMul, ghost = true)
        val eyes = ArrayList<List<Eye>>(); val lasersOn = ArrayList<BooleanArray>()
        repeat(horizon + 8) {
            eyes += ghost.eyes(); lasersOn += BooleanArray(ghost.lasers.size) { i -> ghost.lasers[i].on(ghost.t) }
            repeat(4) { ghost.update(0.05f, 0f, 0f, 0f) }
        }
        val w = StealthWorld(spec, speedMul, ghost = true)
        val cols = w.cols; val rows = w.rows
        val keyBit = HashMap<Pair<Int, Int>, Int>()
        for (k in w.keys) keyBit[k.x.toInt() to k.y.toInt()] = if (k.ch == 'k') 1 else 2
        fun setMask(m: Int) { w.opened.clear(); if (m and 1 != 0) w.opened += 'D'; if (m and 2 != 0) w.opened += 'E' }
        fun safe(x: Float, y: Float, step: Int, mask: Int): Boolean {
            setMask(mask)
            for (e in eyes[step]) if (w.sees(e, x, y)) return false
            for ((i, l) in ghost.lasers.withIndex()) if (lasersOn[step][i] && l.near(x, y, 0.5f)) return false
            return true
        }
        fun open(c: Int, r: Int, mask: Int): Boolean { setMask(mask); return !w.solidAt(c, r) }

        val start = w.startX.toInt() to w.startY.toInt()
        val goal = w.contactX.toInt() to w.contactY.toInt()
        val seen = HashSet<Long>()
        fun code(c: Int, r: Int, m: Int, s: Int) = (((s * 4L + m) * rows + r) * cols + c)
        val q = ArrayDeque<LongArray>()
        if (!safe(start.first + 0.5f, start.second + 0.5f, 0, 0)) return -1f
        q.add(longArrayOf(start.first.toLong(), start.second.toLong(), 0, 0)); seen += code(start.first, start.second, 0, 0)
        while (q.isNotEmpty()) {
            val (c0, r0, m0, s0) = q.removeFirst()
            val c = c0.toInt(); val r = r0.toInt(); val m = m0.toInt(); val s = s0.toInt()
            if (c to r == goal) return s * dtRec
            if (s + 4 >= horizon) continue
            // wait a moment
            if (safe(c + 0.5f, r + 0.5f, s + 1, m) && seen.add(code(c, r, m, s + 1))) q.add(longArrayOf(c0, r0, m0, s + 1L))
            // walk to a neighbouring tile (4 steps), safe at every step on the way
            for ((dx, dy) in StealthWorld.DIRS4) {
                val nc = c + dx; val nr = r + dy
                if (!open(nc, nr, m)) continue
                var ok = true
                for (k in 1..4) {
                    val f = k / 4f
                    if (!safe(c + 0.5f + dx * f, r + 0.5f + dy * f, s + k, m)) { ok = false; break }
                }
                if (!ok) continue
                val nm = m or (keyBit[nc to nr] ?: 0)
                if (seen.add(code(nc, nr, nm, s + 4))) q.add(longArrayOf(nc.toLong(), nr.toLong(), nm.toLong(), s + 4L))
            }
        }
        return -1f
    }

    @Test fun everyBuildingCanBeBeatenUnseen() {
        val bad = ArrayList<String>()
        for ((i, spec) in StealthLevels.all.withIndex()) {
            val secs = plan(spec)
            println("LEVEL ${i + 1} ${spec.building}: " + if (secs < 0) "NO SAFE ROUTE" else "safe route in ${"%.1f".format(secs)}s")
            if (secs < 0) bad += "${i + 1} ${spec.building}"
        }
        assertTrue("No unseen route in: $bad", bad.isEmpty())
    }

    @Test fun laterRoundsStayFair() {
        val bad = ArrayList<String>()
        for ((i, spec) in StealthLevels.all.withIndex()) if (plan(spec, 1.25f) < 0) bad += "${i + 1} ${spec.building}"
        assertTrue("No unseen route when guards are faster in: $bad", bad.isEmpty())
    }
}

class StealthNaiveTest {
    /** Walk the shortest route at a steady sneak without ever waiting: how often does that get through? Starts at different moments. */
    private fun naive(spec: LevelSpec, delay: Float, mag: Float): String {
        val w = StealthWorld(spec)
        repeat((delay / 0.05f).toInt()) { w.update(0.05f, 0f, 0f, 0f) }
        var route = w.route(w.startX.toInt() to w.startY.toInt(), w.contactX.toInt() to w.contactY.toInt())
        var i = 0
        var steps = 0
        while (w.state == StealthWorld.State.PLAY && steps < 4000) {
            val tgt = route.getOrNull(i) ?: break
            val dx = tgt.first + 0.5f - w.px; val dy = tgt.second + 0.5f - w.py
            val d = kotlin.math.hypot(dx, dy)
            if (d < 0.12f) { i++; continue }
            w.update(0.05f, dx / d, dy / d, mag); steps++
        }
        return w.state.name
    }

    @Test fun printNaive() {
        for ((n, spec) in StealthLevels.all.withIndex()) {
            val sneak = (0 until 12).count { naive(spec, it * 1.1f, 0.5f) == "WON" }
            val run = (0 until 12).count { naive(spec, it * 1.1f, 1.0f) == "WON" }
            println("NAIVE ${n + 1} ${spec.building}: sneaking wins $sneak/12, running wins $run/12")
        }
    }
}
