package com.wilfrombdn.telegramnt

import org.junit.Assert.assertTrue
import org.junit.Test

class PacAlwaysMovingTest {
    @Test fun theCourierNeverStopsAtAWall() {
        val w = PacWorld(PacMaze.sewer)
        var i = 0
        while (w.phase != PacWorld.Phase.PLAY && i++ < 3000) w.update(0.02f, -1)
        w.update(0.02f, PacWorld.LEFT)
        var still = 0; var frames = 0
        // no input at all for a while: with no turning he must keep going round the maze, never standing at a wall
        for (k in 0 until 1200) {
            if (w.phase != PacWorld.Phase.PLAY) break
            w.update(0.02f, -1)
            frames++; if (!w.moving) still++
        }
        println("pac always moving: frames=$frames still=$still")
        assertTrue(frames > 200)
        assertTrue("stood still $still of $frames frames", still < frames / 12)
    }
}
