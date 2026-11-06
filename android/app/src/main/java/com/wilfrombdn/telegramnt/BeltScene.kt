package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The warehouse conveyors in pixel art, seen from above: three narrow belts (just wider than you) on legs, high over a dark warehouse
 * floor lined with rows of stocked pallet racks. The floor and racks scroll slower than the belts, so you feel how high up you are.
 * Crates, tools, cables, presses, sparks and thrown barrels come down the belts. 14 tiles wide, 24 tall.
 */
internal class BeltScene(private val world: BeltWorld) : PixelSprites() {
    private val S = 16
    val W = 14 * S
    val ROWS = 24
    var H = ROWS * S; private set
    private var frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private var fc = Canvas(frame)
    /** Show more of the belts ahead so the view fills a tall screen (height in art pixels). */
    fun resize(hpx: Int) { val h = maxOf(ROWS * S, hpx); if (h == H) return; H = h; frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); fc = Canvas(frame) }

    private val steel = 0xFF6A7890.toInt(); private val steelD = 0xFF3A4458.toInt(); private val steelL = 0xFFA9B4C8.toInt()
    private val steelDD = 0xFF222936.toInt()
    private val hazard = 0xFFFFC82E.toInt(); private val hazardD = 0xFF1B1D22.toInt()
    private val cardboard = 0xFFA8803E.toInt(); private val cardboardD = 0xFF6E5226.toInt(); private val cardboardL = 0xFFD4AA5E.toInt()
    private val rust = 0xFF8A4A2A.toInt(); private val oil = 0xFF0A1014.toInt()
    private val lampGlow = glowBitmap(34, 0xFFFFD28A.toInt(), 54)
    private val redGlow = glowBitmap(20, 0xFFFF3B3B.toInt(), 70)
    private val blueGlow = glowBitmap(20, 0xFF3DE8F5.toInt(), 80)
    private val floorTile = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
    private val floorTile2 = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
    private val floorTile3 = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
    private var camWy = 0
    private var shx = 0; private var shy = 0

    init {
        for ((bm, seed) in listOf(floorTile to 1, floorTile2 to 2, floorTile3 to 3)) {
            val c = Canvas(bm)
            rect(c, 0, 0, S, S, 0xFF0B1217.toInt())
            for (y in 0 until S) for (x in 0 until S) { val v = hash(x, y, seed) % 100; if (v < 10) px(c, x, y, 0xFF121C23.toInt()) else if (v < 13) px(c, x, y, 0xFF05090C.toInt()) }
            rect(c, 0, 0, S, 1, 0xFF070C10.toInt()); rect(c, 0, 0, 1, S, 0xFF070C10.toInt())
            if (seed == 2) { rect(c, 3, 4, 9, 6, a(oil, 0xC0)); rect(c, 5, 3, 5, 8, a(oil, 0xC0)); px(c, 6, 5, 0xFF1D2E3A.toInt()) }
            if (seed == 3) { rect(c, 5, 5, 6, 6, 0xFF04070A.toInt()); for (i in 0..2) rect(c, 6, 6 + i * 2, 4, 1, 0xFF1A252D.toInt()) }   // a drain
        }
    }

    private val headGlow by lazy { glowBitmap(34, 0xFFFFE9A0.toInt(), 0x40) }
    private fun sy(sw: Float): Int = H - ((sw * S).roundToInt() - camWy)
    private fun sx(xw: Float): Int = (xw * S).roundToInt()
    private val laneHalf = 0.68f            // the belts: just wider than you
    private val DEPTH = 0.62f                // the floor far below scrolls at this fraction of the belts' speed
    private fun fy(sw: Float): Int = H - ((sw * S).roundToInt() - (camWy * DEPTH).roundToInt())   // a floor row on screen
    private val floorGlow = glowBitmap(44, 0xFFFFD28A.toInt(), 40)
    private val coinGlow = glowBitmap(10, 0xFFFFD84D.toInt(), 0x38)
    private val boxCols = intArrayOf(0xFFA8803E.toInt(), 0xFF8A6A3A.toInt(), 0xFFB89058.toInt(), 0xFF6E8AA0.toInt(), 0xFF8A3A3A.toInt(), 0xFF4A6A4A.toInt())
    private fun lite(c: Int, d: Int): Int {
        val r = (((c shr 16) and 0xFF) + d).coerceIn(0, 255); val g = (((c shr 8) and 0xFF) + d).coerceIn(0, 255); val b = ((c and 0xFF) + d).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    private val rackX = floatArrayOf(0.95f, 5.1f, 8.9f, 13.05f)           // pallet racks: at the sides and under the gaps between belts
    private fun laneX(l: Int) = world.spec.laneX[l.coerceIn(0, 2)]

    fun render(clock: Float): Bitmap {
        animT = clock
        val c = fc
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        val ps = world.s
        camWy = ((ps - 6.5f) * S).roundToInt()
        shx = if (world.shake > 0f) ((clock * 70f).toInt() % 3 - 1) * 2 else 0
        shy = if (world.shake > 0f) ((clock * 53f).toInt() % 3 - 1) * 2 else 0
        c.save(); c.translate(shx.toFloat(), shy.toFloat())
        val topS = (camWy + H) / S.toFloat(); val botS = camWy / S.toFloat()
        val r0 = floor(botS).toInt() - 2; val r1 = floor(topS).toInt() + 2
        val endS = world.endS

        // 1. the warehouse floor far below: concrete, painted aisle lines, tyre marks, stains, pools of lamp light
        val fr0 = floor((camWy * DEPTH) / S).toInt() - 2; val fr1 = fr0 + H / S + 4
        rect(c, 0, 0, W, H, 0xFF080C10.toInt())
        for (r in fr0..fr1) {
            val y = fy(r.toFloat() + 1f)
            for (cx in 0 until 14) {
                val v = hash(cx, r, 4) % 13
                c.drawBitmap(when { v == 0 -> floorTile2; v == 1 -> floorTile3; else -> floorTile }, (cx * S).toFloat(), y.toFloat(), plain)
            }
            // aisle lines down the floor between the racks, dashed where forklifts cross
            for (ax in floatArrayOf(3.0f, 7.0f, 11.0f)) if (r % 6 != 0) rect(c, sx(ax) - 1, y, 2, S, 0xFF6A5A1E.toInt())
            if (r % 11 == 3) { val tx = sx(2.2f + (hash(r, 1, 3) % 9)); for (k in 0 until S step 2) { px(c, tx, y + k, 0xFF05080A.toInt()); px(c, tx + 6, y + k, 0xFF05080A.toInt()) } }   // tyre marks
            if (r % 9 == 5) for (i in 0 until 14 * 2) rect(c, i * 8, y + 6, 4, 3, if (i % 2 == 0) 0xFF7A6420.toInt() else 0xFF14161A.toInt())          // a painted crossing
        }
        for (r in fr0..fr1) if (r % 7 == 2) { val y = fy(r + 0.5f); c.drawBitmap(floorGlow, sx(3f) - 44f, y - 44f, plain); c.drawBitmap(floorGlow, sx(11f) - 44f, y - 44f, plain) }
        // the racks: blue uprights, orange beams every bay, pallets of boxes, drums and sacks seen from above
        for (rx in rackX) {
            val x0 = sx(rx) - 14; val wR = 28
            for (r in fr0..fr1) {
                val y = fy(r.toFloat() + 1f)
                rect(c, x0 - 1, y, wR + 2, S, 0xFF05070A.toInt())
                rect(c, x0, y, 3, S, 0xFF2A4A8A.toInt()); rect(c, x0 + wR - 3, y, 3, S, 0xFF2A4A8A.toInt()); rect(c, x0, y, 1, S, 0xFF5A7AC0.toInt())
                if (r % 2 == 0) { rect(c, x0, y, wR, 3, 0xFFC8642A.toInt()); rect(c, x0, y, wR, 1, 0xFFF0904A.toInt()); rect(c, x0, y + 2, wR, 1, 0xFF6A3418.toInt()) }   // the beams
                val kind = hash(r, (rx * 10).toInt(), 17) % 9
                val bx = x0 + 4; val by = y + (if (r % 2 == 0) 4 else 1); val bw = wR - 8; val bh = 11
                val zone = if (world.spec.arcade) BeltWorld.zoneAt(r / DEPTH) else 0
                if (zone != 0) { zoneBay(c, zone, kind, r, x0, y, wR, bx, by, bw, bh); continue }
                when {
                    kind == 0 -> { }                                                                                  // an empty bay
                    kind < 5 -> {                                                                                     // a pallet stacked with boxes, shrink-wrapped
                        rect(c, bx - 1, by - 1, bw + 2, bh + 2, 0xFF3A2A18.toInt())
                        val bc = boxCols[hash(r, (rx * 7).toInt(), 18) % boxCols.size]
                        for (q in 0..3) { val qx = bx + (q % 2) * (bw / 2); val qy = by + (q / 2) * (bh / 2); rect(c, qx, qy, bw / 2 - 1, bh / 2 - 1, bc); rect(c, qx, qy, bw / 2 - 1, 1, lite(bc, 30)); rect(c, qx + bw / 4 - 1, qy, 1, bh / 2 - 1, lite(bc, -28)) }
                        if (kind % 2 == 0) { rect(c, bx + 1, by + 1, 2, bh - 2, a(PX.W, 0x40)); px(c, bx + bw - 3, by + 2, a(PX.W, 0x80)) }          // the wrap's sheen
                        if (kind == 3) { rect(c, bx + 3, by + 3, 5, 3, PX.W); px(c, bx + 4, by + 4, PX.K) }                                    // a shipping label
                    }
                    kind < 7 -> { for (q in 0..1) { val dx2 = bx + 2 + q * 11; rect(c, dx2, by, 9, 9, PX.K); rect(c, dx2 + 1, by + 1, 7, 7, if ((r + q) % 2 == 0) 0xFF2F6F73.toInt() else 0xFF8A3A2A.toInt()); rect(c, dx2 + 3, by + 3, 3, 3, 0xFF14181E.toInt()); px(c, dx2 + 2, by + 2, a(PX.W, 0x90)) } }   // drums
                    else -> { for (q in 0..2) { val sx2 = bx + q * 7; rect(c, sx2, by + 1, 6, 9, 0xFFC8B890.toInt()); rect(c, sx2, by + 1, 6, 2, 0xFFE8DCB8.toInt()); rect(c, sx2 + 2, by + 4, 2, 2, 0xFF8A7A50.toInt()) } }                  // sacks
                }
            }
        }
        // the arcade's parts of the warehouse: a wash of their own colour over the floor
        if (world.spec.arcade) for (r in fr0..fr1) { val z = BeltWorld.zoneAt(r / DEPTH); if (z == 0) continue
            val y = fy(r.toFloat() + 1f); rect(c, 0, y, W, S, a(when (z) { 1 -> 0xFF8AD0FF.toInt(); 2 -> 0xFFFFB050.toInt(); else -> 0xFF60FF9A.toInt() }, 0x16))
            if (z == 1 && hash(r, 3, 31) % 3 == 0) for (k in 0 until 6) px(c, hash(r, k, 32) % W, y + hash(r, k, 33) % S, a(PX.W, 0x90))      // frost glinting
        }
        // everything down there is far below and dim: a blue-black haze over it so the belts stand out
        rect(c, -4, -4, W + 8, H + 8, 0x9004070E.toInt())
        // the belts' shadows cast down on the floor (offset, because the light comes from above and to the left)
        for (l in 0..2) { val x0 = sx(laneX(l) - laneHalf) + 10; val w = sx(laneX(l) + laneHalf) - sx(laneX(l) - laneHalf); val y0 = fy(world.endS).coerceAtLeast(0); val y1 = fy(0f).coerceAtMost(H); if (y1 > y0) rect(c, x0, y0, w, y1 - y0, a(PX.K, 0x70)) }
        // 2. the legs holding the belts up, and the cross beams between them
        for (r in r0..r1) {
            if (r < 0 || r.toFloat() > endS || r % 5 != 2) continue
            val y = sy(r.toFloat() + 1f)
            rect(c, sx(laneX(0) - laneHalf) - 6, y + 5, sx(laneX(2) + laneHalf) - sx(laneX(0) - laneHalf) + 12, 4, PX.K)
            rect(c, sx(laneX(0) - laneHalf) - 6, y + 5, sx(laneX(2) + laneHalf) - sx(laneX(0) - laneHalf) + 12, 1, steel)
            for (l in 0..2) for (e in 0..1) { val lx = if (e == 0) sx(laneX(l) - laneHalf) - 3 else sx(laneX(l) + laneHalf); rect(c, lx, y + 2, 3, 10, PX.K); rect(c, lx, y + 2, 1, 10, steelL); rect(c, lx + 8, y + 9, 3, 6, a(PX.K, 0x80)) }
        }
        // 3. the belts, the feed end, the kicker and the landing platform
        for (l in 0..2) belt(c, l)
        feedEnd(c)
        kicker(c, endS)
        landing(c, endS)
        if (world.s < 8f) wayIn(c)
        // 4. lamps' glow on everything below them
        for (r in r0..r1) if (r % 12 == 5) {
            val y = sy(r.toFloat() + 0.5f)
            c.drawBitmap(lampGlow, (sx(laneX(1)) - 34).toFloat(), (y - 34).toFloat(), plain)
            c.drawBitmap(lampGlow, (sx(laneX(if (r % 24 == 5) 0 else 2)) - 34).toFloat(), (y - 34).toFloat(), plain)
        }
        // 5. obstacles
        for (o in world.spec.obstacles) {
            val yy = sy(o.curS)
            if (yy < -50 || yy > H + 50) continue
            obstacle(c, o, sx(world.obsX(o)), yy, clock)
        }
        // 5b. coins: on the belt, or hanging in the air over barriers (with a little shadow below)
        for (co in world.spec.coins) {
            if (co.taken) continue
            val yy = sy(co.s)
            if (yy < -12 || yy > H + 12) continue
            var cx = sx(laneX(co.lane)); var cy = yy - (if (co.high) 16 else 0)
            if (co.claimed) { val k = (1f - (co.s - world.s) / 6f).coerceIn(0f, 1f); cx += ((sx(world.x) - cx) * k).roundToInt(); cy -= (k * 6f).roundToInt() }   // pulled in to you
            if (co.high) rect(c, cx - 4, yy + 2, 9, 3, a(PX.K, 0x70))
            c.drawBitmap(coinGlow, cx - 10f, cy - 10f, plain)
            coin(c, cx, cy, 5, clock * 4.2f + co.s * 0.8f)
        }
        // 6. me
        player(c, clock)
        // 7. sparks drifting up in the air
        for (k in 0 until 6) {
            val ph = (clock * 0.7f + k * 0.17f) % 1f
            val x = hash(k, (clock * 0.7f + k * 0.17f).toInt(), 8) % W
            rect(c, x, H - (ph * H).toInt(), 1, 2, a(PX.WARM, ((1f - ph) * 0x60).toInt()))
        }
        c.restore()
        if (world.hitFlash > 0f) { p.color = a(PX.RED, (world.hitFlash * 0x90).toInt()); c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p) }
        if (world.phase == BeltWorld.Phase.RIDE && world.speedNow > 7f) {
            val n = ((world.speedNow - 7f) * 2f).toInt() + 2
            for (k in 0 until n) { val x = hash(k, (clock * 22f).toInt(), 9) % W; val y = hash(k, (clock * 22f).toInt(), 10) % H; rect(c, x, y, 1, 10 + k % 6, a(PX.W, 0x30)) }
        }
        return frame
    }

    /** One bay of the floor below in the arcade's other parts of the warehouse (our own scenery, drawn from above). */
    private fun zoneBay(c: Canvas, zone: Int, kind: Int, r: Int, x0: Int, y: Int, wR: Int, bx: Int, by: Int, bw: Int, bh: Int) {
        when (zone) {
            1 -> { // the cold store: white freezer units with frosted lids and plastic strip curtains between them
                rect(c, x0 - 1, y, wR + 2, S, 0xFF0A1218.toInt())
                if (r % 3 != 0) { rect(c, x0 + 1, y + 1, wR - 2, S - 2, 0xFFB8D4E8.toInt()); rect(c, x0 + 1, y + 1, wR - 2, 2, 0xFFE8F6FF.toInt()); rect(c, x0 + 3, y + 5, wR - 6, 1, 0xFF6A8AA0.toInt())
                    if (kind % 3 == 0) rect(c, x0 + wR / 2 - 3, y + 7, 6, 3, 0xFF3BA0FF.toInt()) }
                else for (k in 0 until wR step 4) rect(c, x0 + k, y, 2, S, a(0xFFC8E8FF.toInt(), 0x70))
            }
            2 -> { // the loading docks: trailers backed up to the bays, roller doors, a yellow bumper on each bay
                rect(c, x0 - 1, y, wR + 2, S, 0xFF0C0A08.toInt())
                val trailer = hash(r / 4, x0, 41) % 3 != 0
                if (trailer) { rect(c, x0 + 2, y, wR - 4, S, 0xFF8A8E96.toInt()); rect(c, x0 + 2, y, 2, S, 0xFFC0C6D0.toInt()); for (k in 0 until S step 4) rect(c, x0 + 4, y + k, wR - 8, 1, 0xFF5A5E66.toInt())
                    if (r % 4 == 0) { rect(c, x0 + 2, y, wR - 4, 3, 0xFFE8A020.toInt()); rect(c, x0 + 6, y + 4, wR - 12, 5, 0xFF2A3A4C.toInt()) } }
                else { rect(c, x0 + 2, y, wR - 4, S, 0xFF2A2420.toInt()); if (r % 4 == 1) rect(c, x0 + 2, y + 6, wR - 4, 3, 0xFFFFC82E.toInt()) }
            }
            else -> { // the sorting hall: parcel chutes with rollers, parcels sliding down, sorting bins
                rect(c, x0 - 1, y, wR + 2, S, 0xFF080C0A.toInt())
                rect(c, x0 + 2, y, wR - 4, S, 0xFF2A3A30.toInt()); for (k in 0 until S step 3) rect(c, x0 + 3, y + k, wR - 6, 1, 0xFF4A6A54.toInt())
                if (kind < 5) { val bc = boxCols[kind % boxCols.size]; val off = ((animT * 10f).toInt() + r * 5) % S; rect(c, x0 + 6 + (kind % 3) * 4, y + off, 8, 6, bc); rect(c, x0 + 6 + (kind % 3) * 4, y + off, 8, 1, lite(bc, 30)) }
                if (r % 5 == 0) { rect(c, x0, y + 2, wR, 10, 0xFF1A5A3A.toInt()); rect(c, x0 + 2, y + 4, wR - 4, 6, 0xFF0A1A12.toInt()) }
            }
        }
    }

    // ---------------- the belts ----------------
    private fun belt(c: Canvas, l: Int) {
        val endS = world.endS
        val x0 = sx(laneX(l) - laneHalf); val w = sx(laneX(l) + laneHalf) - x0
        val yTop = sy(endS); val yBot = sy(0f)
        if (yBot < 0 || yTop > H) return
        val y0 = maxOf(yTop, 0); val y1 = minOf(yBot, H)
        // the frame: steel side rails with a lit top edge, rollers peeking out at the sides
        rect(c, x0 - 3, y0, w + 6, y1 - y0, PX.K)
        rect(c, x0 - 3, y0, 3, y1 - y0, steelD); rect(c, x0 - 3, y0, 1, y1 - y0, steelL)
        rect(c, x0 + w, y0, 3, y1 - y0, steelD); rect(c, x0 + w + 2, y0, 1, y1 - y0, PX.K); rect(c, x0 + w, y0, 1, y1 - y0, steel)
        // the rubber belt itself
        rect(c, x0, y0, w, y1 - y0, 0xFF1A1E24.toInt())
        rect(c, x0, y0, 1, y1 - y0, 0xFF2A3038.toInt()); rect(c, x0 + w - 1, y0, 1, y1 - y0, 0xFF101318.toInt())
        val bi0 = floor(camWy / 4f).toInt() - 1; val bi1 = floor((camWy + H) / 4f).toInt() + 1
        for (bi in bi0..bi1) {
            val sw = bi * 4f / S
            if (sw < 0f || sw > endS) continue
            val y = H - (bi * 4 - camWy)
            if (bi % 2 == 0) { rect(c, x0 + 1, y, w - 2, 1, 0xFF0E1116.toInt()); rect(c, x0 + 1, y + 1, w - 2, 1, 0xFF262C35.toInt()) }   // cleats
            if (bi % 4 == 1) { rect(c, x0 - 3, y, 3, 2, 0xFF5A6478.toInt()); rect(c, x0 + w, y, 3, 2, 0xFF5A6478.toInt()) }                // roller ends
            if (bi % 7 == 3) px(c, x0 + 2 + hash(bi, l, 3) % (w - 4), y + 2, 0xFF3A4452.toInt())                                            // wear
            if (bi % 10 == 0) rivet(c, x0 - 2, y + 1)
            if (bi % 16 == 0) { val cx = x0 + w / 2; for (i in 0..5) { px(c, cx - 5 + i, y - 4 + (5 - i) / 2, 0xFF3C4858.toInt()); px(c, cx + 5 - i, y - 4 + (5 - i) / 2, 0xFF3C4858.toInt()) } }   // a chevron
        }
    }

    private fun rivet(c: Canvas, x: Int, y: Int) { px(c, x, y, steelL); px(c, x + 1, y + 1, PX.K) }

    private fun feedEnd(c: Canvas) {
        val y = sy(0f)
        if (y < -40 || y > H + 60) return
        rect(c, 0, y + 6, W, 8 * S, 0xFF0A1014.toInt())
        for (i in 0 until 14 * 2) rect(c, i * 8, y + 6, 8, 3, if (i % 2 == 0) hazard else hazardD)
        val dx = sx(laneX(1)) - 34
        rect(c, dx - 6, y + 22, 80, 70, PX.K); rect(c, dx - 4, y + 24, 76, 66, steelDD)
        rect(c, dx, y + 28, 68, 62, 0xFFFFE9B0.toInt()); rect(c, dx, y + 28, 68, 6, 0xFFFFF6D8.toInt())
        for (i in 0..4) rect(c, dx + i * 14 + 6, y + 34, 2, 56, a(0xFFFFC870.toInt(), 0x90))
        rect(c, dx - 6, y + 22, 80, 4, steel)
        rect(c, dx + 20, y + 14, 28, 8, PX.K); rect(c, dx + 21, y + 15, 26, 6, 0xFF5A1020.toInt()); rect(c, dx + 24, y + 17, 20, 2, PX.RED)
        for (l in 0..2) {
            val x0 = sx(laneX(l) - laneHalf); val w = sx(laneX(l) + laneHalf) - x0
            rect(c, x0 - 4, y - 4, w + 8, 10, steelDD); rect(c, x0, y - 3, w, 3, steelL); rect(c, x0, y, w, 4, steelD)
            for (i in 0 until w step 6) { rect(c, x0 + i, y - 2, 3, 6, steel); px(c, x0 + i + 1, y - 1, steelL) }
        }
    }

    private fun kicker(c: Canvas, endS: Float) {
        val y = sy(endS)
        if (y < -40 || y > H + 40) return
        val pulse = if (world.phase == BeltWorld.Phase.LAUNCH && world.launchK < 0.25f) 6 else 0
        rect(c, 4, y - 8 + pulse, W - 8, 16, PX.K)
        for (i in 0 until 26) rect(c, 6 + i * 8, y - 6 + pulse, 8, 12, if (i % 2 == 0) hazard else hazardD)
        rect(c, 4, y - 8 + pulse, W - 8, 2, steelL)
        for (l in 0..2) { val cx = sx(laneX(l)); rect(c, cx - 14, y - 4 + pulse, 28, 8, 0xFFB02038.toInt()); rect(c, cx - 14, y - 4 + pulse, 28, 2, 0xFFFF6A7A.toInt()); rect(c, cx - 4, y + 4 + pulse, 8, 6, steel) }
        if (world.phase == BeltWorld.Phase.RIDE && world.s > endS - 22f && (animT * 6f).toInt() % 2 == 0) c.drawBitmap(redGlow, (sx(laneX(1)) - 20).toFloat(), (y - 28).toFloat(), plain)
    }

    private fun landing(c: Canvas, endS: Float) {
        val yTopPlat = sy(endS + 14f); val yBotPlat = sy(endS + 0.4f)
        if (yBotPlat < 0 || yTopPlat > H) return
        val ya = maxOf(yTopPlat, 0); val yb = minOf(yBotPlat, H)
        rect(c, 8, ya, W - 16, yb - ya, PX.K); rect(c, 10, ya, W - 20, yb - ya, 0xFF2A3340.toInt())
        for (yy in ya until yb step 6) for (xx in 12 until W - 12 step 6) { rect(c, xx, yy, 4, 4, 0xFF1A212B.toInt()); px(c, xx, yy, 0xFF3C4858.toInt()) }
        rect(c, 10, ya, 3, yb - ya, hazard); rect(c, W - 13, ya, 3, yb - ya, hazard)
        val dy = sy(endS + 14f)
        rect(c, 0, dy - 56, W, 58, PX.K); rect(c, 4, dy - 52, W - 8, 52, steelDD)
        for (i in 0 until 7) { rect(c, 10 + i * 30, dy - 48, 26, 46, 0xFF303A48.toInt()); rect(c, 10 + i * 30, dy - 48, 26, 2, steelL); for (k in 0..3) rect(c, 12 + i * 30, dy - 40 + k * 10, 22, 1, 0xFF1D242E.toInt()) }
        val jx = sx(laneX(1)); val jy = sy(endS + 10f)
        if (world.phase != BeltWorld.Phase.PICKUP && world.phase != BeltWorld.Phase.DONE) {
            c.drawBitmap(redGlow, (jx - 20).toFloat(), (jy - 24).toFloat(), plain)
            rect(c, jx - 12, jy + 2, 24, 6, a(PX.K, 0xA0)); rect(c, jx - 10, jy - 4, 20, 7, steelDD); rect(c, jx - 10, jy - 4, 20, 2, steel)
            jetpackSprite(c, jx, jy - 4)
        }
    }

    private fun jetpackSprite(c: Canvas, cx: Int, by: Int) {
        for (k in 0..1) {
            val x = cx - 8 + k * 9
            rect(c, x - 1, by - 15, 8, 17, PX.K); rect(c, x, by - 14, 6, 15, 0xFFC03030.toInt()); rect(c, x, by - 14, 2, 15, 0xFFFF7070.toInt()); rect(c, x + 5, by - 14, 1, 15, 0xFF701818.toInt())
            rect(c, x, by - 14, 6, 2, steelL); rect(c, x + 1, by + 1, 4, 3, steelD)
        }
        rect(c, cx - 8, by - 9, 15, 2, steel); rect(c, cx - 8, by - 4, 15, 2, steel)
        px(c, cx - 1, by - 11, PX.GOLD); px(c, cx, by - 11, PX.W)
    }

    // ---------------- things on the belts ----------------
    private fun shadow(c: Canvas, x: Int, y: Int, w: Int, h: Int) { rect(c, x - w / 2 + 3, y - h / 2 + 3, w, h, a(PX.K, 0x80)) }

    private fun obstacle(c: Canvas, o: BeltObs, x: Int, y: Int, clock: Float) {
        when (o.kind) {
            BeltWorld.BOX -> {   // a cardboard box with tape, a label and a FRAGILE strip, sized to the belt
                shadow(c, x, y, 18, 18)
                rect(c, x - 10, y - 10, 20, 20, PX.K); rect(c, x - 9, y - 9, 18, 18, cardboard); rect(c, x - 9, y - 9, 18, 2, cardboardL); rect(c, x - 9, y - 9, 1, 18, cardboardL)
                rect(c, x - 9, y + 7, 18, 2, cardboardD); rect(c, x + 8, y - 9, 1, 18, cardboardD)
                rect(c, x - 1, y - 9, 3, 18, 0xFFD8C690.toInt()); rect(c, x - 1, y - 9, 1, 18, 0xFFEEE0B0.toInt())                    // tape
                rect(c, x + 3, y + 1, 5, 4, PX.W); px(c, x + 4, y + 2, PX.K); px(c, x + 6, y + 3, PX.K)                                // label
                rect(c, x - 8, y - 6, 5, 2, 0xFFC03030.toInt()); px(c, x - 7, y + 4, cardboardD); px(c, x - 5, y + 5, cardboardD)      // FRAGILE, scuffs
            }
            BeltWorld.BARREL -> {   // an oil drum from above: rim, ribs, the bung, a drip
                shadow(c, x, y, 17, 17)
                for (dy in -8..8) { val hw = kotlin.math.sqrt(maxOf(0f, 72.25f - dy * dy)).toInt(); rect(c, x - hw - 1, y + dy, hw * 2 + 3, 1, PX.K) }
                for (dy in -7..7) { val hw = kotlin.math.sqrt(maxOf(0f, 56.25f - dy * dy)).toInt(); rect(c, x - hw, y + dy, hw * 2 + 1, 1, if (dy < -3) 0xFFD84A34.toInt() else if (dy < 3) 0xFFB03A2A.toInt() else 0xFF8A2A1C.toInt()) }
                for (dy in -4..4) { val hw = kotlin.math.sqrt(maxOf(0f, 20.25f - dy * dy)).toInt(); px(c, x - hw, y + dy, 0xFF701C10.toInt()); px(c, x + hw, y + dy, 0xFF701C10.toInt()) }
                rect(c, x + 2, y - 3, 3, 3, PX.K); px(c, x + 3, y - 2, hazard); px(c, x - 4, y - 5, 0xFFFF9A7A.toInt()); px(c, x - 3, y - 6, PX.W)
                rect(c, x + 8, y + 2, 2, 3, a(oil, 0xC0))
            }
            BeltWorld.TOOL -> {
                shadow(c, x, y, 12, 40)
                rect(c, x - 4, y - 18, 8, 36, PX.K); rect(c, x - 3, y - 17, 6, 34, 0xFFB8C2D4.toInt()); rect(c, x - 3, y - 17, 2, 34, PX.W); rect(c, x + 2, y - 17, 1, 34, 0xFF6A7890.toInt())
                rect(c, x - 9, y - 24, 18, 10, PX.K); rect(c, x - 8, y - 23, 16, 8, 0xFFB8C2D4.toInt()); rect(c, x - 4, y - 23, 8, 5, PX.K)
                rect(c, x - 8, y - 23, 16, 2, PX.W); rect(c, x - 3, y + 14, 6, 6, 0xFFC03030.toInt())
            }
            BeltWorld.CABLE, BeltWorld.HOOK -> {
                val big = o.kind == BeltWorld.HOOK
                shadow(c, x, y + 4, if (big) 18 else 12, if (big) 18 else 12)
                val lift = if (big) 22 else 18
                for (i in 0..14) { val ty = y - lift - i * 8; rect(c, x - 1, ty, 3, 9, PX.K); px(c, x - 1, ty + 1, 0xFF3C4858.toInt()) }
                if (big) {
                    rect(c, x - 8, y - lift - 2, 16, 8, PX.K); rect(c, x - 7, y - lift - 1, 14, 6, hazard); rect(c, x - 7, y - lift - 1, 14, 2, 0xFFFFE9A0.toInt())
                    rect(c, x - 3, y - lift + 6, 6, 12, PX.K); rect(c, x - 2, y - lift + 6, 4, 11, steelL); rect(c, x - 7, y - lift + 14, 6, 6, PX.K); rect(c, x - 6, y - lift + 15, 4, 4, steel)
                } else {
                    rect(c, x - 6, y - lift - 2, 12, 12, PX.K); rect(c, x - 5, y - lift - 1, 10, 10, 0xFF1D232C.toInt()); rect(c, x - 5, y - lift - 1, 10, 3, 0xFF3C4858.toInt())
                    rect(c, x - 2, y - lift + 9, 4, 6, PX.K); rect(c, x - 1, y - lift + 9, 2, 6, 0xFFC8D0E0.toInt())
                    if ((clock * 14f).toInt() % 2 == 0) { rect(c, x - 4, y - lift + 14, 8, 2, PX.CYAN); px(c, x - 6, y - lift + 16, PX.W); px(c, x + 5, y - lift + 13, PX.W) }
                }
            }
            BeltWorld.PRESS -> {
                val live = world.obsLive(o)
                val warn = !live && (((world.t + o.phase) % o.period) > o.period * o.onFrac + o.period * (1f - o.onFrac) * 0.6f)
                val hw = sx(laneHalf) + 6
                if (live) {
                    rect(c, x - hw, y - 16, hw * 2, 32, PX.K)
                    rect(c, x - hw + 1, y - 15, hw * 2 - 2, 30, 0xFF5A6478.toInt()); rect(c, x - hw + 1, y - 15, hw * 2 - 2, 3, steelL)
                    for (i in 0 until hw * 2 step 8) rect(c, x - hw + 2 + i, y - 8, 4, 14, if ((i / 8) % 2 == 0) hazard else hazardD)
                    px(c, x - hw - 2, y - 4, PX.W); px(c, x + hw + 1, y + 2, PX.WARM); px(c, x + hw + 3, y, PX.W)
                } else {
                    rect(c, x - hw + 10, y - 12, hw * 2, 26, a(PX.K, 0x60))
                    rect(c, x - hw - 2, y - 18, hw * 2 + 4, 6, steelDD); rect(c, x - hw - 2, y - 18, hw * 2 + 4, 2, steel)
                    rect(c, x - hw + 2, y - 14, hw * 2 - 4, 18, 0xFF3C4858.toInt()); rect(c, x - hw + 2, y - 14, hw * 2 - 4, 3, steelL)
                    rect(c, x - 3, y + 4, 6, 6, PX.K); rect(c, x - hw + 4, y - 4, hw * 2 - 8, 3, if (warn && (animT * 12f).toInt() % 2 == 0) PX.RED else 0xFF2A3340.toInt())
                    if (warn) c.drawBitmap(redGlow, (x - 20).toFloat(), (y - 22).toFloat(), plain)
                }
            }
            BeltWorld.ARC -> {
                val live = world.obsLive(o)
                val hw = (o.hw * S).toInt()
                val coilL = x - hw; val coilR = x + hw
                for (cx in intArrayOf(coilL, coilR)) { rect(c, cx - 6, y - 8, 12, 16, PX.K); rect(c, cx - 5, y - 7, 10, 14, steelD); rect(c, cx - 5, y - 7, 10, 3, steelL); rect(c, cx - 2, y - 2, 4, 4, if (live) PX.CYAN else 0xFF1A4A50.toInt()) }
                if (live) {
                    val n = 14
                    var py = y
                    for (k in 0 until n) {
                        val x1 = coilL + (coilR - coilL) * k / n; val x2 = coilL + (coilR - coilL) * (k + 1) / n
                        val jy = y + (hash(k, (clock * 28f).toInt(), 13) % 9) - 4
                        rect(c, minOf(x1, x2), minOf(py, jy), x2 - x1 + 1, abs(jy - py) + 3, a(PX.CYAN, 0x80)); rect(c, minOf(x1, x2), minOf(py, jy), x2 - x1 + 1, abs(jy - py) + 1, PX.W)
                        py = jy
                    }
                    c.drawBitmap(blueGlow, (x - 20).toFloat(), (y - 20).toFloat(), plain)
                } else if (((world.t + o.phase) % o.period) > o.period * o.onFrac + (o.period * (1f - o.onFrac)) * 0.55f && (animT * 14f).toInt() % 2 == 0) {
                    for (k in 0..4) px(c, coilL + (coilR - coilL) * k / 4, y, 0xFF3DE8F5.toInt())
                }
            }
            BeltWorld.SMALL -> { // a low striped barrier across the belt: jump it
                val hw = sx(laneHalf) + 4
                rect(c, x - hw + 3, y - 4, hw * 2, 11, a(PX.K, 0x80))
                rect(c, x - hw + 1, y - 6, 4, 12, PX.K); rect(c, x + hw - 5, y - 6, 4, 12, PX.K)
                rect(c, x - hw, y - 8, hw * 2, 9, PX.K)
                for (i in 0 until hw * 2 - 2 step 8) { rect(c, x - hw + 1 + i, y - 7, 4, 7, hazard); rect(c, x - hw + 5 + i, y - 7, 4, 7, 0xFFE8EEF8.toInt()) }
                rect(c, x - hw + 1, y - 7, hw * 2 - 2, 1, 0xFFFFF2B0.toInt()); rect(c, x - 3, y - 12, 6, 4, PX.K); rect(c, x - 2, y - 11, 4, 2, PX.RED)
            }
            BeltWorld.GAP -> { // a hole in the belt: the floor far below shows through
                val hw = sx(laneHalf) + 3; val hh = (o.hs * S).toInt()
                rect(c, x - hw, y - hh, hw * 2, hh * 2, PX.K)
                rect(c, x - hw + 4, y - hh + 2, hw * 2 - 8, hh * 2 - 4, 0xFF070C10.toInt())
                for (i in 0 until hw * 2 step 6) { rect(c, x - hw + i, y - hh - 2 + (hash(i, 1, 5) % 3), 4, 4, steelDD); rect(c, x - hw + i + 2, y + hh - 2 - (hash(i, 2, 5) % 3), 4, 4, steelDD) }
                for (i in 0 until 2) rect(c, x - hw + 5 + i * 8, y - hh + 6 + (hash(i, 3, 5) % 18), 5, 1, 0xFF1A252D.toInt())
                rect(c, x - hw, y - hh, 3, hh * 2, hazard); rect(c, x + hw - 3, y - hh, 3, hh * 2, hazard)
                if ((clock * 10f).toInt() % 3 == 0) { px(c, x - 8, y - 2, PX.WARM); px(c, x - 6, y - 4, PX.W); px(c, x + 10, y + 3, PX.WARM) }
                for (i in 0..1) { val wx = x - hw + 6 + i * 10; rect(c, wx, y - hh - 4, 2, 8 + i * 2, PX.K) }  // torn belt
            }
            BeltWorld.TRUCK -> { // a runaway forklift driving at you
                if (!o.armed) { if ((animT * 6f).toInt() % 2 == 0) { rect(c, x - 24, y - 10, 48, 4, PX.RED); rect(c, x - 2, y - 18, 4, 6, PX.RED) }; return }
                rect(c, x - 28 + 5, y - 28 + 5, 56, 56, a(PX.K, 0x80))
                rect(c, x - 30, y - 28, 60, 56, PX.K); rect(c, x - 29, y - 27, 58, 54, 0xFFE8A020.toInt()); rect(c, x - 29, y - 27, 58, 4, 0xFFFFD060.toInt())
                for (i in 0 until 58 step 8) { rect(c, x - 29 + i, y + 21, 4, 5, PX.K) }
                rect(c, x - 14, y - 20, 28, 20, PX.K); rect(c, x - 12, y - 18, 24, 16, 0xFF2A3A4C.toInt()); rect(c, x - 12, y - 18, 24, 3, 0xFF4C6A8C.toInt())
                rect(c, x - 8, y + 4, 16, 10, PX.K); rect(c, x - 6, y + 6, 12, 6, 0xFFC03030.toInt())
                rect(c, x - 22, y + 26, 5, 14, steelL); rect(c, x + 18, y + 26, 5, 14, steelL); rect(c, x - 22, y + 38, 5, 3, PX.K); rect(c, x + 18, y + 38, 5, 3, PX.K)    // the forks, pointing at you
                rect(c, x - 26, y + 22, 8, 5, PX.W); rect(c, x + 19, y + 22, 8, 5, PX.W)
                c.drawBitmap(headGlow, x - 34f, y + 14f, plain)
                if ((clock * 8f).toInt() % 2 == 0) { rect(c, x - 3, y - 26, 6, 4, PX.RED) }
            }
            BeltWorld.SAWB -> { // a saw on a rail across all three belts
                val x0 = sx(world.spec.laneX[0]) - 40; val x1 = sx(world.spec.laneX[2]) + 40
                rect(c, x0, y - 3, x1 - x0, 6, PX.K); rect(c, x0, y - 2, x1 - x0, 2, steel); rect(c, x0, y, x1 - x0, 1, steelDD)
                for (i in x0 until x1 step 10) px(c, i, y - 2, steelL)
                rect(c, x - 10, y - 10, 24, 24, a(PX.K, 0x70))
                rect(c, x - 9, y - 9, 18, 18, PX.K); rect(c, x - 8, y - 8, 16, 16, 0xFFE8EEF8.toInt())
                for (k in 0 until 10) { val ang = clock * 18f + k * 0.628f; val tx = (x + kotlin.math.cos(ang) * 13f).roundToInt(); val ty = (y + kotlin.math.sin(ang) * 13f).roundToInt(); rect(c, tx - 2, y.coerceAtLeast(0) + (ty - y) - 2, 4, 4, steelL); px(c, tx, ty, PX.K) }
                rect(c, x - 4, y - 4, 8, 8, steelD); rect(c, x - 1, y - 1, 3, 3, hazard)
                if ((clock * 14f).toInt() % 2 == 0) { px(c, x + 14, y - 4, PX.WARM); px(c, x + 16, y - 2, PX.W) }
            }
            BeltWorld.DROP -> { // a crate from the crane: a shadow grows on the belt, then it crashes down
                val cyc = (world.t + o.phase) % o.period
                val onT = o.period * o.onFrac
                val hw = sx(laneHalf) + 1
                if (cyc < onT) { // down and lying there
                    rect(c, x - hw + 4, y - hw + 4, hw * 2, hw * 2, a(PX.K, 0x80))
                    rect(c, x - hw, y - hw, hw * 2, hw * 2, PX.K); rect(c, x - hw + 1, y - hw + 1, hw * 2 - 2, hw * 2 - 2, 0xFF8A6A34.toInt()); rect(c, x - hw + 1, y - hw + 1, hw * 2 - 2, 3, cardboardL)
                    rect(c, x - 3, y - hw, 6, hw * 2, cardboardD); rect(c, x - hw + 4, y + 2, 10, 6, PX.W); rect(c, x - hw + 3, y - hw + 5, 6, 3, PX.RED)
                    if (cyc < 0.25f) for (k in 0..5) rect(c, x - hw - 6 + k * 7, y + hw - 2 + (k % 2) * 3, 4, 3, a(0xFFC8D0E0.toInt(), 0x90))     // dust
                } else {
                    val kk = ((cyc - onT) / (o.period - onT)).coerceIn(0f, 1f)
                    val sz = (hw * (0.35f + 0.65f * kk)).toInt()
                    rect(c, x - sz, y - sz, sz * 2, sz * 2, a(PX.K, 0x40 + (kk * 0x50).toInt()))
                    if ((clock * 10f).toInt() % 2 == 0 && kk > 0.5f) { rect(c, x - 8, y - 2, 16, 4, PX.RED); rect(c, x - 2, y - 8, 4, 16, PX.RED) }
                    if (kk > 0.75f) { val up = ((1f - kk) / 0.25f * 70f).toInt(); rect(c, x - hw, y - hw - up, hw * 2, hw * 2, PX.K); rect(c, x - hw + 1, y - hw - up + 1, hw * 2 - 2, hw * 2 - 2, 0xFF8A6A34.toInt()) }
                }
            }
            BeltWorld.FLYER -> {
                if (!o.armed) { if ((animT * 6f).toInt() % 2 == 0) rect(c, x - 7, y - 10, 14, 3, PX.RED); return }
                for (k in 0..3) rect(c, x - 3 + (hash(k, (clock * 20f).toInt(), 14) % 7) - 3, y + 12 + k * 5, 6 - k, 5, a(if (k < 2) 0xFFFFD84D.toInt() else 0xFFFF7A2A.toInt(), 0xE0 - k * 0x30))
                shadow(c, x, y, 22, 24)
                rect(c, x - 11, y - 12, 22, 24, PX.K); rect(c, x - 10, y - 11, 20, 22, 0xFF3A56B8.toInt()); rect(c, x - 10, y - 11, 20, 3, 0xFF6A8AF0.toInt())
                rect(c, x - 10, y - 3, 20, 2, PX.K); rect(c, x - 10, y + 4, 20, 2, PX.K); rect(c, x - 4, y - 8, 8, 4, hazard); px(c, x - 1, y - 7, PX.K)
            }
        }
    }

    /** The manhole you come up through and the scaffold tower you climb to reach the belts. */
    private fun wayIn(c: Canvas) {
        // the manhole in the floor, its cover slid aside
        val mx = sx(BeltWorld.MAN_X); val my = sy(BeltWorld.MAN_S)
        rect(c, mx - 9, my - 5, 19, 11, 0xFF2A2E34.toInt()); rect(c, mx - 7, my - 4, 15, 9, 0xFF020304.toInt()); rect(c, mx - 6, my - 3, 13, 1, 0xFF0C0E12.toInt())
        for (k in 0 until 3) rect(c, mx - 3, my - 2 + k * 3, 7, 1, 0xFF3A3E46.toInt())                                                    // the top rungs of the ladder below
        val cx = mx + (world.coverOff * 16f).toInt(); val cy = my + (world.coverOff * 3f).toInt()
        rect(c, cx - 7, cy - 4, 15, 9, PX.K); rect(c, cx - 6, cy - 3, 13, 7, 0xFF4A4E56.toInt()); for (k in 0 until 3) rect(c, cx - 4, cy - 2 + k * 2, 9, 1, 0xFF5E626C.toInt()); px(c, cx, cy, 0xFF2A2E34.toInt())
        // the scaffold: four poles, cross braces, planks on top, a ladder up the side, its shadow thrown down and to the right
        val bx = sx(BeltWorld.SCAF_X); val by = sy(BeltWorld.SCAF_S + 0.3f)
        rect(c, bx - 8, by - 10, 26, 30, a(PX.K, 0x60))
        val pole = 0xFF8A929E.toInt(); val poleD = 0xFF4A525E.toInt()
        for ((qx, qy) in listOf(-11 to -14, 11 to -14, -11 to 14, 11 to 14)) { rect(c, bx + qx - 1, by + qy - 1, 3, 3, poleD); px(c, bx + qx, by + qy, PX.W) }
        for (i in 0..22) { px(c, bx - 11 + i, by - 14 + (i * 28 / 22), pole); px(c, bx + 11 - i, by - 14 + (i * 28 / 22), poleD) }
        rect(c, bx - 11, by - 14, 23, 1, pole); rect(c, bx - 11, by + 14, 23, 1, poleD); rect(c, bx - 11, by - 14, 1, 29, pole); rect(c, bx + 11, by - 14, 1, 29, poleD)
        for (k in 0 until 4) { rect(c, bx - 10, by - 13 + k * 4, 21, 3, 0xFF8A6A3A.toInt()); rect(c, bx - 10, by - 13 + k * 4, 21, 1, 0xFFB08A50.toInt()) }   // planks on the top deck
        for (k in 0 until 7) rect(c, bx + 12, by - 12 + k * 4, 4, 1, pole); rect(c, bx + 12, by - 13, 1, 28, poleD); rect(c, bx + 16, by - 13, 1, 28, poleD)  // the ladder
    }

    // ---------------- me ----------------
    private fun player(c: Canvas, clock: Float) {
        val py = sy(world.s); val px0 = sx(world.x)
        if (world.phase == BeltWorld.Phase.INTRO) {
            val t = world.phaseT
            val run = 1 + ((clock * 10f).toInt() % 2)
            when {
                t < 1.7f -> {   // up out of the hole: clipped by its rim
                    val mx = sx(BeltWorld.MAN_X); val my = sy(BeltWorld.MAN_S)
                    c.save(); c.clipRect(mx - 14, my - 40, mx + 14, my + 1)
                    folk(c, mx, my + 2 + ((1f - world.outOfHole) * 26f).toInt(), 2, if (world.outOfHole in 0.05f..0.95f) run else 0, clerk, false, true); c.restore()
                }
                t < 2.6f -> { rect(c, px0 - 6, py + 5, 13, 3, a(PX.K, 0x80)); folk(c, px0, py + 6, 3, run, clerk, false, true) }
                else -> {       // up the ladder (higher = a touch bigger, the shadow falling away), then the jump
                    val h = if (t < 3.6f) world.climb else 1f - ((t - 3.6f) / 0.7f).coerceIn(0f, 1f)
                    val sc = 1f + 0.22f * h
                    rect(c, px0 - 6 + (h * 8).toInt(), py + 5 + (h * 6).toInt(), 13, 3, a(PX.K, 0x70))
                    c.save(); c.scale(sc, sc, px0.toFloat(), (py + 6).toFloat())
                    folk(c, px0, py + 6 - (world.air * 14).toInt(), if (t < 3.6f) 0 else 1, if (t < 3.6f) run else 1, clerk, false, true)
                    c.restore()
                }
            }
            return
        }
        when (world.phase) {
            BeltWorld.Phase.LAUNCH -> {
                val k = world.launchK
                for (i in 1..5) rect(c, px0 - 2, py + i * 5, 4, 7, a(PX.W, 0x50 - i * 8))
                c.save(); c.rotate(k * 900f, px0.toFloat(), (py - 8).toFloat()); c.scale(1f + world.air * 0.5f, 1f + world.air * 0.5f, px0.toFloat(), (py - 8).toFloat())
                folk(c, px0, py - (world.air * 28).toInt(), 0, 1, clerk, false, true)
                c.restore()
                rect(c, px0 - 8, py + 2, 16, 4, a(PX.K, (0x80 * (1f - world.air * 0.6f)).toInt()))
            }
            BeltWorld.Phase.PICKUP, BeltWorld.Phase.DONE -> {
                val t = world.phaseT
                if (world.phase == BeltWorld.Phase.PICKUP && t < 1.0f) { lying(c, px0 + 2, py + 8, clerk, true, true) }
                else {
                    rect(c, px0 - 6, py + 5, 13, 3, a(PX.K, 0x70)); folk(c, px0, py + 6, 0, 0, clerk, false, false); jetpackSprite(c, px0 - 1, py - 4)
                    if (world.phase == BeltWorld.Phase.DONE || t > 1.8f) { val fl = 6 + (clock * 30f).toInt() % 5; rect(c, px0 - 7, py + 6, 4, fl, 0xFFFF7A2A.toInt()); rect(c, px0 - 6, py + 6, 2, fl - 3, 0xFFFFE07A.toInt()); rect(c, px0 + 2, py + 6, 4, fl, 0xFFFF7A2A.toInt()); rect(c, px0 + 3, py + 6, 2, fl - 3, 0xFFFFE07A.toInt()) }
                }
            }
            BeltWorld.Phase.FALL -> {
                val k = (world.phaseT / 1.4f).coerceIn(0f, 1f)
                val sc = 1f - k * 0.7f
                c.save(); c.scale(sc, sc, px0.toFloat(), py.toFloat()); c.rotate(k * 400f, px0.toFloat(), (py - 8).toFloat())
                folk(c, px0, py, 0, 0, clerk, false, true)
                c.restore()
            }
            else -> {
                val blink = world.invuln > 0f && ((clock * 16f).toInt() % 2 == 0)
                rect(c, px0 - 6, py + 5, 13, 3, a(PX.K, 0x80))
                if (!blink) {
                    val lift = maxOf(world.air * 12f, world.jumpAir * 24f).toInt()
                    val frame = if (world.moving) 1 + ((clock * 11f).toInt() % 2) else 0
                    folk(c, px0, py + 6 - lift, 0, frame, clerk, false, true)
                }
            }
        }
    }
}
