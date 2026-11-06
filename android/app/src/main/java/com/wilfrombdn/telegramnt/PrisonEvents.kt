package com.wilfrombdn.telegramnt

/**
 * The big things that happen in the prison: one every second day (days 2, 4 ... 20, then round again).
 * What an event leaves behind (who is hurt, who is gone, the high alert) is worked out from the day alone ([PrisonEvents.marks]),
 * so a replayed day, a night in the cell or a jump in the clock can never leave it half done. Only the live scene itself
 * runs as it happens (PrisonWorld.eventTick).
 *
 * Inmates are named by their persona name; guards by "GUARD#<id>".
 */
internal enum class PrisonEvent(
    val title: String,
    val period: PrisonWorld.Period,
    val delayMin: Float,                  // game minutes into that part of the day
    val headline: String,
    val cast: List<String>,
    /** What the men who were in it say about it afterwards (by name). */
    val involved: Map<String, List<String>>,
    /** What everybody else says about it that day and the next. */
    val talk: List<String>,
) {
    BRAWL("THE BRAWL", PrisonWorld.Period.LUNCH, 10f, "A BRAWL IN THE DINING HALL!", listOf("TANK", "BIG LOU"),
        mapOf("TANK" to listOf("HE LOOKED AT MY TRAY. NOBODY LOOKS AT MY TRAY.", "TWO DAYS AND I STILL CAN'T CHEW."),
              "BIG LOU" to listOf("I WAS WINNING TILL THE SCREWS SHOWED UP.", "TANK HITS LIKE A WET SOCK. THE GUARDS DON'T.")),
        listOf("YOU SEE TANK GO OVER THAT TABLE? BEAUTIFUL.", "THREE GUARDS ON TWO MEN. REAL BRAVE.", "MY MONEY WAS ON LOU. STILL IS.", "BLOOD IN THE MASHED POTATOES. NOT THE WORST I'VE HAD.")),
    DRUGS("THE ARREST", PrisonWorld.Period.FREE, 18f, "A GUARD'S BEEN CAUGHT WITH DRUGS! THEY'RE ARRESTING HIM IN THE YARD!", listOf("GUARD#105"),
        emptyMap(),
        listOf("THEY CUFFED A SCREW. I'LL DIE HAPPY NOW.", "HE SOLD HALF THE BLOCK THEIR PILLS. NOW WHAT?", "DID YOU HEAR THE YARD? LOUDEST I EVER HEARD IT.", "ONE LESS BOOT ON OUR NECK. FOR NOW.", "THEY'LL SAY HE WAS THE ONLY ONE. HE WASN'T.")),
    ESCAPE("THE ESCAPE", PrisonWorld.Period.BREAKFAST, 2f, "INTERCOM: INMATE MISSING. NUMBERS, B BLOCK. THE PRISON IS ON HIGH ALERT.", listOf("NUMBERS"),
        mapOf("NUMBERS" to listOf("TWO NIGHTS ON THE ROCKS. THE WATER'S COLDER THAN YOU THINK.", "SEVEN MINUTES BETWEEN ROUNDS. I WAS ONE SHORT.", "THEY FOUND ME UNDER THE DOCK. THEY WEREN'T GENTLE.")),
        listOf("NUMBERS GOT OUT! COUNTED THE ROUNDS AND WALKED.", "HIGH ALERT. EVERY SCREW'S WIDE AWAKE. KEEP YOUR HEAD DOWN.", "NO SMOKE BREAKS FOR THE GUARDS TILL THEY FIND HIM.", "THEY WON'T TAKE THEIR EYES OFF US FOR DAYS.")),
    SHOWERS("THE BACK STALL", PrisonWorld.Period.SHOWERS, 6f, "TWO MEN CAUGHT TOGETHER IN THE BACK STALL!", listOf("FELIX", "DUTCH"),
        mapOf("FELIX" to listOf("DON'T. JUST DON'T.", "THEY BEAT US IN FRONT OF THE WHOLE ROOM. FOR WHAT?"),
              "DUTCH" to listOf("WHAT? A MAN CAN'T BE LONELY?", "MY RIBS. THAT'S ALL I'LL SAY.")),
        listOf("FELIX AND DUTCH? IN THE BACK STALL? HUH.", "THEY BEAT THEM BLACK AND BLUE. IN FRONT OF EVERYONE.", "WHAT THEY DO IS THEIR BUSINESS. THE SCREWS DIDN'T THINK SO.", "NOBODY'S USING THE BACK STALL NOW.")),
    STABBING("THE STABBING", PrisonWorld.Period.WORK_AM, 40f, "A MAN'S BEEN STABBED IN THE LAUNDRY!", listOf("RAY", "MOE"),
        mapOf("RAY" to listOf("...", "..."),
              "MOE" to listOf("SIX STITCHES. OVER A BAR OF SOAP.", "RAY. I DIDN'T EVEN SEE HIM COMING.")),
        listOf("RAY STUCK MOE WITH A SHARPENED SPOON. A SPOON!", "THEY DRAGGED RAY TO D BLOCK. HE DIDN'T SAY A WORD.", "THERE'S STILL BLOOD ON THE FOLDING TABLE.", "MOE OWED SOMEBODY. EVERYBODY OWES SOMEBODY.")),
    FIRE("THE FIRE", PrisonWorld.Period.DINNER, 8f, "FIRE IN THE KITCHEN!", listOf("COOKIE"),
        mapOf("COOKIE" to listOf("GREASE FIRE. MY EYEBROWS AREN'T COMING BACK.", "BURNED MY ARM. STILL MAKING YOUR GRAVY.")),
        listOf("THE WHOLE KITCHEN WENT UP. DINNER WAS SMOKE.", "COOKIE NEARLY COOKED HIMSELF.", "THE GUARDS RAN IN WITH ONE LITTLE EXTINGUISHER. GENIUSES.", "SMELL THAT? STILL SMOKE IN THE WALLS.")),
    SHAKEDOWN("THE SHAKEDOWN", PrisonWorld.Period.FREE, 12f, "SHAKEDOWN! THE GUARDS ARE TOSSING THE CELLS!", listOf("VIC"),
        mapOf("VIC" to listOf("TWO DAYS IN THE HOLE. FOR A BIT OF METAL.", "THEY FOUND MY SHANK. THEY DIDN'T FIND EVERYTHING.")),
        listOf("SHAKEDOWN. THEY TORE VIC'S CELL APART.", "THEY FOUND A SHANK UNDER VIC'S BUNK. HE'S IN THE HOLE.", "KEEP YOUR THINGS ON YOU. THEY CHECK THE CELLS NOW.", "THEY TOOK MY PHOTOS. MY PHOTOS!")),
    BLACKOUT("THE BLACKOUT", PrisonWorld.Period.DINNER, 15f, "THE POWER'S OUT! THE GUARDS CAN'T SEE A THING!", emptyList(),
        emptyMap(),
        listOf("THE LIGHTS WENT OUT AND THE WHOLE HALL HOWLED.", "IN THE DARK THE GUARDS WERE BLIND AS BATS.", "SOMEBODY STOLE MY PUDDING IN THE BLACKOUT.", "GENERATOR'S OLD. IT'LL GO AGAIN. BE READY WHEN IT DOES.")),
    FOODFIGHT("THE FOOD FIGHT", PrisonWorld.Period.BREAKFAST, 14f, "FOOD FIGHT!", listOf("MAGS", "KID", "DUTCH"),
        mapOf("MAGS" to listOf("WORTH IT. TOTALLY WORTH IT.", "I STARTED IT. I'D DO IT AGAIN."),
              "KID" to listOf("I GOT OATMEAL IN MY EAR. STILL.", "BEST MORNING I'VE HAD IN HERE."),
              "DUTCH" to listOf("I JUST THREW ONE EGG. ONE.", "THEY ONLY BEAT MAGS. LUCKY US.")),
        listOf("EGGS EVERYWHERE. A GUARD GOT ONE RIGHT IN THE EAR.", "MAGS STARTED IT. MAGS PAID FOR IT.", "THEY MADE US SCRAPE OATMEAL OFF THE CEILING.", "BEST BREAKFAST IN YEARS.")),
    JUMPED("THE JUMPING", PrisonWorld.Period.FREE, 20f, "THEY'RE JUMPING A GUARD IN THE YARD!", listOf("BISHOP", "BIG LOU", "GUARD#101"),
        mapOf("BISHOP" to listOf("HE HAD IT COMING. ASK ANYONE.", "WORTH EVERY BRUISE."),
              "BIG LOU" to listOf("I JUST HELD HIM. BISHOP DID THE REST.", "TWO BEATINGS THIS MONTH. I'M COLLECTING."),
              "GUARD#101" to listOf("ONE MORE WORD, TELLEM. ONE.", "I KNOW WHO WATCHED. I KNOW WHO CHEERED.")),
        listOf("BISHOP AND LOU PUT A SCREW ON THE GROUND!", "THE YARD WENT CRAZY. THEN THE BATONS CAME.", "THAT GUARD'S GOT A BANDAGE ON HIS HEAD NOW. HA.", "EVERY GUARD'S ANGRY TODAY. STAY OUT OF THEIR WAY.")),
}

/** A lasting effect of an event on someone: [kind] "gone", "bruised", "bandaged" or "burned", from day+[from] to day+[to] after it. */
internal class Mark(val who: String, val kind: String, val from: Int, val to: Int, val beforeToo: Boolean = false)

internal object PrisonEvents {
    /** The event on this day (every second day from day 2), or null. */
    fun on(day: Int): PrisonEvent? = if (day >= 2 && day % 2 == 0) PrisonEvent.values()[(day / 2 - 1) % PrisonEvent.values().size] else null

    fun marksOf(e: PrisonEvent): List<Mark> = when (e) {
        PrisonEvent.BRAWL -> listOf(Mark("TANK", "bruised", 0, 2), Mark("BIG LOU", "bruised", 0, 2))
        PrisonEvent.DRUGS -> listOf(Mark("GUARD#105", "gone", 0, 9999))
        PrisonEvent.ESCAPE -> listOf(Mark("NUMBERS", "gone", 0, 1, beforeToo = true), Mark("NUMBERS", "bruised", 2, 3))
        PrisonEvent.SHOWERS -> listOf(Mark("FELIX", "bruised", 0, 2), Mark("DUTCH", "bruised", 0, 2))
        PrisonEvent.STABBING -> listOf(Mark("RAY", "gone", 0, 1), Mark("RAY", "bruised", 2, 3), Mark("MOE", "gone", 0, 0), Mark("MOE", "bandaged", 1, 2))
        PrisonEvent.FIRE -> listOf(Mark("COOKIE", "burned", 0, 2))
        PrisonEvent.SHAKEDOWN -> listOf(Mark("VIC", "gone", 0, 1), Mark("VIC", "bruised", 2, 3))
        PrisonEvent.BLACKOUT -> emptyList()
        PrisonEvent.FOODFIGHT -> listOf(Mark("MAGS", "bruised", 0, 2))
        PrisonEvent.JUMPED -> listOf(Mark("BISHOP", "bruised", 0, 2), Mark("BIG LOU", "bruised", 0, 2), Mark("GUARD#101", "bandaged", 0, 2))
    }

    /** Everyone's marks on [day] ([doneToday]: today's event has already happened). who -> kinds. */
    fun marks(day: Int, doneToday: Boolean): Map<String, Set<String>> {
        val out = HashMap<String, HashSet<String>>()
        var d = 2
        while (d <= day) {
            val e = on(d)
            if (e != null) for (m in marksOf(e)) {
                val off = day - d
                if (off !in m.from..m.to) continue
                if (off == 0 && !doneToday && !m.beforeToo) continue
                out.getOrPut(m.who) { HashSet() } += m.kind
            }
            d += 2
        }
        return out
    }

    /** The prison is on high alert from the morning the escape is found through the next two days (the guards see further and never look away). */
    fun alertOn(day: Int): Boolean = on(day) == PrisonEvent.ESCAPE || on(day - 1) == PrisonEvent.ESCAPE || on(day - 2) == PrisonEvent.ESCAPE

    /** The event the men are talking about today: today's (once it has happened) or yesterday's. */
    fun talkedAbout(day: Int, doneToday: Boolean): PrisonEvent? = on(day)?.takeIf { doneToday } ?: on(day - 1)

    /** A steady number from a few ints (the same day always gives the same lines, without touching the world's dice). */
    fun mix(vararg v: Int): Int { var h = 0x2F6B; for (x in v) { h = h * 31 + x; h = h xor (h ushr 13); h *= 0x5BD1E995 } ; return h and 0x7FFFFFFF }

    // ---------- what the men talk about (a few each day, different every day) ----------
    val TIPS = listOf(
        "THE CANTEEN LINE'S SHORTEST RIGHT AFTER THE BELL.",
        "NEVER SIT IN SOMEONE'S SEAT. EVER.",
        "DON'T BORROW. EVERYTHING IN HERE HAS INTEREST.",
        "WALK LIKE YOU KNOW WHERE YOU'RE GOING. NOBODY ASKS.",
        "IF YOU'RE SKIPPING, DO IT WHEN THE SCREW'S RADIO GOES.",
        "LAUNDRY'S THE BEST JOB. WARM, AND NOBODY WATCHES THE CARTS.",
        "CHOW'S NOT REQUIRED. BUT IT'S THE ONLY TIME WE ALL TALK.",
        "KEEP YOUR THINGS ON YOU. CELLS GET TOSSED.",
        "THE LIBRARY'S QUIET. GOOD PLACE TO THINK. OR HIDE.",
        "GET CAUGHT SKIPPING AND THEY DRAG YOU BACK. WITH A BATON.",
        "DON'T RUN IN THE CORRIDORS. RUNNING MEANS GUILTY.",
        "SHOWERS ARE TEN MINUTES. DON'T BE THE LAST ONE OUT.",
        "IF A GUARD SAYS JUMP, YOU'RE ALREADY IN THE AIR.",
        "WATER'S FREEZING OUT THERE. NOBODY SWIMS IT ALONE.",
    )
    val GUARD_TIPS = listOf(
        "A SCREW WITH HIS BACK TURNED IS A SCREW WHO CAN'T SEE YOU.",
        "THE ONE ON THE LAUNDRY DOOR WATCHES THE DOOR, NOT THE ROOM.",
        "WHEN THEY STEP OUT FOR A SMOKE YOU'VE GOT A MINUTE. MAYBE.",
        "THEY ONLY SEE WHAT'S IN FRONT OF THEM. STAY BEHIND.",
        "WALLS. KEEP A WALL BETWEEN YOU AND THE GUARD.",
        "A GUARD ON THE RADIO HEARS NOTHING ELSE.",
        "THE DOOR GUARDS NEVER LEAVE THEIR DOORS. GO ROUND THEM.",
        "IF HE'S YAWNING, MOVE. IF HE'S STARING, DON'T.",
        "THE EYE OVER THEIR HEADS? WHEN IT GOES DOTS, YOU GO.",
        "TWO GUARDS WALK THE TABLES AT CHOW. COUNT THEIR LAPS.",
        "THE NIGHT TORCHES ONLY REACH SO FAR.",
        "NEVER LET THE SAME SCREW SEE YOU TWICE IN ONE DAY.",
    )
    val FACTS = listOf(
        "THIS ROCK WAS A FORT BEFORE IT WAS A PRISON.",
        "THEY CALL THE MAIN HALL BROADWAY. NO SHOWS, THOUGH.",
        "D BLOCK'S THE HOLE. NO LIGHT, NO TALK, NO NOTHING.",
        "THE WATER'S FIFTY DEGREES AND THE CURRENT PULLS OUT.",
        "ON A QUIET NIGHT YOU CAN HEAR THE CITY PARTY. CRUEL.",
        "THE CELLS ARE FIVE BY NINE. I'VE MEASURED. TWICE.",
        "THEY SERVE THE BEST FOOD IN THE SYSTEM. KEEPS US QUIET.",
        "HOT SHOWERS, SO NOBODY GETS USED TO COLD WATER.",
        "THE TOWER CAN SEE THE WHOLE YARD. NOT THE CORNERS.",
        "THERE'S A TUNNEL UNDER HERE SOMEWHERE. EVERYBODY SAYS.",
        "THE PIPES BEHIND THE CELLS RUN THE WHOLE BLOCK.",
        "NOBODY'S MADE IT OFF THIS ROCK. OFFICIALLY.",
    )
    val JOKES = listOf(
        "WHAT'S THE BEST THING ABOUT PRISON? THE VIEW. HA.",
        "I ASKED FOR A ROOM WITH A VIEW. THEY GAVE ME BARS.",
        "MY LAWYER SAID DON'T WORRY. HE'S NOT IN HERE, IS HE?",
        "I'D TELL YOU A JOKE ABOUT ESCAPING. BUT IT WON'T GET OUT.",
        "THE FOOD'S SO BAD THE RATS BRING THEIR OWN.",
        "I'M NOT A CRIMINAL. I'M A VERY UNLUCKY ACCOUNTANT.",
        "WHY DON'T SCREWS PLAY HIDE AND SEEK? THEY'D LOSE US.",
        "MY CELLMATE SNORES IN MORSE CODE. IT SAYS HELP.",
        "SAY WHAT YOU LIKE, THE RENT'S CHEAP.",
        "I CAME IN FOR A PARKING TICKET. STAYED FOR THE SOUP.",
        "I'VE BEEN HERE SO LONG THE GUARDS ASK ME FOR DIRECTIONS.",
        "WHAT DO YOU CALL A SCREW WITH NO EARS? ANYTHING YOU LIKE.",
    )
    val BRUSH_OFF = listOf(
        "NOT NOW. TALK AT CHOW.", "GUARD'S RIGHT THERE. LATER.", "SAVE IT FOR THE YARD.", "KEEP YOUR VOICE DOWN, NEW GUY.",
        "WORKING. CATCH ME AT DINNER.", "YOU WANT US BOTH IN THE HOLE? LATER.",
    )
    val HURT = mapOf(
        "bruised" to listOf("DON'T ASK ABOUT THE FACE.", "YOU SHOULD SEE THE OTHER GUY. HE'S GOT A BATON.", "EVERYTHING HURTS. EVEN MY HAIR."),
        "bandaged" to listOf("STITCHES. DON'T MAKE ME LAUGH.", "THE INFIRMARY'S NICE. SOFT PILLOWS."),
        "burned" to listOf("SMELLS LIKE BURNT HAIR? THAT'S ME."),
    )
}
