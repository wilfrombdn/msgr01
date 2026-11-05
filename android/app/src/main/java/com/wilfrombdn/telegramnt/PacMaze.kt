package com.wilfrombdn.telegramnt

/**
 * The sewer maze. # wall, a space is void, . path, M the police chamber (with the manhole in its ceiling), - the chamber door,
 * S your start, D the exit door (it opens when every key fragment is collected).
 */
internal object PacMaze {
    val sewer = PacSpec(
        listOf(
            "##########D##########",
            "#...................#",
            "#.###.###.#.###.###.#",
            "#.###.###.#.###.###.#",
            "#...................#",
            "#.###.#.#####.#.###.#",
            "#.....#...#...#.....#",
            "#####.###.#.###.#####",
            "    #.###...###.#    ",
            "    #.####-####.#    ",
            "......#MMMMMMM#......",
            "    #.#MMMMMMM#.#    ",
            "    #.#########.#    ",
            "    #.###...###.#    ",
            "#####.###.#.###.#####",
            "#.....#...#...#.....#",
            "#.###.#.#####.#.###.#",
            "#.........S.........#",
            "#.###.###.#.###.###.#",
            "#.###.###.#.###.###.#",
            "#...................#",
            "#####################",
        ),
        tunnelRow = 10,
    )
}
