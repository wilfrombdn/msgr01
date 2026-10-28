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
        sam.msgs += Msg("c1", true, "", now - 20_000, Msg.SENT).also { it.call = org.json.JSONObject().put("d", "out").put("s", "ok").put("dur", 151) }
        sam.msgs += Msg("c2", false, "", now - 10_000, Msg.RECEIVED).also { it.call = org.json.JSONObject().put("d", "in").put("s", "missed").put("dur", 0) }
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

    @Test fun popupAndSpacing() {
        Messenger.initForPreview(fakeCore(signedIn = true))
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        call(a, "showChat", "sam_r"); shoot(a, "40-spacing")
        call(a, "deleteChatConfirm", Messenger.core.contact("sam_r")!!)
        shadowOf(Looper.getMainLooper()).idle()
        org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView?.let { v ->
            v.measure(android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
            val bmp = Bitmap.createBitmap(v.measuredWidth, v.measuredHeight, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(android.graphics.Color.GRAY); v.draw(Canvas(bmp))
            FileOutputStream(File("build/shots", "41-popup.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun storage() {
        Messenger.initForPreview(fakeCore(signedIn = true))
        Messenger.core.blobSize = { 3_400_000L }
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        call(a, "showStorage"); shoot(a, "50-storage")
    }

    @Test fun lightMode() {
        Messenger.initForPreview(fakeCore(signedIn = true))
        Messenger.look.theme = "light"; Messenger.look.inn = Look.BUBBLES.last().second
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        call(a, "showChats"); shoot(a, "30-light-chats")
        call(a, "showChat", "sam_r"); shoot(a, "31-light-chat")
        call(a, "showAppearance"); shoot(a, "32-light-appearance")
        Messenger.look.load(org.json.JSONObject()); Messenger.look.out = Look.BUBBLES[0].second; Messenger.look.inn = Look.BUBBLES[1].second
    }

    /** Voice messages: waveform packs/unpacks, bubbles (mine + theirs), mic shows when the box is empty. */
    @Test fun voice() {
        val levels = List(83) { (Math.abs(Math.sin(it / 4.0)) * 9000 + (it * 37 % 11) * 300).toInt() }
        val th = Voice.pack(14, levels)
        val (secs, bars) = Voice.unpack(java.util.Base64.getEncoder().encodeToString(th))!!
        assert(secs == 14 && bars.size == Voice.BARS && bars.max() == 1f) { "waveform round trip" }
        assert(Voice.unpack(java.util.Base64.getEncoder().encodeToString(testJpeg(0, 0))) == null) { "a photo thumb is not a waveform" }
        Messenger.initForPreview(fakeCore(signedIn = true))
        val sam = Messenger.core.contact("sam_r")!!
        val now = System.currentTimeMillis()
        fun voice(id: String, mine: Boolean, secs: Int, local: Boolean) = Msg(id, mine, "", now, if (mine) Msg.SENT else Msg.RECEIVED).also {
            it.file = org.json.JSONObject().put("id", id.repeat(32).take(32)).put("k", "0".repeat(64)).put("n", Voice.NAME).put("m", Voice.MIME)
                .put("s", 60_000).put("th", java.util.Base64.getEncoder().encodeToString(Voice.pack(secs, levels.shuffled(java.util.Random(secs.toLong())))))
                .apply { if (local) put("local", "x") }
        }
        sam.msgs += voice("a", false, 14, true)
        sam.msgs += voice("b", true, 72, true)
        sam.msgs += voice("c", false, 5, false)
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        call(a, "showChat", "sam_r"); shoot(a, "40-voice")
        call(a, "showChats"); shoot(a, "41-voice-preview")

        // hold the mic, slide up onto the lock, let go: still recording, mic is now send, "Cancel" shows
        shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        call(a, "showChat", "sam_r")
        shadowOf(Looper.getMainLooper()).idle()
        val mic = findView(a.window.decorView) { it is android.widget.ImageView && it.parent === findEditText(a.window.decorView) { b -> b.hint?.toString() == "Message" }!!.parent && (it.parent as android.view.ViewGroup).indexOfChild(it) == (it.parent as android.view.ViewGroup).childCount - 1 }!!
        val d = a.resources.displayMetrics.density
        fun touch(action: Int, y: Float) = mic.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0, action, 10f, y, 0))
        touch(android.view.MotionEvent.ACTION_DOWN, 500f)
        if (!Voice.recording) { println("voice: MediaRecorder not available under Robolectric; lock gesture not exercised"); return }
        touch(android.view.MotionEvent.ACTION_MOVE, 500f - 100 * d)
        touch(android.view.MotionEvent.ACTION_UP, 500f - 100 * d)
        assert(Voice.recording) { "letting go after locking must keep recording" }
        val cancel = findView(a.window.decorView) { it is android.widget.TextView && it.text.toString() == "Cancel" }
        assert(cancel != null) { "locked: Cancel shows" }
        shoot(a, "42-voice-locked")
        cancel!!.performClick()
        assert(!Voice.recording) { "Cancel stops the recording" }
    }

    private fun findView(v: android.view.View, match: (android.view.View) -> Boolean): android.view.View? {
        if (match(v)) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findView(v.getChildAt(i), match)?.let { return it }
        return null
    }

    /** "typing…" / "online" / "last seen" under the name and in the chat list; the two privacy switches. */
    @Test fun presence() {
        Messenger.initForPreview(fakeCore(signedIn = true))
        val sam = Messenger.core.contact("sam_r")!!
        val jo = Messenger.core.contacts.first { it.user != "sam_r" && it.status == Contact.FRIENDS }
        val now = System.currentTimeMillis()
        sam.theirTypingUntil = now + 60_000
        jo.theirSeen = now - 25 * 60_000
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        call(a, "showChats"); shoot(a, "43-typing-list")
        call(a, "showChat", "sam_r"); shoot(a, "44-typing")
        call(a, "showChat", jo.user); shoot(a, "45-last-seen")
        call(a, "showSettings"); shoot(a, "46-privacy-switches")
    }

    /** Forwarded + edited bubbles, the edit strip above the box, and the forward picker. */
    @Test fun editForward() {
        Messenger.initForPreview(fakeCore(signedIn = true))
        val sam = Messenger.core.contact("sam_r")!!
        val now = System.currentTimeMillis()
        sam.msgs += Msg("f1", false, "brake chamber part # is 3030T", now, Msg.RECEIVED).also { it.fwd = "jordan_k" }
        val mine = Msg("e1", true, "running 10 min late", now, Msg.SENT).also { it.edited = true }
        sam.msgs += mine
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        call(a, "showChat", "sam_r"); shadowOf(Looper.getMainLooper()).idle()
        call(a, "startEdit", mine, a.javaClass.getDeclaredField("chatBox").apply { isAccessible = true }.get(a))
        shoot(a, "47-edit-forwarded")
        call(a, "cancelReply")
        call(a, "forwardPicker", mine, "wil_p"); shadowOf(Looper.getMainLooper()).idle()
        val dlg = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        val v = dlg.window!!.decorView
        v.measure(android.view.View.MeasureSpec.makeMeasureSpec(900, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val bmp = Bitmap.createBitmap(v.measuredWidth, v.measuredHeight, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File("build/shots/48-forward-picker.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Tapping call opens the call screen at once (even before the invite is out). */
    @Test fun callButtonOpensCallScreen() {
        Messenger.initForPreview(fakeCore(signedIn = true))
        shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        call(a, "showChat", "sam_r"); shadowOf(Looper.getMainLooper()).idle()
        call(a, "callFriend", Messenger.core.contact("sam_r")!!)
        val next = shadowOf(a).nextStartedActivity
        println("callButton: core.call=${Messenger.core.call?.phase} next=${next?.component?.className}")
        assert(next?.component?.className == CallActivity::class.java.name) { "call screen should open, got $next" }
    }

    /** Pinned chats sit above folders (and friend requests), with a pin mark. */
    @Test fun pinned() {
        Messenger.initForPreview(fakeCore(signedIn = true))
        val jo = Messenger.core.contacts.first { it.user != "sam_r" && it.status == Contact.FRIENDS }
        Messenger.folders.clear(); Messenger.folders.add(Folder("f1", "Work", true, mutableListOf("u:sam_r")))
        Messenger.pins.clear(); Messenger.pins.addAll(listOf("g:g1", "u:${jo.user}"))
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        call(a, "showChats"); shoot(a, "49-pinned")
        Messenger.pins.clear(); Messenger.folders.clear()
    }

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
