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
        assertEquals(PrisonWorld.Period.ROLL_AM, w.period); assertEquals("06:00", w.clockText)
        run(w, 31f); assertEquals(PrisonWorld.Period.BREAKFAST, w.period)
        assertEquals(PrisonWorld.Period.WORK_AM, PrisonWorld.periodAt(8 * 60f))
        assertEquals(PrisonWorld.Period.FREE, PrisonWorld.periodAt(16 * 60f + 30))
        assertEquals(PrisonWorld.Period.LIGHTS_OUT, PrisonWorld.periodAt(3 * 60f))
    }

    @Test fun standingByYourCellCountsYou() {
        val w = awake()
        run(w, 10f)
        assertTrue(w.attended); assertEquals(0f, w.heat, 0.01f)
    }

    @Test fun theMorningCountBeatsYouIfYouAreNotOnYourSquare() {
        val w = PrisonWorld(PrisonLevel.rock()); repeat(90) { w.update(0.05f, 0f, 0f, 0f) }
        assertTrue("doors shut first thing", w.cellsShut)
        var g = 0f; while (w.countStage < 2 && g < 20f) { w.update(0.05f, 0f, 0f, 0f); g += 0.05f }   // you stay in your cell
        assertTrue("a guard comes for you", w.enforcer != null)
        while (!w.lockedInSpot && g < 40f) { w.update(0.05f, 0f, 0f, 0f); g += 0.05f }
        assertTrue("dragged onto your square", w.lockedInSpot && w.onSpot)
        assertTrue(w.heat >= 10f); assertFalse(w.cleanToday)
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
        g.face = kotlin.math.atan2(w.py - g.y, w.px - g.x)
        assertFalse(w.act())
        assertTrue("busted", w.bustStage >= 0)
        run(w, 6f)
        assertEquals(day + 1, w.day); assertFalse(w.has(PrisonWorld.Item.FILE)); assertTrue(w.countStage >= 0 || w.period == PrisonWorld.Period.ROLL_AM)
    }

    @Test fun aCleanDayEarnsTrust() {
        val w = awake()
        assertFalse(w.trusted)
        w.clock = 1259.9f; at(w, w.spec.startX, w.spec.startY); run(w, 1f)   // lights out, in your cell, nothing went wrong today
        var g = 0f; while (w.countStage != 1 && g < 40f) { w.update(0.05f, 0f, 0f, 0f); g += 0.05f }   // the night passes; morning
        at(w, w.countSpot.first + 0.5f, w.countSpot.second + 0.5f)                                        // out onto your square for the count
        while (w.countStage >= 0 && g < 90f) { w.update(0.05f, 0f, 0f, 0f); g += 0.05f }
        assertTrue("trusted the next morning", w.trusted)
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
        run(w, 6f)
        assertTrue("dragged in", w.inMyCell)
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

    @Test fun aGuardFetchesYouForBreakfastAndKeepsWatch() {
        val w = awake()
        run(w, 31f)
        assertEquals(PrisonWorld.Period.BREAKFAST, w.period)
        val g = w.escort!!
        assertTrue("he turns up next to you", kotlin.math.hypot(g.x - w.px, g.y - w.py) < 4.5f)
        assertEquals("FOLLOW THE GUARD", w.nextStep().first)
        at(w, 15.5f, 18.5f)                                   // you get to the dining hall
        var t = 0f; while (!w.escortArrived && t < 40f) { w.update(0.05f, 0f, 0f, 0f); t += 0.05f }
        assertTrue("he got there and keeps watch", w.escortArrived && g.post != null)
    }

    @Test fun aWatchingGuardStopsYouStealingADistractedOneDoesnt() {
        val w = awake()
        w.clock = 419.9f; run(w, 0.2f)                         // breakfast
        for (n in w.npcs) if (n.guard) { n.x = 70.5f; n.y = 44.5f; n.path.clear(); n.post = null; n.escorting = false }
        val g = w.npcs.first { it.guard }
        g.x = 15.5f; g.y = 17.5f; g.post = 15 to 17; g.attn = PrisonWorld.WATCH; g.attnT = 99f
        at(w, 12.5f, 11.5f)                                    // next to a table
        assertEquals("TAKE A SPOON", w.prompt())
        w.update(0.05f, 0f, 0f, 0f); g.x = 15.5f; g.y = 17.5f
        assertFalse(w.act()); assertFalse(w.has(PrisonWorld.Item.SPOON))
        g.attn = PrisonWorld.DISTRACT; g.attnT = 99f
        assertTrue(w.act()); assertTrue(w.has(PrisonWorld.Item.SPOON))
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
}
