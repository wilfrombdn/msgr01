package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** The night palette shared by every pixel-art scene. */
internal object PX {
        const val K = 0xFF03050A.toInt()
        const val N0 = 0xFF070B18.toInt()
        const val N1 = 0xFF0B1230.toInt()
        const val N2 = 0xFF121D47.toInt()
        const val N3 = 0xFF1B2B66.toInt()
        const val B1 = 0xFF2B4FA8.toInt()
        const val B2 = 0xFF4C7DFF.toInt()
        const val ICE = 0xFFA9CCFF.toInt()
        const val W = 0xFFFFFFFF.toInt()
        const val CYAN = 0xFF29F0FF.toInt()
        const val MAG = 0xFFFF38D6.toInt()
        const val WARM = 0xFFFFD27A.toInt()
        const val RED = 0xFFFF3B55.toInt()
        const val BLUE = 0xFF3B6BFF.toInt()
        const val LAMP = 0xFFDCEBFF.toInt()
        const val GOLD = 0xFFFFD84D.toInt()
        const val SKIN = 0xFFE2B590.toInt()
        const val NIGHT = 0xFF040824.toInt()
}

/** Drawing tools shared by the pixel-art scenes: paints, little helpers, dither glows, and the people (police, the clerk, the vendor). */
internal open class PixelSprites {
    protected val p = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL; isFilterBitmap = false }
    protected val punch = Paint().apply { isAntiAlias = false; isFilterBitmap = false; xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    protected val plain = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    // ---------- helpers ----------
    protected fun rect(c: Canvas, x: Int, y: Int, w: Int, h: Int, color: Int) { p.style = Paint.Style.FILL; p.color = color; c.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), p) }
    protected fun px(c: Canvas, x: Int, y: Int, color: Int) = rect(c, x, y, 1, 1, color)
    protected fun a(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    protected fun hash(x: Int, y: Int, s: Int): Int {
        var v = x * 374761393 + y * 668265263 + s * 1274126177
        v = (v xor (v ushr 13)) * 1274126177
        return (v xor (v ushr 16)) and 0x7fffffff
    }
    protected val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    /** A soft pool of dither dots as a small bitmap (used as a glow, or as a cut-out of the night). */
    protected fun glowBitmap(r: Int, color: Int, maxA: Int): Bitmap {
        val bm = Bitmap.createBitmap(r * 2, r * 2, Bitmap.Config.ARGB_8888)
        val pix = IntArray(r * 2 * r * 2)
        for (y in 0 until r * 2) for (x in 0 until r * 2) {
            val d = hypot(x + 0.5f - r, y + 0.5f - r) / r
            if (d >= 1f) continue
            val t = (1f - d) * 3.4f
            val q = floor(t + bayer[(y and 3) * 4 + (x and 3)] / 16f).toInt().coerceIn(0, 3)
            if (q > 0) pix[y * r * 2 + x] = a(color, q * maxA / 3)
        }
        bm.setPixels(pix, 0, r * 2, 0, 0, r * 2, r * 2)
        return bm
    }
    // ---------- people ----------
    internal class Outfit(
        val skin: Int, val hair: Int, val hat: Int?, val brim: Int?, val shirt: Int, val coat: Int?, val pants: Int, val shoes: Int,
        val belt: Int?, val badge: Boolean = false, val glasses: Boolean = false, val tie: Boolean = false, val apron: Int? = null,
        val radio: Boolean = false,
        val straps: Int? = null,      // braces over the shirt
    )
    protected val police = Outfit(PX.SKIN, 0xFF1A1420.toInt(), 0xFF111B45.toInt(), 0xFF05081A.toInt(), 0xFF2F58C8.toInt(), null, 0xFF0E1736.toInt(), PX.K, PX.K, badge = true, radio = true)
    /** You: an old-timey clerk. Blond hair, round glasses, a white shirt with braces, khaki trousers, brown shoes. */
    protected val clerk = Outfit(PX.SKIN, 0xFFE8C860.toInt(), null, null, 0xFFF4F0E6.toInt(), null, 0xFFB8A070.toInt(), 0xFF4A2E1B.toInt(), null, glasses = true, straps = 0xFF5A3A22.toInt())
    protected val vendor = Outfit(PX.SKIN, 0xFF59647A.toInt(), 0xFF59647A.toInt(), 0xFF3A4458.toInt(), 0xFFE8E0C8.toInt(), null, 0xFF3A4458.toInt(), PX.K, null, apron = 0xFF2E8F66.toInt())

    /** Draws one person standing with their feet at (fx, fy). [dir]: 0 away from you, 1 right, 2 toward you, 3 left. */
    protected fun folk(c: Canvas, fx: Int, fy: Int, dir: Int, frame: Int, o: Outfit, torch: Boolean, letter: Boolean, hd: Int = 0, cross: Int = -1, pose: Int = 0, pdir: Int = 0, sorrow: Boolean = false, handsUp: Boolean = false) {
        rect(c, fx - 5, fy - 1, 11, 3, a(PX.K, 0x70))     // shadow
        c.save()
        if (dir == 3) c.scale(-1f, 1f, fx.toFloat() + 0.5f, 0f)
        val side = dir == 1 || dir == 3
        val coatBottom = if (o.coat != null) 4 else 0
        if (!side) {
            // legs
            val lyL = if (frame == 1) 1 else 0
            rect(c, fx - 3, fy - 6 + lyL, 3, 5 - lyL, o.pants); rect(c, fx + 1, fy - 6, 3, 5, o.pants)
            rect(c, fx - 3, fy - 1, 3, 1, o.shoes); rect(c, fx + 1, fy - 1, 3, 1, o.shoes)
            // torso (and a long coat)
            rect(c, fx - 4, fy - 12, 9, 6 + coatBottom, o.coat ?: o.shirt)
            if (o.coat != null) {
                rect(c, fx - 4, fy - 12, 1, 6 + coatBottom, o.coat - 0x00181818); rect(c, fx, fy - 8, 1, 2 + coatBottom, o.coat - 0x00202020)
                if (dir == 2) { rect(c, fx - 1, fy - 12, 3, 3, o.shirt); if (o.tie) rect(c, fx, fy - 11, 1, 4, PX.RED) }
            }
            if (o.apron != null && dir == 2) rect(c, fx - 3, fy - 10, 7, 6, o.apron)
            if (o.belt != null) rect(c, fx - 4, fy - 7, 9, 1, o.belt)
            if (o.badge && dir == 2) { px(c, fx + 2, fy - 10, PX.GOLD); px(c, fx + 2, fy - 9, PX.GOLD) }
            if (o.straps != null && o.coat == null) {
                rect(c, fx - 3, fy - 12, 1, 6, o.straps); rect(c, fx + 3, fy - 12, 1, 6, o.straps)
                if (dir == 2) { px(c, fx - 3, fy - 7, PX.GOLD); px(c, fx + 3, fy - 7, PX.GOLD) } else { rect(c, fx - 2, fy - 10, 5, 1, o.straps) }   // buttons, or the cross at the back
                rect(c, fx - 4, fy - 7, 9, 1, lite0(o.pants, -30))                                                                                       // the waistband
            }
            if (o.radio) { rect(c, fx - 4, fy - 12, 2, 3, PX.K); px(c, fx - 3, fy - 12, PX.RED) }
            // arms
            val sleeve = o.coat ?: o.shirt
            val swing = if (frame == 1) 1 else 0
            if (handsUp) {   // both arms straight up, hands open: no arms left by his sides
                rect(c, fx - 6, fy - 19, 2, 8, sleeve); rect(c, fx + 5, fy - 19, 2, 8, sleeve)
                rect(c, fx - 6, fy - 21, 2, 2, o.skin); rect(c, fx + 5, fy - 21, 2, 2, o.skin)
            } else if (sorrow) {   // shoulders down, hands clasped in front of him
                rect(c, fx - 6, fy - 11, 2, 4, sleeve); rect(c, fx - 5, fy - 8, 3, 2, sleeve); rect(c, fx + 5, fy - 11, 2, 4, sleeve); rect(c, fx + 3, fy - 8, 3, 2, sleeve)
                rect(c, fx - 2, fy - 8, 5, 2, o.skin)
            } else rect(c, fx - 6, fy - 12 + swing, 2, 6, sleeve)
            if (!sorrow && !handsUp) rect(c, fx - 6, fy - 6 + swing, 2, 1, o.skin)
            if (sorrow || handsUp) Unit                                               // both hands are already drawn, clasped
            else if (pose != 0) poseArm(c, fx, fy, pose, pdir, sleeve, o)
            else if (cross < 0 || dir != 2) { rect(c, fx + 5, fy - 12 - swing, 2, 6, sleeve); rect(c, fx + 5, fy - 6 - swing, 2, 1, o.skin) }
            else {   // crossing himself: forehead, chest, left shoulder, right shoulder
                val hx = intArrayOf(fx, fx, fx - 4, fx + 4)[cross]; val hy = intArrayOf(fy - 16, fy - 9, fy - 11, fy - 11)[cross]
                val sx = fx + 5; val sy = fy - 11
                for (i in 0..6) rect(c, sx + (hx - sx) * i / 6, sy + (hy - sy) * i / 6, 2, 2, sleeve)
                rect(c, hx - 1, hy - 1, 2, 2, o.skin)
            }
            if (letter && dir == 2) { rect(c, fx + 4, fy - 8, 5, 4, PX.K); rect(c, fx + 5, fy - 7, 3, 2, PX.W); px(c, fx + 6, fy - 7, PX.RED) }
            // head
            if (dir == 0) rect(c, fx - 3, fy - 18 + hd, 7, 7, o.hair) else {
                rect(c, fx - 3, fy - 18 + hd, 7, 7, o.skin)
                rect(c, fx - 3, fy - 18 + hd, 7, 2, o.hair); rect(c, fx - 3, fy - 18 + hd, 1, 4, o.hair); rect(c, fx + 3, fy - 18 + hd, 1, 4, o.hair)
                px(c, fx - 2, fy - 15 + hd, PX.K); px(c, fx + 2, fy - 15 + hd, PX.K)
                if (o.glasses) { rect(c, fx - 3, fy - 15 + hd, 3, 1, PX.ICE); rect(c, fx + 1, fy - 15 + hd, 3, 1, PX.ICE); px(c, fx - 2, fy - 15 + hd, PX.K); px(c, fx + 2, fy - 15 + hd, PX.K) }
                px(c, fx, fy - 13 + hd, o.skin - 0x00301818)
            }
            if (sorrow && dir == 2) {   // eyes shut, looking at the ground
                px(c, fx - 2, fy - 15 + hd, o.skin); px(c, fx + 2, fy - 15 + hd, o.skin); px(c, fx, fy - 13 + hd, o.skin)
                rect(c, fx - 3, fy - 14 + hd, 2, 1, lite0(o.skin, -70)); rect(c, fx + 2, fy - 14 + hd, 2, 1, lite0(o.skin, -70))
            }
            if (sorrow && dir == 0) rect(c, fx - 2, fy - 12 + hd, 5, 1, o.skin)          // the back of his neck as his head bows
            val hb = if (sorrow) 1 else 0                                                  // his hat tips forward
            if (o.hat != null) {
                rect(c, fx - 4, fy - 20 + hd + hb, 9, 3, o.hat); rect(c, fx - 4, fy - 20 + hd + hb, 9, 1, o.hat + 0x00101820)
                if (dir == 2 && o.brim != null) rect(c, fx - 4, fy - 17 + hd + hb, 9, 1 + hb, o.brim)
                if (o.badge && dir == 2) px(c, fx, fy - 19 + hd + hb, PX.GOLD)
            }
            if (torch && dir == 2) { rect(c, fx + 5, fy - 7, 2, 3, PX.K); px(c, fx + 5, fy - 4, PX.W); px(c, fx + 6, fy - 4, PX.LAMP) }
            if (pose == 2 && dir == 2) cigarette(c, fx, fy)
        } else {
            val step = frame == 1
            // legs, striding
            if (step) { rect(c, fx - 4, fy - 6, 3, 5, o.pants); rect(c, fx + 1, fy - 6, 3, 5, o.pants); rect(c, fx - 5, fy - 1, 3, 1, o.shoes); rect(c, fx + 2, fy - 1, 3, 1, o.shoes) }
            else { rect(c, fx - 2, fy - 6, 5, 5, o.pants); rect(c, fx - 2, fy - 1, 6, 1, o.shoes) }
            // torso
            rect(c, fx - 3, fy - 12, 7, 6 + coatBottom, o.coat ?: o.shirt)
            if (o.coat != null) rect(c, fx - 3, fy - 8, 1, 2 + coatBottom, o.coat - 0x00202020)
            if (o.apron != null) rect(c, fx - 1, fy - 10, 5, 6, o.apron)
            if (o.belt != null) rect(c, fx - 3, fy - 7, 7, 1, o.belt)
            if (o.radio) { rect(c, fx - 3, fy - 12, 2, 3, PX.K); px(c, fx - 3, fy - 12, PX.RED) }
            // the near arm, forward
            rect(c, fx - 1, fy - 12, 2, 6, (o.coat ?: o.shirt) - 0x00101010); rect(c, fx - 1, fy - 6, 2, 1, o.skin)
            if (torch) { rect(c, fx + 1, fy - 8, 4, 2, PX.K); rect(c, fx + 4, fy - 8, 2, 2, PX.W); px(c, fx + 6, fy - 8, PX.LAMP) }
            if (letter) { rect(c, fx + 1, fy - 9, 5, 4, PX.K); rect(c, fx + 2, fy - 8, 3, 2, PX.W); px(c, fx + 3, fy - 8, PX.RED) }
            // head, in profile
            rect(c, fx - 2, fy - 18 + hd, 7, 7, o.skin)
            rect(c, fx - 3, fy - 18 + hd, 5, 7, o.hair); rect(c, fx - 2, fy - 18 + hd, 7, 2, o.hair)
            px(c, fx + 3, fy - 15 + hd, PX.K); px(c, fx + 5, fy - 14 + hd, o.skin - 0x00301818)
            if (o.glasses) { px(c, fx + 4, fy - 15 + hd, PX.ICE); px(c, fx + 5, fy - 15 + hd, PX.ICE) }
            if (o.hat != null) {
                rect(c, fx - 3, fy - 20 + hd, 8, 3, o.hat); rect(c, fx - 3, fy - 20 + hd, 8, 1, o.hat + 0x00101820)
                if (o.brim != null) rect(c, fx + 2, fy - 17 + hd, 5, 1, o.brim)
                if (o.badge) px(c, fx + 3, fy - 19 + hd, PX.GOLD)
            }
        }
        c.restore()
    }


    protected val swatOutfit = Outfit(PX.SKIN, 0xFF111418.toInt(), 0xFF1D2430.toInt(), 0xFF0B0F16.toInt(), 0xFF1E2632.toInt(), null, 0xFF141A24.toInt(), PX.K, 0xFF0B0F16.toInt(), radio = true)

    /** A SWAT officer in a helmet, visor and armour plates. */
    protected fun swatman(c: Canvas, fx: Int, fy: Int, dir: Int, frame: Int) {
        folk(c, fx, fy, dir, frame, swatOutfit, false, false)
        if (dir == 2) {
            rect(c, fx - 3, fy - 16, 7, 2, PX.K); rect(c, fx - 2, fy - 16, 2, 1, 0xFF29F0FF.toInt()); rect(c, fx + 1, fy - 16, 2, 1, 0xFF29F0FF.toInt())   // visor
            rect(c, fx - 4, fy - 20, 9, 1, 0xFF3A4458.toInt())
            rect(c, fx - 4, fy - 12, 9, 5, 0xFF2B3442.toInt()); rect(c, fx - 4, fy - 12, 9, 1, 0xFF4A5470.toInt()); rect(c, fx - 3, fy - 8, 2, 2, 0xFF141A24.toInt()); rect(c, fx + 2, fy - 8, 2, 2, 0xFF141A24.toInt())
            rect(c, fx - 1, fy - 11, 3, 2, 0xFF3A4458.toInt())
        } else {
            rect(c, fx - 2, fy - 16, 6, 2, PX.K); px(c, fx + 3, fy - 16, 0xFF29F0FF.toInt())
            rect(c, fx - 3, fy - 12, 7, 5, 0xFF2B3442.toInt()); rect(c, fx - 3, fy - 12, 7, 1, 0xFF4A5470.toInt())
        }
    }

    /** A rifle held at the shoulder pointing along [ang], with a red laser dot on the target when [laser] is set. */
    protected fun rifle(c: Canvas, sx: Int, sy: Int, ang: Float, laser: Boolean, target: Pair<Int, Int>) {
        val ca = Math.cos(ang.toDouble()).toFloat(); val sa = Math.sin(ang.toDouble()).toFloat()
        for (i in -3..13) {
            val x = sx + (ca * i).roundToInt(); val y = sy + (sa * i).roundToInt()
            rect(c, x, y, 2, 2, if (i < 2) 0xFF6A4A32.toInt() else if (i == 7 || i == 8) 0xFFB4BED6.toInt() else 0xFF5A6478.toInt()); px(c, x, y, 0xFF8A94AC.toInt())
        }
        rect(c, sx + (ca * 5).roundToInt() + 1, sy + (sa * 5).roundToInt() + 2, 2, 3, 0xFF0B0F16.toInt())
        if (laser) {
            val mx = sx + (ca * 14).roundToInt(); val my = sy + (sa * 14).roundToInt()
            val len = Math.hypot((target.first - mx).toDouble(), (target.second - my).toDouble()).toInt().coerceAtLeast(1)
            for (k in 0 until len step 4) px(c, mx + (target.first - mx) * k / len, my + (target.second - my) * k / len, a(PX.RED, 0x90))
            rect(c, target.first - 1, target.second - 1, 3, 3, PX.RED); px(c, target.first, target.second, PX.W)
        }
    }


    /** A speech bubble with a pointer to the head at (hx, topY). The text types itself out, [shown] letters so far. */
    protected fun bubble(c: Canvas, text: String, hx: Int, topY: Int, shown: Int, maxX: Int) {
        p.typeface = android.graphics.Typeface.MONOSPACE; p.textSize = 8f; p.textAlign = android.graphics.Paint.Align.LEFT; p.style = android.graphics.Paint.Style.FILL
        val maxW = 112f
        val lines = ArrayList<String>(); var cur = ""
        for (word in text.split(" ")) { val tryL = if (cur.isEmpty()) word else "$cur $word"; if (p.measureText(tryL) > maxW && cur.isNotEmpty()) { lines += cur; cur = word } else cur = tryL }
        if (cur.isNotEmpty()) lines += cur
        val bw = (lines.maxOf { p.measureText(it) } + 8f).toInt(); val bh = lines.size * 9 + 6
        val x0 = (hx - bw / 2).coerceIn(3, maxX - bw - 3); val y0 = topY - 5 - bh
        rect(c, x0 - 1, y0 - 1, bw + 2, bh + 2, PX.K); rect(c, x0, y0, bw, bh, PX.W)
        val ax = hx.coerceIn(x0 + 4, x0 + bw - 5)
        rect(c, ax - 3, y0 + bh, 7, 1, PX.K); rect(c, ax - 2, y0 + bh, 5, 1, PX.W)
        rect(c, ax - 2, y0 + bh + 1, 5, 1, PX.K); rect(c, ax - 1, y0 + bh + 1, 3, 1, PX.W)
        rect(c, ax - 1, y0 + bh + 2, 3, 1, PX.K); px(c, ax, y0 + bh + 2, PX.W); px(c, ax, y0 + bh + 3, PX.K)
        p.color = PX.K
        var left = shown
        for ((i, ln) in lines.withIndex()) { if (left <= 0) break; c.drawText(ln.take(left), (x0 + 4).toFloat(), (y0 + 9 + i * 9).toFloat(), p); left -= ln.length + 1 }
        p.typeface = null
    }

    /** The clock the scenes advance each frame, for little animations (a wrench swinging, smoke rising). */
    protected var animT = 0f

    protected val worker = Outfit(PX.SKIN, 0xFF3A2A1E.toInt(), 0xFFFFC82E.toInt(), 0xFFB88A12.toInt(), 0xFF4C6A8C.toInt(), null, 0xFF3F5A78.toInt(), PX.K, 0xFF6A4A2A.toInt())
    /** A police officer in one of the four ghost colours (cap, shirt and a coloured glow come from [color]). */
    protected fun policeIn(color: Int) = Outfit(PX.SKIN, 0xFF1A1420.toInt(), color, 0xFF05081A.toInt(), color, null, 0xFF0E1736.toInt(), PX.K, PX.K, badge = true, radio = true)

    /** The right arm doing something: 1 wrenching up and down, 2 bringing a cigarette to his mouth, 3 pointing along [pdir]. */
    private fun poseArm(c: Canvas, fx: Int, fy: Int, pose: Int, pdir: Int, sleeve: Int, o: Outfit) {
        val sx = fx + 5; val sy = fy - 11
        when (pose) {
            1 -> {
                val up = ((animT / 0.22f).toInt() % 2 == 0)
                val hx = fx + 4; val hy = if (up) fy - 19 else fy - 14
                for (i in 0..6) rect(c, sx + (hx - sx) * i / 6, sy + (hy - sy) * i / 6, 2, 2, sleeve)
                rect(c, hx - 1, hy - 1, 2, 2, o.skin)
                rect(c, hx - 1, hy - 4, 3, 3, 0xFF9AA4B5.toInt()); px(c, hx - 1, hy - 5, 0xFF9AA4B5.toInt()); px(c, hx + 1, hy - 5, 0xFF9AA4B5.toInt())    // a spanner
                if (!up) { px(c, hx + 2, hy - 7, PX.WARM); px(c, hx + 3, hy - 8, PX.W) }                                                             // a spark off the pipe
            }
            2 -> {
                val hx = fx + 3; val hy = fy - 12
                for (i in 0..5) rect(c, sx + (hx - sx) * i / 5, sy + (hy - sy) * i / 5, 2, 2, sleeve)
                rect(c, hx, hy - 1, 2, 2, o.skin)
            }
            3 -> {
                if (pdir == PacWorld.RIGHT) { rect(c, sx, sy, 8, 2, sleeve); rect(c, sx + 8, sy, 3, 2, o.skin) }
                else { rect(c, fx - 14, sy, 8, 2, sleeve); rect(c, fx - 17, sy, 3, 2, o.skin) }
            }
        }
    }

    /** A lit cigarette at the corner of his mouth, with a glowing tip and smoke rising. */
    private fun cigarette(c: Canvas, fx: Int, fy: Int) {
        rect(c, fx + 1, fy - 13, 5, 2, PX.W); rect(c, fx + 1, fy - 12, 5, 1, 0xFFD8DCE8.toInt())
        val glow = (animT * 3f).toInt() % 2 == 0
        rect(c, fx + 6, fy - 13, 2, 2, if (glow) PX.RED else 0xFFFF9A52.toInt())               // the glowing tip
        px(c, fx + 8, fy - 13, a(0xFFFF9A52.toInt(), 0x90)); px(c, fx + 7, fy - 15, a(0xFFFF9A52.toInt(), 0x60))
        for (k in 0..4) {                                                                       // smoke curling up
            val ph = (animT * 0.8f + k * 0.2f) % 1f
            val sx = fx + 7 + (sin((animT + k) * 2.2f) * 2.5f).toInt()
            rect(c, sx, fy - 15 - (ph * 20f).toInt(), 2, 2, a(0xFFC8D0E0.toInt(), ((1f - ph) * 0xA0).toInt()))
        }
    }

    /** A person lying on the floor, seen from the side. [right]: head on the left (true) or on the right (false). */
    protected fun lying(c: Canvas, fx: Int, fy: Int, o: Outfit, right: Boolean, letter: Boolean) {
        rect(c, fx - 13, fy - 1, 27, 3, a(PX.K, 0x70))
        c.save()
        if (!right) c.scale(-1f, 1f, fx.toFloat() + 0.5f, 0f)
        rect(c, fx + 4, fy - 5, 8, 4, o.pants); rect(c, fx + 11, fy - 5, 3, 4, o.shoes)
        rect(c, fx - 5, fy - 7, 10, 6, o.coat ?: o.shirt); rect(c, fx - 5, fy - 7, 10, 1, (o.coat ?: o.shirt) + 0x00181818)
        rect(c, fx - 11, fy - 7, 7, 6, o.skin); rect(c, fx - 11, fy - 7, 4, 6, o.hair); px(c, fx - 6, fy - 5, PX.K)
        if (o.hat != null) rect(c, fx - 11, fy - 8, 6, 2, o.hat)
        rect(c, fx - 3, fy - 2, 7, 2, (o.coat ?: o.shirt) - 0x00101010)
        if (letter) { rect(c, fx + 1, fy - 9, 5, 3, PX.W); px(c, fx + 3, fy - 8, PX.RED) }
        c.restore()
    }
    /**
     * A spinning gold coin, centred on (cx, cy), radius [r] (5 or 6 looks right). [spin] is an angle: the coin turns edge-on and back.
     * Round face shaded from a warm highlight (top left) to deep gold (bottom right), an inner ring, a stamped star, a dark outline so
     * it reads on any background, the thick rim showing as it turns, and a glint that catches now and then.
     */
    protected fun coin(c: Canvas, cx: Int, cy: Int, r: Int, spin: Float, glint: Boolean = true) {
        val sx = abs(cos(spin)).coerceAtLeast(0.16f)
        val edgeSide = if (cos(spin) >= 0f) 1 else -1
        val out = 0xFF4A2E06.toInt(); val rimD = 0xFFA06A0C.toInt(); val rimL = 0xFFD89A1A.toInt()
        val hi = 0xFFFFF0A0.toInt(); val face = 0xFFF8C431.toInt(); val mid = 0xFFE8A81E.toInt(); val low = 0xFFC0800E.toInt()
        val rr = r + 0.5f
        for (dy in -r..r) {
            val half = kotlin.math.sqrt(maxOf(0f, rr * rr - dy * dy))
            val hw = (half * sx).roundToInt().coerceAtLeast(1)
            val thick = ((1f - sx) * 2.4f).roundToInt()                     // the rim shows as the coin turns
            // outline
            rect(c, cx - hw - 1 - (if (edgeSide < 0) thick else 0), cy + dy, hw * 2 + 3 + thick, 1, out)
            if (thick > 0) rect(c, if (edgeSide > 0) cx + hw + 1 else cx - hw - thick, cy + dy, thick, 1, if (dy < 0) rimL else rimD)
            for (dx in -hw..hw) {
                val nx = dx / maxOf(1f, hw.toFloat()); val ny = dy / rr
                val d = kotlin.math.sqrt(nx * nx + ny * ny)
                val light = -nx * 0.55f - ny * 0.75f                         // lit from the top left
                var col = when { d > 0.86f -> if (light > 0f) rimL else rimD; light > 0.45f -> hi; light > 0.05f -> face; light > -0.4f -> mid; else -> low }
                if (d in 0.62f..0.74f && sx > 0.45f) col = if (light > 0f) mid else low       // the inner ring
                px(c, cx + dx, cy + dy, col)
            }
        }
        rect(c, cx - (rr * sx).roundToInt(), cy - r - 1, ((rr * sx).roundToInt()) * 2 + 1, 1, out); rect(c, cx - (rr * sx).roundToInt(), cy + r + 1, ((rr * sx).roundToInt()) * 2 + 1, 1, out)
        // the stamped star
        if (sx > 0.55f) { val st = 0xFFB07410.toInt(); px(c, cx, cy - 2, st); rect(c, cx - 2, cy - 1, 5, 1, st); px(c, cx, cy, st); px(c, cx - 1, cy + 1, st); px(c, cx + 1, cy + 1, st); px(c, cx, cy - 1, hi) }
        // a glint
        if (glint && sx > 0.7f) { px(c, cx - (r * sx * 0.45f).roundToInt(), cy - r / 2, PX.W); if (((animT * 3f + cx * 0.1f).toInt() % 5) == 0) { px(c, cx - 3, cy - r - 2, PX.W); px(c, cx - 3, cy - r - 3, 0x99FFFFFF.toInt()); px(c, cx - 4, cy - r - 2, 0x99FFFFFF.toInt()); px(c, cx - 2, cy - r - 2, 0x99FFFFFF.toInt()) } }
    }

    private fun lite0(c: Int, d: Int): Int {
        val r = (((c shr 16) and 0xFF) + d).coerceIn(0, 255); val g = (((c shr 8) and 0xFF) + d).coerceIn(0, 255); val b = ((c and 0xFF) + d).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    protected fun dirOf(face: Float): Int {
        val dx = cos(face); val dy = sin(face)
        return if (abs(dx) > abs(dy)) (if (dx > 0) 1 else 3) else (if (dy > 0) 2 else 0)
    }

    /** The speech bubble of dots over the cop's head, one dot at a time. */
    protected fun dotsBubble(c: Canvas, cx: Int, topY: Int, n: Int) {
        val x = cx - 10; val y = topY - 12
        rect(c, x - 1, y - 1, 22, 11, PX.K); rect(c, x, y, 20, 9, PX.W)
        rect(c, cx - 2, y + 9, 5, 1, PX.K); rect(c, cx - 1, y + 9, 3, 1, PX.W); rect(c, cx - 1, y + 10, 2, 2, PX.K); px(c, cx, y + 10, PX.W)
        for (i in 0 until n.coerceIn(0, 3)) rect(c, x + 4 + i * 5, y + 4, 3, 3, PX.K)
    }
}
