package com.wilfrombdn.telegramnt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacWorldTest {
    private fun world() = PacWorld(PacMaze.sewer)
    private fun play(w: PacWorld) { var t = 0f; while (w.phase != PacWorld.Phase.PLAY && t < 30f) { w.update(0.02f, -1); t += 0.02f } }
    private fun setChase(w: PacWorld, on: Boolean) { val f = PacWorld::class.java.getDeclaredField("chase"); f.isAccessible = true; f.setBoolean(w, on) }

    @Test fun mazeIsWellFormed() {
        val m = PacMaze.sewer.maze
        assertTrue("all rows the same width", m.all { it.length == m[0].length })
        val w = world()
        println("PAC maze ${w.w}x${w.h}, ${w.total} key fragments, start (${w.startX},${w.startY}), door (${w.doorX},${w.doorY})")
        assertTrue(w.total in 60..200)
    }

    @Test fun everyFragmentAndTheDoorCanBeReached() {
        val w = world()
        val seen = HashSet<Pair<Int, Int>>(); val q = ArrayDeque<Pair<Int, Int>>()
        q.add(w.startX to w.startY); seen.add(w.startX to w.startY)
        while (q.isNotEmpty()) {
            val (x, y) = q.removeFirst()
            for (d in 0..3) {
                var nx = x + PacWorld.DX[d]; val ny = y + PacWorld.DY[d]
                if (y == 10) nx = (nx + w.w) % w.w
                val c = w.tile(nx, ny)
                if ((c == '.' || c == 'D') && (nx to ny) !in seen) { seen.add(nx to ny); q.add(nx to ny) }
            }
        }
        for (y in 0 until w.h) for (x in 0 until w.w) if (w.tile(x, y) == '.') assertTrue("($x,$y) is cut off", (x to y) in seen)
        assertTrue("the exit door is reachable", (w.doorX to w.doorY) in seen)
    }

    @Test fun noDeadEndsForThePlayer() {
        val w = world()
        for (y in 0 until w.h) for (x in 0 until w.w) if (w.tile(x, y) == '.') {
            var open = 0
            for (d in 0..3) { var nx = x + PacWorld.DX[d]; val ny = y + PacWorld.DY[d]; if (y == 10) nx = (nx + w.w) % w.w; if (w.tile(nx, ny) == '.') open++ }
            assertTrue("($x,$y) is a dead end", open >= 2)
        }
    }

    @Test fun introThenReadyThenPlay() {
        val w = world()
        assertEquals(PacWorld.Phase.INTRO, w.phase)
        var t = 0f; while (w.phase == PacWorld.Phase.INTRO && t < 20f) { w.update(0.02f, -1); t += 0.02f }
        assertEquals(PacWorld.Phase.READY, w.phase); assertTrue(w.lidClose >= 1f); assertTrue(w.ghosts.all { it.visible })
        while (w.phase == PacWorld.Phase.READY && t < 30f) { w.update(0.02f, -1); t += 0.02f }
        assertEquals(PacWorld.Phase.PLAY, w.phase)
    }

    @Test fun scatterThenChaseOnTheArcadeTimetable() {
        val w = world(); play(w)
        assertFalse(w.chase)
        var t = 0f
        while (t < 6.9f && w.phase == PacWorld.Phase.PLAY) { w.update(0.02f, -1); t += 0.02f }
        if (w.phase != PacWorld.Phase.PLAY) return
        assertFalse("still scattering at 6.9 s", w.chase)
        while (t < 7.3f && w.phase == PacWorld.Phase.PLAY) { w.update(0.02f, -1); t += 0.02f }
        if (w.phase == PacWorld.Phase.PLAY) assertTrue("chasing after 7 s", w.chase)
    }

    @Test fun theChaseTargetsAreTheArcadeOnes() {
        val w = world(); play(w)
        w.px = 10.5f; w.py = 17.5f; w.dir = PacWorld.RIGHT
        setChase(w, true)
        assertEquals(10 to 17, w.targetTile(PacWorld.BLINKY))
        assertEquals(14 to 17, w.targetTile(PacWorld.PINKY))                  // four tiles ahead
        w.dir = PacWorld.UP
        assertEquals(6 to 13, w.targetTile(PacWorld.PINKY))                   // the up-direction bug: four up AND four left
        val c = w.ghosts[PacWorld.CLYDE]; c.x = 2.5f; c.y = 1.5f
        assertEquals(10 to 17, w.targetTile(PacWorld.CLYDE))                  // far away: he chases
        c.x = 9.5f; c.y = 17.5f
        assertEquals(0 to (w.h + 2), w.targetTile(PacWorld.CLYDE))            // close: he runs to his corner
    }

    @Test fun inkyUsesBlinkyToFlankYou() {
        val w = world(); play(w)
        w.px = 10.5f; w.py = 17.5f; w.dir = PacWorld.RIGHT
        setChase(w, true)
        val b = w.ghosts[PacWorld.BLINKY]; b.x = 8.5f; b.y = 15.5f
        // two ahead of me is (12,17); twice the vector from Blinky (8,15) to it is (16,19)
        assertEquals(16 to 19, w.targetTile(PacWorld.INKY))
    }

    @Test fun aCopNeverTurnsStraightBack() {
        val w = world(); play(w)
        val g = w.ghosts[PacWorld.BLINKY]
        setChase(w, true)
        w.px = 14.5f; w.py = 17.5f   // I am to the right of the crossing, so going straight on is nearest
        assertEquals(PacWorld.RIGHT, w.choose(g, 5, 17, PacWorld.RIGHT))
        w.px = 1.5f; w.py = 17.5f    // I am behind him: he may not turn round, so he picks the next nearest (up, before down)
        val d = w.choose(g, 5, 17, PacWorld.RIGHT)
        assertTrue(d != PacWorld.LEFT)
    }

    @Test fun tiesAreBrokenUpLeftDownRight() {
        val w = world(); play(w)
        val g = w.ghosts[PacWorld.BLINKY]
        setChase(w, true)
        // standing at (5,17) arriving from the left, target straight above and below are equally far when the target is at (5,17) itself
        w.px = 5.5f; w.py = 17.5f
        // neighbours: up (5,16) dist 1, down (5,18)? blocked, right (6,17) dist 1: up wins the tie
        assertEquals(PacWorld.UP, w.choose(g, 5, 17, PacWorld.RIGHT))
    }

    @Test fun theCopsReverseWhenTheModeChanges() {
        val w = world(); play(w)
        var t = 0f; var reversed = false
        var lastDir = w.ghosts[PacWorld.BLINKY].dir
        while (t < 12f && w.phase == PacWorld.Phase.PLAY) {
            w.update(0.02f, -1); t += 0.02f
            val d = w.ghosts[PacWorld.BLINKY].dir
            if (t > 7f && d == (lastDir + 2) % 4) reversed = true
            lastDir = d
        }
        assertTrue("Blinky turned round when scatter ended", reversed || w.phase != PacWorld.Phase.PLAY)
    }

    @Test fun aPlayerWhoStandsStillGetsCaught() {
        val w = world(); play(w)
        var t = 0f; while (t < 120f && w.phase == PacWorld.Phase.PLAY) { w.update(0.02f, -1); t += 0.02f }
        assertEquals(PacWorld.Phase.CAUGHT, w.phase)
    }

    @Test fun collectingEverythingOpensTheDoor() {
        val w = world(); play(w)
        for (y in 0 until w.h) for (x in 0 until w.w) w.frag[y][x] = false
        w.left = 1; w.frag[17][9] = true; w.px = 8.6f; w.py = 17.5f; w.dir = PacWorld.RIGHT; w.wantDir = PacWorld.RIGHT
        var t = 0f; while (t < 1f && !w.doorOpen && w.phase == PacWorld.Phase.PLAY) { w.update(0.02f, PacWorld.RIGHT); t += 0.02f }
        assertTrue(w.doorOpen)
    }
}
