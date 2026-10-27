package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.os.Looper
import java.io.File
import java.io.FileOutputStream

// Renders the main screens to app/build/shots (the emulator will not run on this laptop).
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    private fun fakeCore(signedIn: Boolean): Core {
        val core = Core("https://chat.example")
        if (!signedIn) return core
        core.me = Me("wil_p", "Wil", "dev", Crypto.newKeyset(Crypto.ENC_TEMPLATE), Crypto.newKeyset(Crypto.SIG_TEMPLATE), Crypto.randomHex(), ByteArray(32))
        fun friend(user: String, name: String, status: Int) = Contact(
            user, name, status, Crypto.randomHex(), Crypto.addressOf(Crypto.randomHex()),
            java.util.Base64.getEncoder().encodeToString(Crypto.publicBytes(Crypto.newKeyset(Crypto.ENC_TEMPLATE))),
            java.util.Base64.getEncoder().encodeToString(Crypto.publicBytes(Crypto.newKeyset(Crypto.SIG_TEMPLATE))),
        )
        val now = System.currentTimeMillis()
        val sam = friend("sam_r", "Sam Rivera", Contact.FRIENDS)
        sam.msgs += Msg("1", false, "You are now friends. Check the safety code to be sure it's really them.", now - 600_000, Msg.NOTE)
        sam.msgs += Msg("2", false, "hey! is this the new app?", now - 500_000, Msg.RECEIVED)
        sam.msgs += Msg("3", true, "yep, everything's end-to-end encrypted now", now - 400_000, Msg.SENT)
        sam.msgs += Msg("4", false, "nice, way better than the old one", now - 300_000, Msg.RECEIVED)
        sam.msgs += Msg("5", true, "see you at 6", now - 60_000, Msg.SENDING)
        val jo = friend("jordan_k", "Jordan", Contact.FRIENDS)
        jo.msgs += Msg("6", false, "Did you get the truck fixed?", now - 3_600_000, Msg.RECEIVED)
        jo.unread = 2
        val req = friend("alex_m", "Alex M", Contact.ASKING)
        req.msgs += Msg("8", false, "hey, Jordan gave me your username", now - 1_000_000, Msg.RECEIVED)
        val asked = friend("taylor_b", "@taylor_b", Contact.ASKED)
        asked.msgs += Msg("7", false, "Friend request sent. You can write now; messages go out when @taylor_b accepts.", now - 7_200_000, Msg.NOTE)
        sam.msgs += Msg("p1", false, "", now - 50_000, Msg.RECEIVED).also {
            it.file = org.json.JSONObject().put("id", "0".repeat(32)).put("k", "0".repeat(64)).put("n", "truck.jpg").put("m", "image/jpeg").put("s", 250_000)
                .put("th", java.util.Base64.getEncoder().encodeToString(testJpeg(0xFF8D6E63.toInt(), 0xFF26A69A.toInt())))
        }
        sam.msgs += Msg("p2", true, "here's the invoice", now - 40_000, Msg.SENT).also {
            it.file = org.json.JSONObject().put("id", "1".repeat(32)).put("k", "0".repeat(64)).put("n", "invoice-october.pdf").put("m", "application/pdf").put("s", 182_000).put("local", "x")
        }
        sam.msgs.first { it.id == "3" }.reactions["sam_r"] = "👍"
        sam.msgs += Msg("r1", false, "same, way faster too", now - 30_000, Msg.RECEIVED).also {
            it.reply = org.json.JSONObject().put("id", "3").put("u", "wil_p").put("p", "yep, everything's end-to-end encrypted now")
        }   // 👍 on a 1:1 message
        sam.avatar = testJpeg(0xFFE08A3C.toInt(), 0xFF3C6EE0.toInt())
        core.me!!.avatar = testJpeg(0xFF2E7D32.toInt(), 0xFFB2DFDB.toInt())
        core.contacts += listOf(sam, jo, req, asked)
        val grp = Group("g1", "Road Crew", "0".repeat(64), false, owner = "wil_p")
        grp.leaders.add("wil_p")
        grp.members += listOf("wil_p", "sam_r", "jordan_k")
        grp.msgs += Msg("gm1", false, "who's on the 6am?", now - 500_000, Msg.RECEIVED).also { it.sender = "sam_r" }
        grp.msgs += Msg("gm2", true, "i'll take it", now - 400_000, Msg.SENT)
        grp.msgs += Msg("gm3", false, "thanks, see you there", now - 300_000, Msg.RECEIVED).also { it.sender = "jordan_k" }
        grp.msgs.first { it.id == "gm2" }.reactions.putAll(mapOf("sam_r" to "🔥", "jordan_k" to "❤️"))   // 🔥 ❤️
        core.groups += grp
        return core
    }

    private fun testBitmap(a: Int, b: Int, w: Int = 120, h: Int = 120): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val p = android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), a, b, android.graphics.Shader.TileMode.CLAMP) }
        Canvas(bmp).drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        Canvas(bmp).drawCircle(w / 2f, h / 2.4f, w / 5f, android.graphics.Paint().apply { color = android.graphics.Color.WHITE })
        return bmp
    }

    private fun testJpeg(a: Int, b: Int): ByteArray =
        java.io.ByteArrayOutputStream().also { testBitmap(a, b).compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()

    private fun shoot(a: MainActivity, name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        val v = a.window.decorView
        val bmp = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
        v.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2340, android.view.View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1080, 2340)
        v.draw(Canvas(bmp))
        val dir = File("build/shots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun call(a: MainActivity, method: String, vararg args: Any) {
        // optional (defaulted) trailing parameters are passed as null
        val m = MainActivity::class.java.declaredMethods.filter { it.name == method && it.parameterCount >= args.size }.minBy { it.parameterCount }
        m.isAccessible = true
        m.invoke(a, *args, *arrayOfNulls<Any>(m.parameterCount - args.size))
    }

    @Test fun screens() {
        Messenger.initForPreview(fakeCore(signedIn = true))
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shoot(a, "1-chats")
        call(a, "showChat", "sam_r"); shoot(a, "2-chat")
        call(a, "showChat", "alex_m"); shoot(a, "3-request")
        call(a, "showNewChat"); shoot(a, "4-add-friend")
        call(a, "showSettings"); shoot(a, "5-settings")
        call(a, "showProfile"); shoot(a, "5b-profile")
        Messenger.look.out = Look.BUBBLES[3].second; Messenger.look.square = true; Messenger.look.msg = 18f
        call(a, "showAppearance"); shoot(a, "7-appearance")
        call(a, "showChat", "sam_r"); shoot(a, "8-chat-custom")
        Messenger.look.load(org.json.JSONObject()); Messenger.look.out = Look.BUBBLES[0].second; Messenger.look.inn = Look.BUBBLES[1].second
        call(a, "showNewGroup"); shoot(a, "10-newgroup")
        call(a, "showGroup", "g1"); shoot(a, "11-group")
        call(a, "showGroupSettings", Messenger.core.group("g1")!!); shoot(a, "12-group-settings")
        Messenger.look.load(org.json.JSONObject()); Messenger.look.out = Look.BUBBLES[0].second; Messenger.look.inn = Look.BUBBLES[1].second
        call(a, "showChats")
        a.javaClass.getDeclaredField("drawer").apply { isAccessible = true }.get(a)?.let { dl ->
            (dl as androidx.drawerlayout.widget.DrawerLayout).openDrawer(androidx.core.view.GravityCompat.START)
        }
        shoot(a, "9-drawer")
        Messenger.look.load(org.json.JSONObject()); Messenger.look.out = Look.BUBBLES[0].second; Messenger.look.inn = Look.BUBBLES[1].second
        call(a, "showCrop", testBitmap(0xFF6A1B9A.toInt(), 0xFFFFB74D.toInt(), 1200, 800)); shoot(a, "6-crop")
        Messenger.initForPreview(fakeCore(signedIn = false))
        call(a, "showWelcome"); shoot(a, "0-welcome")
        call(a, "showSignup"); shoot(a, "0b-signup")
    }

    /** 4.4: icon (with the round mask), folders, the long-press menu, and the react-bar ranking. */
    @Test fun v44() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val dir = File("build/shots").apply { mkdirs() }
        val ic = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        Canvas(ic).apply {
            val path = android.graphics.Path().apply { addCircle(216f, 216f, 216f, android.graphics.Path.Direction.CW) }
            clipPath(path)
            for (r in listOf(R.drawable.ic_launcher_bg, R.drawable.ic_launcher_fg)) ctx.getDrawable(r)!!.let { d -> d.setBounds(0, 0, 432, 432); d.draw(this) }
        }
        FileOutputStream(File(dir, "20-icon.png")).use { ic.compress(Bitmap.CompressFormat.PNG, 100, it) }

        Messenger.initForPreview(fakeCore(signedIn = true))
        Messenger.folders.clear()
        Messenger.folders += Folder("f1", "Family", true, mutableListOf("u:sam_r", "g:g1"))
        Messenger.folders += Folder("f2", "Work", false, mutableListOf("u:jordan_k"))
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        call(a, "showChats"); shoot(a, "21-folders")
        call(a, "showChat", "sam_r"); shadowOf(Looper.getMainLooper()).idle()
        call(a, "startReply", Messenger.core.contact("sam_r")!!.msgs.first { it.id == "4" }); shoot(a, "23-reply")
        call(a, "cancelReply")
        a.javaClass.getDeclaredField("touchX").apply { isAccessible = true }.setFloat(a, 300f)
        a.javaClass.getDeclaredField("touchY").apply { isAccessible = true }.setFloat(a, 1200f)
        val m = Messenger.core.contact("sam_r")!!.msgs.first { it.id == "4" }
        call(a, "messageMenu", Messenger.core.contact("sam_r")!!, m)
        shadowOf(Looper.getMainLooper()).idle()
        org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView?.let { v ->
            val bmp = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
            v.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(2340, android.view.View.MeasureSpec.EXACTLY))
            v.layout(0, 0, 1080, 2340)
            shadowOf(Looper.getMainLooper()).idle()
            ShadowLooperIdleFor(400)
            v.draw(Canvas(bmp))
            FileOutputStream(File(dir, "22-menu.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        Folder.fromJson(Folder.toJson(Messenger.folders)).let { back ->
            assert(back.map { it.name } == listOf("Family", "Work") && back[1].open == false && back[0].items == listOf("u:sam_r", "g:g1"))
        }
        val fixed = listOf("👍", "❤️", "😂", "😮", "😢"); val filler = listOf("🙏", "🔥", "👏")
        val q = MainActivity.quickReactions(mapOf("👍" to 50, "❤" to 40, "🎉" to 9, "😎" to 3), fixed, filler)
        assert(q == fixed + listOf("🎉", "😎", "🙏")) { "got $q" }
        assert(MainActivity.quickReactions(emptyMap(), fixed, filler) == fixed + filler)
    }

    private fun ShadowLooperIdleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))

    /** Guards the 3.6/3.7 composer fix: the message box must wrap (maxLines 5), not collapse to one line. */
    @Test fun composerWraps() {
        Messenger.initForPreview(fakeCore(signedIn = true))
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        call(a, "showChat", "sam_r")
        shadowOf(Looper.getMainLooper()).idle()
        val box = findEditText(a.window.decorView) { it.hint?.toString() == "Message" }
        requireNotNull(box) { "message box not found" }
        assert(box.maxLines == 5) { "composer should wrap to 5 lines, but maxLines=${box.maxLines}" }
    }

    private fun findEditText(v: android.view.View, match: (android.widget.EditText) -> Boolean): android.widget.EditText? {
        if (v is android.widget.EditText && match(v)) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findEditText(v.getChildAt(i), match)?.let { return it }
        return null
    }
}
