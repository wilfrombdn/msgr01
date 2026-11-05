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

    val all: List<LevelSpec> = listOf(
        // ------------------------------------------------------------------ 1
        LevelSpec(
            "The Backstreets", "Deliver letter I to the newspaper vendor.",
            "You are newly wanted: \"Mr. Tellem\", terrorist. Officers are working the alleys, and every window has a radio.",
            "Touch the screen and he walks toward your finger. Keep it close to him to sneak quietly; pull it far away and he sprints, loudly.",
            "Newspaper vendor", "I thought you were dead.", "Someone inside the government helped you escape.", 0,
            pal(0xFF0B1230, 0xFF121D47, 0xFF03050A, 0xFF1B2B66, 0xFF29F0FF, 0xFFDCEBFF, 0xFFFF3B55),
            listOf(
                "#########",
                "#...C...#",
                "#.#####.#",
                "#.#...#.#",
                "#.#.c.#.#",
                "#.#...#.#",
                "#.##.##.#",
                "#...L...#",
                "#..c.c..#",
                "##.###.##",
                "#...L...#",
                "#.c...c.#",
                "#...d...#",
                "#...S...#",
                "#########",
            ),
            guards = listOf(
                // the street officer: walks the bottom plaza
                GuardSpec(listOf(1 to 10, 7 to 10), speed = 1.3f, pause = 1.4f),
                // the bin inspector: checks behind each bin in the middle alley
                GuardSpec(listOf(2 to 8, 6 to 8), speed = 1.2f, faces = listOf(0f, 180f), pauses = listOf(2.6f, 2.6f)),
                // a patrol officer who circles the whole block: up the right alley, across the top, down the left alley, back across
                GuardSpec(listOf(7 to 6, 7 to 1, 1 to 1, 1 to 7), speed = 1.25f, pause = 1.2f),
            ),
            cover = "bin", pixel = true,
            props = listOf(
                Prop("window", 0.5f, 4.5f, text = "[CHANNEL 9 NEWS] ...authorities are hunting a man identified only as Mr. Tellem, wanted on terrorism charges after last night's bombing. Citizens are urged not to approach."),
                Prop("window", 8.5f, 3.5f, text = "[TALK RADIO 880] ...Mr. Tellem. Remember the name, folks. The man who bit the hand that fed him. Fifty thousand reward. Lines are open."),
                Prop("window", 0.5f, 11.5f, text = "[POLICE DISPATCH] All units, suspect Tellem is on foot in the alleys behind Fourth Street. Detain on sight. Repeat, on sight."),
                Prop("window", 8.5f, 11.5f, text = "[MORNING SHOW] ...and the weather is cold, with a chance of Mr. Tellem! Ha! Kidding, folks. Seriously, lock your doors."),
                Prop("window", 4.5f, 2.5f, text = "[OLD KITCHEN RADIO] I never met this Mr. Tellem. Seems an awful lot of fuss for one clerk."),
                Prop("poster", 8.5f, 7.5f, text = "WANTED: Mr. Tellem. Terrorism. That is your face, pasted up fresh."),
                Prop("poster", 0.5f, 7.5f),
                Prop("news", 6.5f, 13.5f, text = "HEADLINE: CLERK BEHIND CITY BOMBING. There was no bombing."),
                Prop("stuff", 1.5f, 13.5f, text = "Your old apartment is two streets over. Everything you owned, on the floor."),
            ),
        ),
        // ------------------------------------------------------------------ 2
        LevelSpec(
            "The Underground", "Deliver letter II to the maintenance worker.", "Transit security has your description. The stations are being searched.",
            "Grab the badge to open the locked door. Doors block sight, too.",
            "Maintenance worker", "You shouldn't have come here.", "The government has been erasing people from its records.", 1,
            pal(0xFF0C1714, 0xFF14251F, 0xFF060C0A, 0xFF2A4A3E, 0xFF5EE6A8, 0xFFD8FFE6, 0xFFFF9F43),
            listOf(
                "#########",
                "#C.#...k#",
                "#..#....#",
                "##D##.c.#",
                "#.......#",
                "#.c...c.#",
                "#.......#",
                "#.c.L.c.#",
                "#.......#",
                "#.c...c.#",
                "#.......#",
                "#.c...c.#",
                "#.......#",
                "#S......#",
                "#########",
            ),
            guards = listOf(
                GuardSpec(listOf(1 to 6, 7 to 6), speed = 1.4f, pause = 1.2f),
                GuardSpec(listOf(7 to 10, 1 to 10), speed = 1.4f, pause = 1.2f),
            ),
            props = listOf(
                Prop("screen", 0.5f, 7.5f, text = "Every turnstile shows your photograph."),
                Prop("screen", 8.5f, 5.5f),
                Prop("notice", 0.5f, 11.5f, text = "MISSING: Mara Voss. Your former colleague."),
                Prop("sign", 1.5f, 3.5f, text = "MAINTENANCE. Authorized personnel only."),
                Prop("none", 7.5f, 1.5f, text = "A discarded security badge. Someone left in a hurry."),
            ),
        ),
        // ------------------------------------------------------------------ 3
        LevelSpec(
            "The Industrial District", "Deliver letter III to the former detective.", "The police have set up checkpoints. The detective is hiding in an abandoned factory.",
            "Floodlights sweep back and forth. Wait for them to turn away. Machines block their view.",
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
            "The Administration", "Deliver letter IV to the archivist inside City Hall.", "The government knows you are trying to expose them. Security is everywhere.",
            "Lasers blink on and off. Cross while they are dark. Touching one raises the alarm.",
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
            "The Old Quarter", "Deliver letter V to the priest.", "Martial law has been declared. Your face is on every channel in the city.",
            "The priest will not fight for you. Do not get caught: guards hear running, and a chase here ends badly.",
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
            "The Broadcast", "Deliver the final letter to the radio operator.", "The whole city is looking for you. Searchlights, patrols and emergency broadcasts everywhere.",
            "Everything at once. Watch, wait, then move. Almost there.",
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
