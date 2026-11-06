package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
class PrisonTest {
    private fun save(b: Bitmap, name: String, scale: Int = 3) {
        val big = Bitmap.createScaledBitmap(b, b.width * scale, b.height * scale, false)
        val dir = File("build/shots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { big.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun mapIsTheRightSize() {
        val s = PrisonLevel.rock()
        assertEquals(PrisonLevel.H, s.rows)
        assertTrue(s.map.all { it.length == PrisonLevel.W })
        assertEquals('O', s.tile(s.myDoor.first, s.myDoor.second))
    }

    @Test fun everyOpenRoomCanBeReachedFromYourCell() {
        val w = PrisonWorld(PrisonLevel.rock())
        val names = w.reachable().map { PrisonLevel.zoneName(w.spec, it.first, it.second) }.toSet()
        for (z in listOf("RECREATION YARD", "DINING HALL", "KITCHEN", "SHOWERS", "INFIRMARY", "LIBRARY", "TIMES SQUARE", "BROADWAY",
                "MICHIGAN AVENUE", "SEEDY STREET", "EAST CORRIDOR", "VISITING ROOM", "BARBER", "LAUNDRY", "A BLOCK", "B BLOCK", "C BLOCK", "D BLOCK"))
            assertTrue("can't reach $z", z in names)
        for (z in listOf("CONTROL CENTRE", "WARDEN'S OFFICE", "FRONT GATE", "PIPE CORRIDOR"))
            assertFalse("$z should be locked", z in names)
    }

    @Test fun youWakeUpThenWalkOutOfYourCell() {
        val w = PrisonWorld(PrisonLevel.rock())
        val y0 = w.py
        repeat(40) { w.update(0.05f, 0f, 1f, 1f) }          // pushing during the wake-up does nothing
        assertEquals(PrisonWorld.Phase.WAKE, w.phase); assertEquals(y0, w.py, 0.001f)
        repeat(60) { w.update(0.05f, 0f, 0f, 0f) }
        assertEquals(PrisonWorld.Phase.PLAY, w.phase)
        var g = 0f; while (w.countStage < 1 && g < 5f) { w.update(0.05f, 0f, 0f, 0f); g += 0.05f }
        repeat(25) { w.update(0.05f, 0f, 0f, 0f) }           // "please exit your cell": the door slides open
        repeat(30) { w.update(0.05f, 0f, 1f, 1f) }           // walk down through the open door onto Broadway
        assertEquals("BROADWAY", w.zone)
    }

    @Test fun peopleKeepMovingAndStayOnTheFloor() {
        val w = PrisonWorld(PrisonLevel.rock())
        val start = w.npcs.map { it.x to it.y }
        repeat(1400) { w.update(0.05f, 0f, 0f, 0f) }          // past 7:00, when everyone heads to breakfast
        val moved = w.npcs.indices.count { i -> val (x, y) = start[i]; kotlin.math.hypot(w.npcs[i].x - x, w.npcs[i].y - y) > 1f }
        assertTrue("only $moved moved", moved > w.npcs.size / 2)
        for (n in w.npcs) assertTrue("${n.id} is in a wall", w.walkable(n.x.toInt(), n.y.toInt()))
    }

    /** Wakes up and gets through the first morning count standing on your square. */
    private fun awake(): PrisonWorld {
        val w = PrisonWorld(PrisonLevel.rock()); repeat(90) { w.update(0.05f, 0f, 0f, 0f) }
        var g = 0f; while (w.countStage < 1 && g < 5f) { w.update(0.05f, 0f, 0f, 0f); g += 0.05f }
        w.px = w.countSpot.first + 0.5f; w.py = w.countSpot.second + 0.5f
        while (w.countStage >= 0 && g < 60f) { w.update(0.05f, 0f, 0f, 0f); g += 0.05f }
        return w
    }
    private fun run(w: PrisonWorld, sec: Float) { var t = sec; while (t > 0f) { w.update(0.05f, 0f, 0f, 0f); t -= 0.05f } }
    private fun at(w: PrisonWorld, x: Float, y: Float) { w.px = x; w.py = y }

    @Test fun theDayFollowsTheRoutine() {
        val w = awake()
        run(w, 0.2f)
        assertEquals("the count is over: breakfast is called", PrisonWorld.Period.BREAKFAST, w.period); assertEquals("07:00", w.clockText)
        assertEquals(PrisonWorld.Period.WORK_AM, PrisonWorld.periodAt(8 * 60f))
        assertEquals(PrisonWorld.Period.FREE, PrisonWorld.periodAt(16 * 60f + 30))
        assertEquals(PrisonWorld.Period.LIGHTS_OUT, PrisonWorld.periodAt(3 * 60f))
    }

    @Test fun standingOnYourSquareGetsYouCounted() {
        val w = awake()
        assertEquals(0f, w.heat, 0.01f); assertEquals(PrisonWorld.HEARTS, w.hearts); assertTrue(w.cleanToday)
    }

    @Test fun theMorningCountBeatsYouIfYouAreNotOnYourSquare() {
        val w = PrisonWorld(PrisonLevel.rock()); repeat(90) { w.update(0.05f, 0f, 0f, 0f) }
        assertTrue("doors shut first thing", w.cellsShut)
        var g = 0f; while (w.countStage < 2 && g < 20f) { w.update(0.05f, 0f, 0f, 0f); g += 0.05f }   // you stay in your cell
        assertTrue("a guard comes for you", w.enforcer != null)
        while (!w.lockedInSpot && g < 40f) { w.update(0.05f, 0f, 0f, 0f); g += 0.05f }
        assertTrue("dragged onto your square", w.lockedInSpot && w.onSpot)
        assertTrue(w.heat >= 10f); assertFalse(w.cleanToday)
        assertEquals("one smack, one heart", PrisonWorld.HEARTS - 1, w.hearts)
        while (w.countStage >= 0 && g < 90f) { w.update(0.05f, 0f, 0f, 0f); g += 0.05f }
        assertFalse(w.lockedInSpot)
    }

    @Test fun beingSeenPocketingSomethingGetsYouLockedUpTillMorning() {
        val w = awake()
        w.debugGive(PrisonWorld.Item.FILE)
        w.clock = 419.9f; run(w, 0.2f)
        val g = w.npcs.first { it.guard }
        for (n in w.npcs) if (n.guard && n !== g) { n.x = 70.5f; n.y = 44.5f; n.path.clear() }
        at(w, 12.5f, 11.5f); g.x = 12.5f; g.y = 14.5f; g.path.clear(); g.post = 12 to 14; g.attn = PrisonWorld.WATCH; g.attnT = 99f
        val day = w.day
        assertTrue(w.act()); assertTrue("sat down", w.seated)
        g.face = kotlin.math.atan2(w.py - g.y, w.px - g.x)
        assertFalse(w.act())
        assertTrue("busted", w.bustStage >= 0)
        run(w, 6f)
        assertEquals(day + 1, w.day); assertFalse(w.has(PrisonWorld.Item.FILE)); assertTrue(w.countStage >= 0 || w.period == PrisonWorld.Period.ROLL_AM)
    }

    /** Skip a whole shift where no guard can see you: nothing happens to you. */
    @Test fun skippingUnseenCostsNothing() {
        val w = awake()
        w.clock = 479.9f; run(w, 0.2f)                                          // the laundry shift
        assertEquals("LAUNDRY", w.dueAt)
        at(w, 4.5f, 33.5f)                                                     // hiding in the infirmary instead
        for (n in w.npcs) if (n.guard) { n.x = 66.5f; n.y = 44.5f; n.path.clear(); n.post = 66 to 44; n.door = null; n.attn = PrisonWorld.WATCH; n.attnT = 99f }
        var t = 0f; while (w.period == PrisonWorld.Period.WORK_AM && t < 200f) { w.update(0.05f, 0f, 0f, 0f); at(w, 4.5f, 33.5f); for (n in w.npcs) if (n.guard) { n.x = 66.5f; n.y = 44.5f }; t += 0.05f }
        assertEquals(PrisonWorld.Period.LUNCH, w.period)
        assertEquals("not a scratch", PrisonWorld.HEARTS, w.hearts); assertEquals(-1, w.grabStage); assertEquals(0f, w.heat, 0.5f)
    }

    @Test fun aLaundryLoadIsThreeBagsWashedDriedFoldedAndDelivered() {
        val w = awake()
        w.clock = 479.9f; run(w, 0.2f)
        assertEquals(PrisonWorld.Period.WORK_AM, w.period); assertEquals(3, w.dirtyBags)
        repeat(3) {
            at(w, 61.5f, 44.5f); assertEquals("TAKE A BAG", w.prompt()); assertTrue(w.act()); assertEquals(PrisonWorld.Carry.DIRTY, w.carry)
            at(w, 61.5f, 39.5f); assertEquals("WASH IT", w.prompt()); assertTrue(w.act()); assertEquals(PrisonWorld.Carry.NONE, w.carry)
            run(w, PrisonWorld.WASH_S + 0.2f); assertTrue(w.act()); assertEquals(PrisonWorld.Carry.WET, w.carry)
            at(w, 67.5f, 39.5f); assertEquals("DRY IT", w.prompt()); assertTrue(w.act())
            run(w, PrisonWorld.DRY_S + 0.2f); assertTrue(w.act()); assertEquals(PrisonWorld.Carry.DRY, w.carry)
            at(w, 64.5f, 43.5f); assertTrue(w.act()); run(w, PrisonWorld.FOLD_S + 0.2f); assertEquals(PrisonWorld.Carry.FOLDED, w.carry)
            at(w, 69.5f, 44.5f); assertTrue(w.act())
        }
        assertTrue(w.shiftDone); assertEquals(1, w.loadsToday)
        assertEquals(null, w.dueAt)                // free until lunch
    }

    @Test fun theLaundryIsClosedOutsideYourShifts() {
        val w = awake()
        at(w, 61.5f, 44.5f)
        assertFalse(w.act()); assertEquals(PrisonWorld.Carry.NONE, w.carry)
    }

    @Test fun lightsOutDragsYouToYourCellThenMorningComes() {
        val w = awake()
        w.clock = 1259.9f; at(w, 20.5f, 42.5f)
        var g = 0f; while (!w.inMyCell && g < 80f) { w.update(0.05f, 0f, 0f, 0f); g += 0.05f }
        assertTrue("walked in by a guard", w.inMyCell)
        assertTrue(w.night)
        run(w, 25f)                               // the night flies by in your cell
        assertEquals(2, w.day); assertEquals(PrisonWorld.Period.ROLL_AM, w.period)
        run(w, 4f); assertTrue(w.doorOpen > 0.9f)
    }

    private fun nightInCell(w: PrisonWorld) { w.debugEndCount(); w.clock = ((w.day - 1) * 1440 + 1262).toFloat(); at(w, w.spec.startX, w.spec.startY - 1f); run(w, 0.2f) }

    @Test fun theMirrorSendsTheSignal() {
        val w = awake()
        at(w, 4.5f, 2.5f)
        assertEquals("PICK UP THE MIRROR PIECE", w.prompt())
        assertTrue(w.act()); assertEquals(PrisonWorld.Phase.SIGNAL, w.phase)
        run(w, PrisonWorld.SIGNAL_S + 0.2f)
        assertEquals(PrisonWorld.Phase.PLAY, w.phase); assertTrue(w.signalSent)
    }

    @Test fun diggingOutWithoutTheSignalDrownsYouAndYouWakeUpNextDay() {
        val w = awake()
        w.debugGive(PrisonWorld.Item.SPOON)
        repeat(PrisonWorld.DIG_NIGHTS) {
            nightInCell(w); assertTrue("in cell", w.inMyCell)
            assertEquals("DIG TONIGHT", w.prompt()); assertTrue(w.act())
            run(w, 25f)                                    // the night passes
            assertEquals(PrisonWorld.Period.ROLL_AM, w.period)
        }
        assertEquals(PrisonWorld.DIG_NIGHTS, w.digNights)
        nightInCell(w)
        assertEquals("CRAWL OUT", w.prompt()); assertTrue(w.act())
        assertEquals(PrisonWorld.Phase.ESCAPE, w.phase); assertTrue(w.drowned)
        val day = w.day
        run(w, PrisonWorld.DROWN_S + 0.2f); assertFalse(w.done)
        w.retry()
        assertEquals(PrisonWorld.Phase.PLAY, w.phase); assertEquals(day + 1, w.day); assertTrue(w.has(PrisonWorld.Item.SPOON))
    }

    @Test fun withTheSignalTheBoatPicksYouUp() {
        val w = awake()
        at(w, 4.5f, 2.5f); w.act(); run(w, PrisonWorld.SIGNAL_S + 0.2f)
        w.debugGive(PrisonWorld.Item.UNIFORM); w.debugHaircut()
        at(w, w.spec.startX, w.spec.startY - 1f)
        assertEquals("PUT ON THE UNIFORM", w.prompt()); assertTrue(w.act()); assertTrue(w.disguised)
        assertTrue("the guards' door opens for you", w.walkable(PrisonWorld.GATE_DOOR.first, PrisonWorld.GATE_DOOR.second))
        at(w, 70.5f, 34.5f)
        assertEquals("WALK OUT THE GATE", w.prompt()); assertTrue(w.act())
        assertEquals(PrisonWorld.Route.GATE, w.route); assertFalse(w.drowned)
        run(w, PrisonWorld.ESCAPE_RESCUED + 0.2f); assertTrue(w.done)
    }

    @Test fun ropeAndHookAreMadeInYourCell() {
        val w = awake()
        w.debugGive(PrisonWorld.Item.SHEET, 3); w.debugGive(PrisonWorld.Item.FORK); w.debugGive(PrisonWorld.Item.PIPE)
        at(w, w.spec.startX, w.spec.startY - 1f)
        assertTrue(w.act()); assertTrue(w.act())
        assertTrue(w.has(PrisonWorld.Item.ROPE)); assertTrue(w.has(PrisonWorld.Item.HOOK)); assertFalse(w.has(PrisonWorld.Item.SHEET))
    }

    @Test fun escapeShots() {
        val w = awake(); val s = PrisonScene(w)
        var t = 0f
        fun step(sec: Float) { val end = t + sec; while (t < end) { w.update(0.05f, 0f, 0f, 0f); t += 0.05f; s.render(t) } }
        at(w, 4.5f, 2.5f); w.act()
        step(1.0f); save(s.render(t), "l5-signal-flash")
        step(3.5f); save(s.render(t), "l5-signal-boat")
        step(2f)
        w.debugGive(PrisonWorld.Item.UNIFORM); w.debugHaircut(); at(w, w.spec.startX, w.spec.startY - 1f); w.act(); at(w, 70.5f, 34.5f); w.act()
        step(2f); save(s.render(t), "l5-escape-boat-arrives")
        step(1.7f); save(s.render(t), "l5-escape-jump")
        step(3f); save(s.render(t), "l5-escape-sails")
        val w2 = awake(); val s2 = PrisonScene(w2); w2.debugGive(PrisonWorld.Item.SPOON)
        repeat(3) { nightInCell(w2); w2.act(); run(w2, 25f) }
        nightInCell(w2); w2.act()
        var t2 = 0f
        fun step2(sec: Float) { val end = t2 + sec; while (t2 < end) { w2.update(0.05f, 0f, 0f, 0f); t2 += 0.05f; s2.render(t2) } }
        step2(1f); save(s2.render(t2), "l5-drown-edge")
        step2(1f); save(s2.render(t2), "l5-drown-dive")
        step2(1.2f); save(s2.render(t2), "l5-drown-splash")
    }

    @Test fun nobodyWalksYouAnywhereButAGuardKeepsWatchAtTheLaundry() {
        val w = awake()
        assertEquals("breakfast is up to you", null, w.dueAt)
        w.clock = 479.9f; run(w, 0.2f)
        assertEquals(null, w.escort)
        val g = w.dutyGuard!!
        var t = 0f; while (t < 60f && !(w.zoneOf(g) == "LAUNDRY" || kotlin.math.hypot(g.x - 64.5f, g.y - 44.5f) < 6f)) { w.update(0.05f, 0f, 0f, 0f); t += 0.05f }
        assertTrue("he went to the laundry (at ${g.x},${g.y})", w.zoneOf(g) == "LAUNDRY" || kotlin.math.hypot(g.x - 64.5f, g.y - 44.5f) < 6f)
    }

    @Test fun atBreakfastYouSitDownAndEat() {
        val w = awake(); run(w, 0.2f)
        at(w, 12.5f, 11.5f)                                    // by a table
        assertEquals("SIT DOWN AND EAT", w.prompt()); assertTrue(w.act()); assertTrue(w.seated)
        run(w, PrisonWorld.EAT_MIN / PrisonWorld.DAY_RATE + 0.5f)
        assertTrue("ate", w.attended)
        w.update(0.05f, 1f, 0f, 1f); assertFalse("the stick gets you up", w.seated)
        run(w, 20f)
        assertTrue("the men are at the tables", w.npcs.count { w.activityOf(it) == "eating" } >= 8)
    }

    @Test fun breakfastShot() {
        val w = awake(); val s = PrisonScene(w); run(w, 0.2f)
        at(w, 12.5f, 11.5f); w.act()
        var t = 0f; while (t < 22f) { w.update(0.05f, 0f, 0f, 0f); t += 0.05f; s.render(t) }
        save(s.render(t), "l5-breakfast")
    }

    /** Seen skipping: one beating, dragged to the laundry, and then you don't have to finish the load. */
    @Test fun seenSkippingGetsYouBeatenDraggedThereAndExcused() {
        val w = awake()
        w.clock = 479.9f; run(w, 0.2f)
        w.clock = 479.9f + 150f; run(w, 0.1f)                                    // well past the time it takes to get there
        val g = w.npcs.first { it.guard && it.door == null }
        at(w, 35.5f, 19.5f)                                                    // out on Broadway
        g.x = 38.5f; g.y = 19.5f; g.path.clear(); g.post = 38 to 19; g.attn = PrisonWorld.WATCH; g.attnT = 99f
        var t = 0f; while (w.grabStage < 0 && t < 3f) { w.update(0.05f, 0f, 0f, 0f); t += 0.05f }
        assertTrue("he comes for you", w.grabStage >= 0)
        while (w.grabStage >= 0 && t < 60f) { w.update(0.05f, 0f, 0f, 0f); t += 0.05f }
        assertEquals("one beating", PrisonWorld.HEARTS - 1, w.hearts)
        assertEquals("dragged to the laundry", "LAUNDRY", w.zone)
        assertTrue(w.excused); assertEquals("nothing more due", null, w.dueAt)
        w.clock = 719.9f; run(w, 0.3f)                                         // the shift ends unfinished
        assertEquals("no more trouble for it", PrisonWorld.HEARTS - 1, w.hearts); assertEquals(-1, w.grabStage)
    }

    @Test fun threeBeatingsKillYouAndYouWakeTheSameDay() {
        val w = awake()
        w.debugGive(PrisonWorld.Item.SPOON)
        fun settle() { var k = 0f; while (w.grabStage >= 0 && k < 40f) { w.update(0.05f, 0f, 0f, 0f); k += 0.05f } }
        w.debugBeat(); var g = 0f; while (w.hearts > 2 && g < 40f) { w.update(0.05f, 0f, 0f, 0f); g += 0.05f }
        settle(); w.debugBeat(); g = 0f; while (w.hearts > 1 && g < 40f) { w.update(0.05f, 0f, 0f, 0f); g += 0.05f }
        settle(); w.debugBeat(); g = 0f; while (!w.dead && g < 40f) { w.update(0.05f, 0f, 0f, 0f); g += 0.05f }
        assertTrue("YOU DIED", w.dead)
        val day = w.day
        run(w, PrisonWorld.DEAD_S + 0.2f); w.retry()
        assertFalse(w.dead); assertEquals(PrisonWorld.HEARTS, w.hearts); assertEquals(day, w.day); assertTrue("you keep your things", w.has(PrisonWorld.Item.SPOON))
        assertEquals(PrisonWorld.Period.ROLL_AM, w.period)
    }

    /** Nobody jumps from place to place and nobody walks backwards: a whole day where you do nothing (so the guards come for you). */
    @Test fun everyoneWalksNoJumpsNoMoonwalking() {
        val w = PrisonWorld(PrisonLevel.rock())
        val dt = 0.05f; var frames = 0
        var lastCount = w.countStage
        while (frames < 9500 && w.day < 2) {
            val before = w.npcs.map { it.x to it.y }
            w.update(dt, 0f, 0f, 0f); frames++
            val nightReset = w.countStage == 0 && lastCount != 0; lastCount = w.countStage
            if (w.phase != PrisonWorld.Phase.PLAY || nightReset || w.bustStage >= 0) continue
            for ((i, n) in w.npcs.withIndex()) {
                val (x0, y0) = before[i]; val dx = n.x - x0; val dy = n.y - y0; val d = kotlin.math.hypot(dx, dy)
                assertTrue("${n.id} jumped $d at ${w.clockText}", d <= 4.7f * dt + 0.02f)
                if (d > 0.02f) { val want = kotlin.math.atan2(dy, dx); assertEquals("${n.id} faces the way he walks", kotlin.math.cos(want).toDouble(), kotlin.math.cos(n.face).toDouble(), 0.05); assertEquals(kotlin.math.sin(want).toDouble(), kotlin.math.sin(n.face).toDouble(), 0.05) }
            }
        }
        assertTrue("got through the day", frames > 3000)
    }

    @Test fun aWatchingGuardStopsYouStealingADistractedOneDoesnt() {
        val w = awake()
        w.clock = 419.9f; run(w, 0.2f)                         // breakfast
        for (n in w.npcs) if (n.guard) { n.x = 70.5f; n.y = 44.5f; n.path.clear(); if (n.door == null) n.post = null; n.escorting = false; n.patrol = null }
        val g = w.npcs.first { it.guard }
        g.x = 15.5f; g.y = 17.5f; g.post = 15 to 17; g.attn = PrisonWorld.WATCH; g.attnT = 99f
        at(w, 12.5f, 11.5f)                                    // next to a table
        assertTrue(w.act()); assertTrue("sat down", w.seated)
        assertEquals("TAKE A SPOON", w.prompt())
        w.update(0.05f, 0f, 0f, 0f); g.x = 15.5f; g.y = 17.5f
        assertFalse(w.act()); assertFalse(w.has(PrisonWorld.Item.SPOON)); assertTrue("caught", w.bustStage >= 0)
        // the same again with his back turned
        val w2 = awake(); w2.clock = 419.9f; run(w2, 0.2f)
        for (n in w2.npcs) if (n.guard) { n.x = 70.5f; n.y = 44.5f; n.path.clear(); if (n.door == null) n.post = null; n.escorting = false; n.patrol = null }
        val g2 = w2.npcs.first { it.guard }; g2.x = 15.5f; g2.y = 17.5f; g2.post = 15 to 17; g2.attn = PrisonWorld.DISTRACT; g2.attnT = 99f
        at(w2, 12.5f, 11.5f); assertTrue(w2.act()); assertTrue(w2.seated)
        assertTrue(w2.act()); assertTrue(w2.has(PrisonWorld.Item.SPOON))
    }

    @Test fun inmatesHaveThingsToSay() {
        val w = awake()
        val doc = w.npcs.first { it.persona?.name == "DOC" }
        at(w, doc.x + 0.8f, doc.y)
        assertEquals("TALK TO DOC", w.prompt())
        w.act()
        assertTrue(doc.say.contains("MIRROR"))
        val rook = w.npcs.first { it.persona?.name == "ROOK" }
        at(w, rook.x + 0.8f, rook.y); w.talk(rook)
        assertEquals("...", rook.say)
    }

    @Test fun shots() {
        val w = PrisonWorld(PrisonLevel.rock()); val s = PrisonScene(w)
        var t = 0f
        fun step(until: Float, ix: Float = 0f, iy: Float = 0f, mag: Float = 0f) { while (t < until) { w.update(0.02f, ix, iy, mag); t += 0.02f; s.render(t) } }
        save(s.renderWholeMap(), "l5-prison-map", 1)
        save(s.renderWholeMap(true), "l5-prison-map-night", 1)
        step(0.5f); save(s.render(t), "l5-wake-dark")
        step(1.9f); save(s.render(t), "l5-wake-lights")
        step(4.5f); save(s.render(t), "l5-door-open")
        step(5.3f, 0f, 1f, 1f); step(9f, -1f, 0f, 1f); save(s.render(t), "l5-broadway-west")
        w.px = 15.5f; w.py = 19.5f; step(t + 1f); save(s.render(t), "l5-dining")
        w.px = 20.5f; w.py = 41.5f; step(t + 1f); save(s.render(t), "l5-yard")
        w.px = 60.5f; w.py = 14.5f; step(t + 1f); save(s.render(t), "l5-east")
        w.clock = 479.9f; step(t + 0.2f)
        at(w, 61.5f, 44.5f); w.act(); at(w, 61.5f, 39.5f); w.act(); at(w, 61.5f, 44.5f); w.act(); at(w, 63.5f, 39.5f); w.act()
        at(w, 61.5f, 44.5f); w.act(); at(w, 64.5f, 40.5f); step(t + 1f); save(s.render(t), "l5-laundry")
        w.clock = 1150f; at(w, 11.5f, 4.5f); step(t + 1f); save(s.render(t), "l5-showers")
        w.clock = 1000f; at(w, 4.5f, 16.5f); step(t + 1f); save(s.render(t), "l5-kitchen")
        at(w, 32.5f, 32.5f); step(t + 1f); save(s.render(t), "l5-library")
        at(w, 35.5f, 19.5f); step(t + 1f); save(s.render(t), "l5-broadway-day")
        w.clock = 1300f; at(w, w.spec.startX, w.spec.startY); step(t + 0.5f); save(s.render(t), "l5-night")
        w.clock = 1262f; at(w, 40.5f, 43.5f); step(t + 0.5f); save(s.render(t), "l5-yard-night")
    }

    /** Each of the ten events plays out on its day, and leaves its marks: hurt for two days, some men gone, high alert after the escape. */
    @Test fun everyEventPlaysOutAndLeavesItsMarks() {
        for (k in 0 until 10) {
            val d = 2 + k * 2
            val e = PrisonEvents.on(d)!!
            val w = awake(); val s = PrisonScene(w)
            w.debugDay(d); run(w, 0.1f)
            val start = (d - 1) * 1440f + e.period.start
            if (e.period != PrisonWorld.Period.BREAKFAST) { w.clock = start - 0.1f; run(w, 0.2f) }
            val goal = w.goalFor(e.period); if (goal != null) at(w, goal.first + 0.5f, goal.second + 0.5f)
            for (n in w.npcs) if (n.guard && !n.inEvent) n.attnT = 99f
            var t = 0f; while (w.evNow == null && t < 60f) { w.update(0.05f, 0f, 0f, 0f); t += 0.05f }
            assertEquals("day $d: ${e.name} starts", e, w.evNow)
            val c = w.evCenter; at(w, c.first + 0.5f, c.second + 2.5f)
            var shots = 0; t = 0f
            while (w.evNow != null && t < 150f) {
                val before = w.npcs.map { it.x to it.y }
                w.update(0.05f, 0f, 0f, 0f); t += 0.05f
                if (w.grabStage < 0 && w.bustStage < 0) for ((i, n) in w.npcs.withIndex()) { val (x0, y0) = before[i]; assertTrue("${e.name}: ${n.id} jumped", kotlin.math.hypot(n.x - x0, n.y - y0) <= 4.7f * 0.05f + 0.02f) }
                if (shots < 3 && t > 6f + shots * 9f) { repeat(40) { s.render(t) }; save(s.render(t), "l5-ev-${e.name.lowercase()}-$shots"); shots++ }
                at(w, c.first + 0.5f, c.second + 2.5f)
            }
            assertEquals("day $d: ${e.name} ends", null, w.evNow)
            assertEquals(d, w.doneDay)
            for (m in PrisonEvents.marksOf(e)) if (m.from == 0) {
                val n = w.npcs.first { w.keyOf(it) == m.who }
                if (m.kind == "gone") assertTrue("${m.who} is gone", w.gone(n)) else assertEquals("${m.who} is ${m.kind}", m.kind, w.hurtOf(n))
            }
            assertTrue("nobody's left in the scene", w.npcs.none { it.inEvent })
        }
    }

    @Test fun marksLastTwoDaysAndTheAlertFollowsTheEscape() {
        val tankHurt = { day: Int, done: Boolean -> PrisonEvents.marks(day, done)["TANK"]?.contains("bruised") == true }
        assertFalse("not before the brawl", tankHurt(2, false)); assertTrue(tankHurt(2, true)); assertTrue(tankHurt(3, false)); assertTrue(tankHurt(4, false)); assertFalse(tankHurt(5, false))
        assertTrue("Numbers missing at the count", PrisonEvents.marks(6, false)["NUMBERS"]?.contains("gone") == true)
        assertTrue(PrisonEvents.marks(7, false)["NUMBERS"]?.contains("gone") == true)
        assertEquals(setOf("bruised"), PrisonEvents.marks(8, false)["NUMBERS"])
        assertFalse(PrisonEvents.alertOn(5)); assertTrue(PrisonEvents.alertOn(6)); assertTrue(PrisonEvents.alertOn(7)); assertFalse(PrisonEvents.alertOn(8))
        assertEquals(10, (2..20 step 2).map { PrisonEvents.on(it) }.toSet().size)
        assertEquals(null, PrisonEvents.on(3))
    }

    @Test fun theMenSayDifferentThingsEachDayAndTalkAboutWhatHappened() {
        val w = awake()
        val lou = w.npcs.first { it.persona?.name == "BIG LOU" }
        val day1 = w.linesFor(lou)
        w.debugDay(3); val day3 = w.linesFor(lou)
        assertTrue("new things to say", day1 != day3)
        assertTrue("he talks about the brawl he was in", day3.first() in PrisonEvent.BRAWL.involved["BIG LOU"]!!)
        val doc = w.npcs.first { it.persona?.name == "DOC" }
        assertTrue("everyone talks about it", w.linesFor(doc).any { it in PrisonEvent.BRAWL.talk })
        val pools = PrisonEvents.TIPS + PrisonEvents.GUARD_TIPS + PrisonEvents.FACTS + PrisonEvents.JOKES + PrisonEvent.values().flatMap { it.talk + it.involved.values.flatten() }
        for (l in pools) assertTrue("short enough: $l", l.length <= 62)
    }

    @Test fun onHighAlertTheGuardsSeeFurtherAndNeverLookAway() {
        val w = awake(); w.debugDay(6)
        assertTrue(w.alert); assertTrue(w.seeRange > PrisonWorld.SEE)
    }

    /** Days 2 to 4 left to run on their own (brawl, the arrest): nothing crashes, nobody jumps. */
    @Test fun eventDaysRunOnTheirOwn() {
        val w = awake(); w.debugDay(2)
        val dt = 0.05f; var frames = 0; var lastCount = w.countStage; var events = 0; var lastEv: PrisonEvent? = null
        while (frames < 45000 && w.day < 5 && w.doneDay < 4) {
            val before = w.npcs.map { it.x to it.y }
            w.update(dt, 0f, 0f, 0f); frames++
            if (w.dead && w.deadT > PrisonWorld.DEAD_S) w.retry()
            if (w.evNow != null && w.evNow != lastEv) events++; lastEv = w.evNow
            val nightReset = w.countStage == 0 && lastCount != 0; lastCount = w.countStage
            if (w.phase != PrisonWorld.Phase.PLAY || nightReset || w.bustStage >= 0 || w.dead) continue
            for ((i, n) in w.npcs.withIndex()) { val (x0, y0) = before[i]; assertTrue("${n.id} jumped at day ${w.day} ${w.clockText}", kotlin.math.hypot(n.x - x0, n.y - y0) <= 4.7f * dt + 0.02f) }
        }
        assertTrue("both events happened ($events) day ${w.day} frames $frames done ${w.doneDay} hearts ${w.hearts}", events >= 2)
    }
}
