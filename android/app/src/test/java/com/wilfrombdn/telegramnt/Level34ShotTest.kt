package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class Level34ShotTest {
    private fun save(b: Bitmap, name: String) {
        val big = Bitmap.createScaledBitmap(b, b.width * 3, b.height * 3, false)
        val dir = File("build/shots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { big.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun belt() {
        val w = BeltWorld(BeltLevel.warehouse); val s = BeltScene(w)
        var t = 0f
        fun step(until: Float) { while (t < until) { w.update(0.01f); t += 0.01f; if (w.phase == BeltWorld.Phase.RIDE) w.invuln = 5f } }
        step(1.2f); save(s.render(1.2f), "l3-belt-intro")
        step(6f); save(s.render(6f), "l3-belt-a")
        w.leap(1); step(6.15f); save(s.render(6.15f), "l3-belt-leap")
        w.s = 100f; step(t + 0.1f); save(s.render(7f), "l3-belt-fast")
        w.coins = 100; w.s = w.endS - 0.3f; step(t + 0.3f); save(s.render(8f), "l3-belt-kicker")
        step(t + 0.5f); save(s.render(8.5f), "l3-belt-launch")
        step(t + 1.6f); save(s.render(9.5f), "l3-belt-landed")
        step(t + 1.5f); save(s.render(11f), "l3-belt-jetpack")
    }

    @Test fun beltKinds() {
        val w = BeltWorld(BeltLevel.warehouse); val s = BeltScene(w)
        var t = 0f; while (t < 3f) { w.update(0.01f); t += 0.01f }
        w.spec.extend(450f)
        for (k in listOf(BeltWorld.SMALL, BeltWorld.GAP, BeltWorld.TRUCK, BeltWorld.SAWB, BeltWorld.DROP)) {
            val o = w.spec.obstacles.firstOrNull { it.kind == k } ?: continue
            w.invuln = 99f; w.s = o.s - 3f; w.x = w.spec.laneX[1]
            for (i in 0 until 160) { w.s = o.s - 4f + (if (k == BeltWorld.TRUCK) 0f else 0f); w.update(0.01f); w.s = minOf(w.s, o.s - 3f + i * 0.0f) }
            save(s.render(t + k * 1f), "l3-kind-$k")
        }
    }

    @Test fun fly() {
        val w = FlyWorld(FlySpec()); val s = FlyScene(w)
        var saved = 0
        var t = 0f
        while (t < 40f && saved < 5) {
            w.thrust = w.y > 11f
            w.update(0.02f); t += 0.02f
            val r = w.rockets.firstOrNull { it.state == 1 && it.t > 0.6f }
            if (r != null && saved == 0) { save(s.render(t), "l3-fly-warning"); saved = 1 }
            val r2 = w.rockets.firstOrNull { it.state == 2 && it.sx < 9f }
            if (r2 != null && saved == 1) { save(s.render(t), "l3-fly-rocket"); saved = 2 }
            if (w.hazards.any { it.kind == 0 && it.wx - w.scroll in 5f..9f } && saved == 2) { save(s.render(t), "l3-fly-saw"); saved = 3 }
            if (w.hazards.any { it.kind == 1 && it.wx - w.scroll in 5f..9f } && saved == 3) { save(s.render(t), "l3-fly-gate"); saved = 4 }
        }
        save(s.render(t), "l3-fly-end")
        println("fly shots saved=$saved rocketsSeen=${w.rocketsSeen}")
    }

    @Test fun lobbyShots() {
        val w = ShootWorld(ShootLevel.townHall()); val s = ShootScene(w)
        fun run(secs: Float) { var t = 0f; while (t < secs) { w.update(0.016f, 0f, 0f, 0f, false); t += 0.016f } }
        run(0.2f); for (i in 0..20) s.render(i * 0.016f); save(s.render(0.5f), "l4-lobby-0-walkin")
        run(2.0f); save(s.render(2.5f), "l4-lobby-1-frozen")
        run(0.9f); save(s.render(3.5f), "l4-lobby-2-dive")
        w.diving = true; run(2.0f); w.diving = false; for (i in 0..30) s.render(5f + i * 0.016f); save(s.render(6f), "l4-lobby-3-fight")
        for (n in w.npcs) if (!n.civ && n.room == ShootLevel.LOBBY) n.dead = true
        run(0.1f); w.px = 7.5f; w.py = 34.5f; for (i in 0..30) s.render(7f + i * 0.016f); save(s.render(8f), "l4-hall-door")
        w.px = 7.5f; w.py = 31.0f; w.diving = true; run(0.1f); for (i in 0..30) s.render(9f + i * 0.016f); save(s.render(9.5f), "l4-hall-0")
        w.px = 7.5f; w.py = 22.5f; run(0.1f); save(s.render(10f), "l4-hall-1")
        w.px = 3.0f; w.py = 21.5f; run(0.1f); save(s.render(11f), "l4-room-office")
        w.px = 3.0f; w.py = 28.5f; run(0.1f); save(s.render(11.5f), "l4-room-store")
    }

    @Test fun mayorShots() {
        val w = ShootWorld(ShootLevel.townHall()); val s = ShootScene(w)
        fun run(secs: Float) { var t = 0f; while (t < secs) { w.update(0.016f, 0f, 0f, 0f, false); t += 0.016f } }
        run(4f); for (n in w.npcs) if (!n.civ && !n.hostage) n.dead = true
        run(0.1f); w.px = 7.5f; w.py = 12.6f; run(0.05f)
        for ((k, tt) in listOf(0.4f, 2.0f, 4.0f, 6.0f, 9.0f, 10.4f, 12.8f, 13.6f).withIndex()) { while (w.ft < tt) run(0.016f); for (i in 0..40) s.render(20f + i * 0.016f); save(s.render(21f), "l4-mayor-$k") }
    }

    @Test fun cineHall() {
        val c = CineScene(1)
        for (tt in listOf(1.5f, 5.0f, 8.0f, 12.3f, 14.5f)) { c.t = tt; save(c.render(tt), "l4-hall-${(tt * 10).toInt()}") }
    }

    @Test fun cine() {
        val c = CineScene(0)
        for (tt in listOf(0.2f, 1.2f, 2.6f, 4.2f, 5.8f, 7.6f, 8.4f)) { c.t = tt; save(c.render(tt), "l3-cine-${(tt * 10).toInt()}") }
    }
}
