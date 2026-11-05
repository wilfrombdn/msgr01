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

    @Test fun fly() {
        val w = FlyWorld(FlySpec()); val s = FlyScene(w)
        w.thrust = true
        for (i in 0 until 200) { w.thrust = w.y > 9f; w.update(0.016f) }
        save(s.render(1f), "l3-fly")
    }

    @Test fun shoot() {
        val w = ShootWorld(ShootLevel.townHall()); val s = ShootScene(w)
        for (i in 0 until 40) w.update(0.016f, 0f, 0f, 0f, 0f, 0f, 0f)
        save(s.render(0.5f), "l4-intro")
        for (i in 0 until 40) w.update(0.016f, 0f, 0f, 0f, 0f, 0f, 0f)
        save(s.render(1.0f), "l4-shot")
        w.px = 8f; w.py = 8f
        for (n in w.npcs.filter { !it.civ }) n.dead = true
        w.update(0.016f, 0f, 0f, 0f, 0f, 0f, 0f)
        w.update(0.016f, 0f, -1f, 1f, 0f, 0f, 0f)
        repeat(300) { w.update(0.016f, 0f, 0f, 0f, 0f, 0f, 0f); s.render(it * 0.016f) }
        save(s.render(5f), "l4-finale-a")
        repeat(300) { w.update(0.016f, 0f, 0f, 0f, 0f, 0f, 0f); s.render(5f + it * 0.016f) }
        save(s.render(10f), "l4-finale-b")
    }
}
