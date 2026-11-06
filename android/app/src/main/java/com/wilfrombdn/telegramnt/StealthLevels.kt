package com.wilfrombdn.telegramnt

// DEAD LETTER: six levels, six letters. You are a city clerk who found proof that the city has been rewriting its citizens.
// They blamed you for a bombing that never happened. Phones are compromised, so the evidence travels as paper:
// six letters to six people who each hold one piece. Together, the letters tell the truth.
//
// Maps are 9 wide and 15 tall:
//   # wall   c tall cover (blocks movement and sight)   d low obstacle (blocks movement only)   . floor   L a lamp (pool of light)
//   S you start   C your contact   k a key   D the door it opens
// Guards walk between waypoints (column, row) by the shortest way. Props are things to look at; with a text they are clues.

internal object StealthLevels {
    private fun pal(floor: Long, line: Long, wall: Long, top: Long, accent: Long, lamp: Long, cone: Long) =
        Palette(floor.toInt(), line.toInt(), wall.toInt(), top.toInt(), accent.toInt(), lamp.toInt(), cone.toInt())

    /** The back alleys: three east-west alleys joined by short passages, ending on a street with a newsstand. 16 wide, 48 tall. */
    private fun backstreetsMap(): List<String> {
        val g = Array(48) { CharArray(16) { '#' } }
        fun fill(c0: Int, r0: Int, c1: Int, r1: Int) { for (r in r0..r1) for (c in c0..c1) g[r][c] = '.' }
        fun put(c: Int, r: Int, ch: Char) { g[r][c] = ch }
        fill(1, 5, 14, 10)      // the street
        fill(0, 7, 0, 9)        // and a way out of it to the west: a side street the cop walks off into
        put(0, 9, 'L')          // a lamp at its mouth
        fill(1, 11, 4, 22)      // the passage up to it
        fill(1, 23, 14, 25)     // alley 3
        fill(12, 26, 14, 33)    // passage
        fill(1, 34, 14, 36)     // alley 2
        fill(1, 37, 3, 43)      // passage
        fill(1, 44, 14, 46)     // alley 1 (you start at its east end)
        put(13, 45, 'S'); put(10, 7, 'C')
        for ((c, r) in listOf(12 to 6, 13 to 6, 1 to 5, 2 to 5, 9 to 8, 10 to 8, 11 to 8)) put(c, r, 'd')            // police cars, the newsstand counter
        for ((c, r) in listOf(5 to 44, 10 to 44, 3 to 34, 8 to 34, 11 to 34, 6 to 23, 11 to 23, 1 to 39, 14 to 30, 1 to 16)) put(c, r, 'c')   // bins
        for ((c, r) in listOf(14 to 44, 2 to 36, 14 to 25)) put(c, r, 'd')                                             // crates
        for ((c, r) in listOf(8 to 45, 6 to 35, 9 to 24, 2 to 40, 13 to 29, 3 to 17, 4 to 7, 7 to 9, 12 to 9)) put(c, r, 'L')   // street lamps
        return g.map { String(it) }
    }

    /** The new area for level 2: two alleys and a passage up to a yard with a manhole in the middle. 16 wide, 30 tall. */
    private fun undergroundMap(): List<String> {
        val g = Array(30) { CharArray(16) { '#' } }
        fun fill(c0: Int, r0: Int, c1: Int, r1: Int) { for (r in r0..r1) for (c in c0..c1) g[r][c] = '.' }
        fun put(c: Int, r: Int, ch: Char) { g[r][c] = ch }
        fill(3, 4, 12, 13)      // the yard
        fill(6, 14, 8, 17)      // the passage up to it
        fill(2, 18, 13, 20)     // alley A
        fill(2, 21, 4, 25)      // the passage between the alleys
        fill(2, 26, 14, 28)     // alley B (you start at its east end)
        put(12, 27, 'S'); put(13, 27, 'C')
        for ((c, r) in listOf(4 to 12, 12 to 5, 4 to 18, 11 to 18, 6 to 26, 11 to 26, 2 to 22)) put(c, r, 'c')      // bins
        for ((c, r) in listOf(12 to 12, 3 to 5, 13 to 20)) put(c, r, 'd')                                           // crates
        for ((c, r) in listOf(4 to 6, 11 to 11, 9 to 19, 3 to 23, 8 to 27)) put(c, r, 'L')                          // lamps
        return g.map { String(it) }
    }

    /** The maintenance room under the street: you come in at the bottom left, the worker is by the pipes on the back wall, the ladder is across the room. */
    private fun maintenanceRoom(): LevelSpec {
        val g = Array(14) { CharArray(16) { '#' } }
        fun fill(c0: Int, r0: Int, c1: Int, r1: Int) { for (r in r0..r1) for (c in c0..c1) g[r][c] = '.' }
        fun put(c: Int, r: Int, ch: Char) { g[r][c] = ch }
        fill(2, 3, 13, 11)
        put(3, 10, 'S'); put(5, 3, 'C')
        for ((c, r) in listOf(13 to 10, 2 to 6, 9 to 10)) put(c, r, 'c')
        for ((c, r) in listOf(8 to 5, 11 to 8)) put(c, r, 'L')
        return LevelSpec(
            "The Maintenance Room", "", "", "", "Maintenance worker", "", "", 1,
            pal(0xFF0B1A16, 0xFF11302A, 0xFF030907, 0xFF1B5A4A, 0xFF5EE6A8, 0xFFFFC27A, 0xFFFF9F43),
            g.map { String(it) },
            cover = "bin", pixel = true, walk = true, theme = "sewer",
            worker = WorkerSpec(12.5f, 3.5f, "Another clerk? Thought you were all gone.|$SHOW_ID|Chen came through here. Left this for you.|$GIVE_ENVELOPE|Factory's above. Move, they're searching."),
            props = listOf(
                Prop("none", 12.5f, 4.5f, text = "A ladder, up to the street."),
                Prop("ladder", 12.5f, 2.5f),
            ),
        )
    }

    val all: List<LevelSpec> = listOf(
        // ------------------------------------------------------------------ 1
        // A walking story, not a puzzle: you walk a long, zig-zag alley behind a police cordon. Radios blare from open windows,
        // red and blue light flashes off the walls, torch beams sweep. At the corner the fog clears and a cop stands at the fork.
        LevelSpec(
            "The Backstreets", "Find your contact somewhere in the alleys. Show him you're a clerk.",
            "The radio calls you \"Mr. Tellem\", terrorist. The other clerks are being rounded up one by one. Someone out here is still on your side.",
            "Walk with the stick at the bottom left. A light push is slow and quiet. Listen at the windows as you pass.",
            "Newspaper vendor", "I thought you were dead.", "Someone inside the government helped you escape.", 0,
            pal(0xFF0B1230, 0xFF121D47, 0xFF03050A, 0xFF1B2B66, 0xFF29F0FF, 0xFFDCEBFF, 0xFFFF3B55),
            backstreetsMap(),
            cover = "bin", pixel = true, walk = true,
            scene = SceneSpec(3.5f, 9.5f, 22.5f, intArrayOf(0, 0, 15, 21), listOf(1.2f to 8.5f, -2.0f to 8.5f), contactLine = "Thank God I didn't have to shoot anyone.|$SHOW_ID|Shit... you're the last one. Sarah got caught.|$GIVE_ENVELOPE|Get underground. They'll take you from here.", speaker = "STRANGER"),
            flashers = listOf(15.5f to 45.5f, 0.2f to 35.5f, 15.8f to 24.5f, 0.2f to 8.0f, 15.8f to 7.0f),
            beams = listOf(
                CamSpec(0.9f, 35.5f, 0f, 22f, 7.0f, 0f, range = 9f, half = 0.16f),
                CamSpec(15.1f, 24.5f, 180f, 22f, 8.0f, 2f, range = 9f, half = 0.16f),
                CamSpec(1.2f, 8.5f, 0f, 20f, 6.0f, 1f, range = 10f, half = 0.16f),
                CamSpec(14.8f, 6.5f, 180f, 20f, 7.0f, 3f, range = 10f, half = 0.16f),
            ),
            props = listOf(
                // the radios, one per open window, each a different voice
                Prop("window", 6.5f, 43.5f, text = "[CHANNEL 9 NEWS] ...authorities are hunting a man identified only as Mr. Tellem, wanted on terrorism charges after last night's bombing. Citizens are urged not to approach."),
                Prop("window", 4.5f, 33.5f, text = "[POLICE DISPATCH] All units, suspect Tellem is on foot in the back alleys behind Fourth Street. Detain on sight. Repeat, on sight."),
                Prop("window", 9.5f, 33.5f, text = "[MORNING SHOW] ...and the weather is cold, with a chance of Mr. Tellem! Ha! Kidding, folks. Seriously, lock your doors."),
                Prop("window", 7.5f, 22.5f, text = "[OLD KITCHEN RADIO] I never met this Mr. Tellem. Seems an awful lot of fuss for one clerk."),
                Prop("window", 12.5f, 22.5f, text = "[PASTOR'S HOUR] ...and we pray for the city, and for the man they say did this. Lord, let the truth come out."),
                Prop("window", 6.5f, 4.5f, text = "[EMERGENCY BROADCAST] ...all citizens must remain indoors. The suspect Tellem is believed to be armed and dangerous."),
                Prop("poster", 9.5f, 43.5f),
                Prop("poster", 2.5f, 33.5f), Prop("poster", 10.5f, 22.5f), Prop("poster", 9.5f, 4.5f),
                Prop("news", 11.5f, 45.5f),
                Prop("stuff", 5.5f, 45.5f),
                Prop("car", 13.0f, 6.5f, w = 2), Prop("car", 2.0f, 5.5f, w = 2),
                Prop("stand", 10.5f, 8.5f, w = 3),
                Prop("streetsign", 0.5f, 6.5f),
            ),
        ),
        // ------------------------------------------------------------------ 2
        // Three parts: a walk (your contact leads you to a manhole and sends you down), the sewer maze played like Pac-Man, and
        // a maintenance room where a worker takes your letter and points you to a ladder.
        LevelSpec(
            "The Underground", "Follow the man in the trenchcoat under the streets. Chen left something down there for you.",
            "Every station is being searched. The only way across the city now is underneath it.",
            "Follow him to the manhole. In the tunnels: collect every key piece and reach the door. Don't let the police catch you.",
            "Maintenance worker", "You shouldn't have come here.", "The government has been erasing people from its records.", 1,
            pal(0xFF0B1B24, 0xFF12303A, 0xFF03080C, 0xFF1B4A5A, 0xFF5EE6A8, 0xFFD8FFE6, 0xFFFF9F43),
            undergroundMap(),
            cover = "bin", pixel = true, walk = true,
            lead = LeadSpec(
                listOf(4.5f to 27.5f, 3.5f to 23.5f, 3.5f to 19.5f, 7.5f to 19.5f, 7.5f to 15.5f, 7.5f to 11.5f, 7.5f to 9.5f),
                8.5f, 9.5f, "Be safe down there. God bless.",
            ),
            pac = PacMaze.sewer,
            pacTip = "Steer with the joystick. Collect every key fragment, then reach the door at the top. The police hunt the way the old arcade ghosts did.",
            room = maintenanceRoom(),
            flashers = listOf(15.8f to 27.5f, 0.2f to 19.5f),
            beams = listOf(
                CamSpec(15.1f, 19.5f, 180f, 22f, 8.0f, 2f, range = 9f, half = 0.16f),
                CamSpec(0.9f, 27.5f, 0f, 20f, 7.0f, 0f, range = 9f, half = 0.16f),
            ),
            props = listOf(
                Prop("window", 5.5f, 3.5f, text = "[TRANSIT RADIO] ...all underground services are suspended. Transit police are searching every platform for the man called Tellem."),
                Prop("window", 10.5f, 3.5f, text = "[TWO NEIGHBOURS, BEHIND THE CURTAIN] \"I cannot believe the state declared a state of emergency.\" \"And closed the borders to the other states. Quarantined the cities, like we are all sick.\" \"I have never witnessed anything like this. Never.\""),
                Prop("window", 4.5f, 17.5f, text = "[POLICE DISPATCH] Units four through seven to the waterworks. Seal every sewer access. Nobody goes down."),
                Prop("window", 11.5f, 17.5f, text = "[A COUPLE ON THE STAIRS] \"My cousin works at the records office. She says they have started rounding up government clerks.\" \"Rounding them up? Why?\" \"Nobody knows. And not one word about it on the news. Not one.\" \"Does that not bother you?\" \"Keep your voice down.\""),
                Prop("window", 7.5f, 25.5f, text = "[A MAN AT HIS WINDOW, ON THE PHONE] \"No, I cannot come to you, the roads are shut. Soldiers on the bridge... Yes, the whole city. Just stay in, love. Lock the door and stay in.\""),
                Prop("notice", 13.5f, 25.5f, text = "MISSING: Mara Voss. Your former colleague."),
                Prop("poster", 2.5f, 17.5f), Prop("poster", 9.5f, 3.5f), Prop("poster", 6.5f, 25.5f),
                Prop("sign", 7.5f, 3.5f, text = "WATERWORKS. Maintenance access only. No entry."),
                Prop("manhole", 8.5f, 9.5f, text = "A manhole in the middle of the yard. The lid is rusted shut, or so it looks."),
                Prop("stuff", 11.5f, 27.5f, text = "A bag, a coat, a thermos. Somebody ran from here."),
            ),
        ),
        // ------------------------------------------------------------------ 3
        LevelSpec(
            "The Industrial District", "Get out through the factory, and keep the envelopes safe.", "The sewers behind you are crawling with police. The factory above is the only way out.",
            "Swipe left or right to change belts, swipe up to jump. Collect 100 coins. Then hold to fly.",
            "Former detective", "They took the others.", "The detective investigated the disappearances and found that you were next.", 2,
            pal(0xFF15110E, 0xFF211A15, 0xFF0A0807, 0xFF4A3426, 0xFFFF9A52, 0xFFFFD2A0, 0xFFFF7A45),
            listOf(
                "#########",
                "#C......#",
                "#.c.c.c.#",
                "#.c.c.c.#",
                "#.......#",
                "#.c.c.c.#",
                "#.c.c.c.#",
                "#.......#",
                "#.c.c.c.#",
                "#.c.c.c.#",
                "#.......#",
                "#.c.c.c.#",
                "#.c.c.c.#",
                "#S..dd..#",
                "#########",
            ),
            guards = listOf(GuardSpec(listOf(1 to 4, 7 to 4), speed = 1.3f, pause = 1.5f)),
            cams = listOf(
                CamSpec(7.88f, 7.5f, 180f, 50f, 6.0f, 0f, range = 6.5f, half = 0.3f),
                CamSpec(1.12f, 10.5f, 0f, 50f, 6.0f, 3.0f, range = 6.5f, half = 0.3f),
            ),
            props = listOf(
                Prop("car", 5.0f, 13.5f, w = 2, text = "Police cruisers. A checkpoint on the road outside."),
                Prop("burned", 5.5f, 4.5f, text = "Burned documents. Someone was in a hurry."),
                Prop("photos", 0.5f, 8.5f, text = "A wall of photographs. Missing citizens, every one."),
                Prop("notice", 8.5f, 4.5f, text = "SEARCH NOTICE. They have your name."),
            ),
        ),
        // ------------------------------------------------------------------ 4
        LevelSpec(
            "The Administration", "Get to the mayor.", "The cop knows what the mayor did. With his own gun at his back, the doors of town hall open for you.",
            "Stick to move. GUN shoots the nearest officer. Hold GRENADE and drag to throw. Swipe with a second finger to roll. Hug the walls.",
            "City archivist", "Give me the letter.", "You were declared dead before the manhunt even began.", 3,
            pal(0xFF0E1218, 0xFF171D26, 0xFF07090D, 0xFF2E3B52, 0xFF9DB8FF, 0xFFE8F0FF, 0xFFFF5A4F),
            listOf(
                "#########",
                "#C......#",
                "#.......#",
                "#.......#",
                "#.......#",
                "#.c...c.#",
                "#.......#",
                "#.......#",
                "#.c...c.#",
                "#.......#",
                "#.......#",
                "#.......#",
                "#...L...#",
                "#S......#",
                "#########",
            ),
            guards = listOf(GuardSpec(listOf(1 to 7, 7 to 7), speed = 1.3f, pause = 1.2f)),
            lasers = listOf(
                LaserSpec(1f, 3.5f, 8f, 3.5f, 1.5f, 1.7f, 0.6f),
                LaserSpec(1f, 6.5f, 8f, 6.5f, 1.5f, 1.7f, 2.2f),
                LaserSpec(1f, 10.5f, 8f, 10.5f, 1.5f, 1.7f, 1.0f),
            ),
            props = listOf(
                Prop("doc", 8.5f, 2.5f, text = "Your employment record. TERMINATED."),
                Prop("files", 6.5f, 1.5f, text = "Files with whole sections blacked out."),
                Prop("terminal", 3.5f, 1.5f, text = "TERMINAL: your name. STATUS: DECEASED."),
                Prop("photos", 0.5f, 9.5f, text = "Officials meeting in secret. You were never meant to see this."),
            ),
        ),
        // ------------------------------------------------------------------ 5
        LevelSpec(
            // the prison (PrisonLevel/PrisonWorld/PrisonScene): the map below is the old walk, kept for the stealth tests only
            "The Rock", "Escape the island prison.", "The mayor had you locked away. Nobody outside knows you're still alive, and the envelopes are gone.",
            "Keep to the daily routine. Steal what you need when nobody's looking. Swipe up to see your things. There's always a way out.",
            "Priest", "I know what happened to your father.", "Your family was part of the original operation. Your discovery was expected.", 4,
            pal(0xFF17120C, 0xFF241B12, 0xFF0B0805, 0xFF4F3A24, 0xFFFFC25A, 0xFFFFC27A, 0xFFFF5A4F),
            listOf(
                "#########",
                "#C......#",
                "#.cc.cc.#",
                "#.cc.cc.#",
                "#.......#",
                "#.d...d.#",
                "#.......#",
                "#.cc.cc.#",
                "#.cc.cc.#",
                "#.......#",
                "#.d.L.d.#",
                "#.......#",
                "#.cc.cc.#",
                "#S......#",
                "#########",
            ),
            guards = listOf(
                GuardSpec(listOf(1 to 4, 7 to 4), speed = 1.5f, pause = 1.2f),
                GuardSpec(listOf(7 to 9, 1 to 9), speed = 1.5f, pause = 1.2f),
                GuardSpec(listOf(7 to 6, 1 to 6), speed = 1.5f, pause = 1.0f),
                GuardSpec(listOf(1 to 11, 7 to 11), speed = 1.5f, pause = 1.0f),
            ),
            armed = false,
            props = listOf(
                Prop("barricade", 2.5f, 5.5f, text = "Barricaded streets. Martial law."),
                Prop("barricade", 6.5f, 5.5f), Prop("barricade", 2.5f, 10.5f), Prop("barricade", 6.5f, 10.5f),
                Prop("stuff", 7.5f, 7.5f, text = "Abandoned belongings. Whole families left in a hurry."),
                Prop("docs", 3.5f, 1.5f, text = "Hidden documents, stacked beside the altar."),
                Prop("photo", 5.5f, 1.5f, text = "You, age eight, standing beside a man in a government coat."),
                Prop("poster", 8.5f, 11.5f), Prop("poster", 0.5f, 6.5f),
            ),
        ),
        // ------------------------------------------------------------------ 6
        LevelSpec(
            // the finale (FinaleWorld/FinaleScene): the map below is the old walk, kept for the stealth tests only
            "The Broadcast", "Get to the radio tower. Tell everyone.", "The city is burning. The tower is the last way left to reach them all.",
            "Climb and jump the spools. Then repeat the lights to open the uplink.",
            "Radio operator", "It's already too late.", "The evidence is being broadcast to the entire city. The conspiracy is exposed.", 5,
            pal(0xFF120A0F, 0xFF1E1018, 0xFF070306, 0xFF4A1F33, 0xFFFF4D6D, 0xFFFFF0D0, 0xFFFF2D55),
            listOf(
                "#########",
                "#k..#..C#",
                "#...#...#",
                "#...##D##",
                "#.......#",
                "#.cc.cc.#",
                "#.cc.cc.#",
                "#.......#",
                "#...L...#",
                "#.c...c.#",
                "#.......#",
                "#.cc.cc.#",
                "#.......#",
                "#S......#",
                "#########",
            ),
            guards = listOf(
                GuardSpec(listOf(1 to 4, 7 to 4), speed = 1.4f, pause = 1.2f),
                GuardSpec(listOf(7 to 7, 1 to 7), speed = 1.4f, pause = 1.2f),
            ),
            cams = listOf(CamSpec(7.88f, 10.5f, 180f, 50f, 6.0f, 1.0f, range = 6.5f, half = 0.3f)),
            lasers = listOf(LaserSpec(1f, 12.5f, 8f, 12.5f, 1.5f, 1.7f, 0.5f)),
            props = listOf(
                Prop("screen", 0.5f, 5.5f, text = "Every public screen shows your face."),
                Prop("screen", 8.5f, 6.5f, text = "EMERGENCY BROADCAST: SUSPECT CAPTURED. (You are not captured.)"),
                Prop("screen", 8.5f, 9.5f),
                Prop("notice", 0.5f, 9.5f, text = "WANTED: everyone you ever wrote to."),
                Prop("radio", 5.5f, 1.5f, text = "A radio, tuned to a dead channel. Waiting."),
                Prop("none", 6.5f, 2.5f, text = "Someone has been waiting for you."),
            ),
        ),
    )
}
