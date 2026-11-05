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
        w.s = w.spec.endS - 0.3f; step(t + 0.3f); save(s.render(8f), "l3-belt-kicker")
        step(t + 0.5f); save(s.render(8.5f), "l3-belt-launch")
        step(t + 1.6f); save(s.render(9.5f), "l3-belt-landed")
        step(t + 1.5f); save(s.render(11f), "l3-belt-jetpack")
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

    @Test fun cine() {
        val c = CineScene(0)
        for (tt in listOf(0.2f, 1.2f, 2.6f, 4.2f, 5.8f, 7.6f, 8.4f)) { c.t = tt; save(c.render(tt), "l3-cine-${(tt * 10).toInt()}") }
    }
}
