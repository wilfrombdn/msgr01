package com.wilfrombdn.telegramnt

/** The conveyor run of level 3: three belts, each shifted sideways from the last, then a platform with the jetpack. */
internal object BeltLevel {
    val warehouse = BeltSpec(
        segs = listOf(
            BeltSeg(0f, 44f, 7.0f, 2.6f, 5.2f),
            BeltSeg(47f, 92f, 4.6f, 2.6f, 6.2f),
            BeltSeg(95f, 140f, 9.0f, 2.6f, 7.2f),
        ),
        obstacles = listOf(
            // belt one: boxes and a tool
            BeltObs(BeltWorld.BOX, 8f, 5.8f, 1.6f, 1.6f),
            BeltObs(BeltWorld.TOOL, 14f, 8.6f, 1.0f, 2.4f),
            BeltObs(BeltWorld.BOX, 20f, 8.4f, 1.6f, 1.6f),
            BeltObs(BeltWorld.CABLE, 26f, 7.0f, 1.0f, 1.0f, amp = 1.3f, period = 3.2f),
            BeltObs(BeltWorld.BOX, 32f, 5.6f, 1.6f, 1.6f),
            BeltObs(BeltWorld.BOX, 35f, 8.6f, 1.6f, 1.6f),
            BeltObs(BeltWorld.TOOL, 40f, 7.0f, 2.2f, 0.8f),
            // belt two: cables and a press
            BeltObs(BeltWorld.BOX, 52f, 3.6f, 1.6f, 1.6f),
            BeltObs(BeltWorld.CABLE, 58f, 5.0f, 1.0f, 1.0f, amp = 1.5f, period = 2.8f),
            BeltObs(BeltWorld.MACHINE, 65f, 3.2f, 2.4f, 1.8f, period = 2.6f),
            BeltObs(BeltWorld.TOOL, 71f, 6.2f, 1.0f, 2.0f),
            BeltObs(BeltWorld.CABLE, 77f, 3.5f, 1.0f, 1.0f, amp = 1.2f, period = 3.0f),
            BeltObs(BeltWorld.BOX, 83f, 5.8f, 1.6f, 1.6f),
            BeltObs(BeltWorld.BOX, 87f, 3.4f, 1.6f, 1.6f),
            // belt three: faster, with presses and swinging cables
            BeltObs(BeltWorld.MACHINE, 101f, 10.4f, 2.4f, 1.8f, period = 2.4f),
            BeltObs(BeltWorld.BOX, 107f, 8.0f, 1.6f, 1.6f),
            BeltObs(BeltWorld.CABLE, 113f, 9.0f, 1.0f, 1.0f, amp = 1.6f, period = 2.6f),
            BeltObs(BeltWorld.TOOL, 119f, 7.4f, 1.0f, 2.2f),
            BeltObs(BeltWorld.TOOL, 119f, 10.8f, 1.0f, 2.2f),
            BeltObs(BeltWorld.MACHINE, 125f, 7.6f, 2.4f, 1.8f, period = 2.4f, phase = 1.2f),
            BeltObs(BeltWorld.CABLE, 131f, 10.0f, 1.0f, 1.0f, amp = 1.4f, period = 2.4f),
            BeltObs(BeltWorld.BOX, 136f, 8.8f, 1.6f, 1.6f),
        ),
        platformS = 140f, endS = 152f, pickupX = 7f,
    )
}
