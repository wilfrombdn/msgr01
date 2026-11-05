package tellem

import com.wilfrombdn.telegramnt.*

/** Fake chats for screenshots (TELLEM_DEMO=1). Never talks to the network. */
object Demo {
    fun fill(core: Core) {
        val now = System.currentTimeMillis()
        val min = 60_000L
        core.me = Me("you_demo", "Demo User", "d", Crypto.newKeyset(Crypto.ENC_TEMPLATE), Crypto.newKeyset(Crypto.SIG_TEMPLATE), Crypto.randomHex(), ByteArray(32))
        fun friend(user: String, name: String, unread: Int, vararg lines: Pair<Boolean, String>): Contact {
            val c = Contact(user, name, Contact.FRIENDS, Crypto.randomHex(), Crypto.randomHex(), "", "", unread = unread)
            lines.forEachIndexed { i, (mine, text) ->
                c.msgs.add(Msg("m$user$i", mine, text, now - (lines.size - i) * 3 * min, if (mine) Msg.SENT else Msg.RECEIVED))
            }
            return c
        }
        val a = friend("maya_k", "Maya Kowalski", 2,
            false to "hey! are we still on for tonight?", true to "yes, 8pm works", false to "perfect, I'll book the table",
            false to "also, did you see the new build?")
        a.theirOnlineUntil = now + 10 * min
        a.theirReadUpto = now
        a.msgs[2].reactions["maya_k"] = "👍"
        a.msgs[1].reactions["you_demo"] = "🔥"; a.msgs[1].reactions["maya_k"] = "🔥"
        val b = friend("tom_r", "Tom Reyes", 0, true to "sent you the file", false to "got it, thanks!")
        val c = friend("priya_s", "Priya Shah", 0, false to "call me when you can")
        core.contacts.addAll(listOf(a, b, c))
        val g = Group("g1", "Design Team", Crypto.randomHex(), true, owner = "maya_k")
        g.members.addAll(listOf("you_demo", "maya_k", "tom_r", "priya_s"))
        g.msgs.add(Msg("g1", false, "Mockups are up in the folder", now - 9 * min, Msg.RECEIVED).also { it.sender = "tom_r" })
        g.msgs.add(Msg("g2", true, "Nice, looking now", now - 6 * min, Msg.SENT))
        core.groups.add(g)
    }
}
