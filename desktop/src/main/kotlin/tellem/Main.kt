package tellem

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.text.selection.SelectionContainer
import kotlinx.coroutines.withContext
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.wilfrombdn.telegramnt.*
import java.awt.FileDialog
import java.awt.Frame
import java.text.SimpleDateFormat
import java.util.Date

fun main() {
    Session.init()
    application {
        Window(
            onCloseRequest = { Session.save(); exitApplication() },
            title = "Tellem",
            state = rememberWindowState(size = DpSize(1000.dp, 680.dp)),
        ) {
            window.minimumSize = java.awt.Dimension(820, 520)
            // whole UI drawn at half size (TELLEM_SCALE=1 for the old size)
            val base = LocalDensity.current
            val k = System.getenv("TELLEM_SCALE")?.toFloatOrNull() ?: 0.5f
            CompositionLocalProvider(LocalDensity provides Density(base.density * k, base.fontScale)) {
                MaterialTheme(colorScheme = darkColorScheme(primary = C.blurple, surface = C.chat, background = C.chat)) { Root() }
            }
        }
    }
}

/** What the middle column / chat pane is showing. */
sealed interface Sel {
    data object Home : Sel
    data class Dm(val user: String) : Sel
    data class Grp(val id: String) : Sel
}

@Composable
fun Root() {
    Session.tick.intValue // redraw on every change
    Box(Modifier.fillMaxSize().background(C.chat)) {
        if (Session.core.me == null) AuthScreen() else MainScreen()
    }
}

// ---------------------------------------------------------------- sign in

@Composable
fun AuthScreen() {
    var signup by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(Session.signedOutReason.value) }
    val go = {
        busy = true; error = null
        Session.signedOutReason.value = null
        Session.run({
            val u = user.trim().removePrefix("@").lowercase()
            if (signup) Session.core.signup(u, pass, name) else Session.core.login(u, pass)
        }) { err -> busy = false; error = err; if (err == null) Session.changed() }
    }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight().background(C.blurpleDark), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(16.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                    Text("T", fontSize = 56.sp, fontWeight = FontWeight.Black, color = C.blurple)
                }
                Spacer(Modifier.height(24.dp))
                Text("Tellem", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Private messaging, now on your desktop", color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
            }
        }
        Box(Modifier.width(420.dp).fillMaxHeight().background(C.list), contentAlignment = Alignment.Center) {
            Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (signup) "Create an account" else "Welcome back", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = C.text)
                Field(user, { user = it }, "Username (a-z, 0-9, _)", prefix = "@")
                if (signup) Field(name, { name = it }, "Display name")
                Field(pass, { pass = it }, "Password" + if (signup) " (10+ characters)" else "", password = true, onEnter = { if (!busy) go() })
                error?.let { Text(it, color = C.red, fontSize = 13.sp) }
                Button(
                    onClick = { go() }, enabled = !busy && user.isNotBlank() && pass.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = C.blurple, contentColor = Color.White),
                    shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth().height(38.dp),
                ) { Text(if (busy) "Please wait…" else if (signup) "Sign up" else "Log in") }
                Text(
                    if (signup) "Already have an account? Log in" else "Need an account? Sign up",
                    color = C.blurple, fontSize = 14.sp, modifier = Modifier.clickable { signup = !signup; error = null },
                )
                Spacer(Modifier.height(8.dp))
                Text(Session.torStatus.value, color = C.faint, fontSize = 12.sp)
                if (!signup) Text(
                    "Heads up: Tellem allows one device per account. Logging in here signs your phone out, and your old chat history stays on the phone.",
                    color = C.faint, fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
fun Field(
    value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier.fillMaxWidth(),
    password: Boolean = false, prefix: String = "", onEnter: (() -> Unit)? = null, bg: Color = C.search,
) {
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = TextStyle(color = C.text, fontSize = 13.sp),
        cursorBrush = SolidColor(C.text),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.onPreviewKeyEvent { e ->
            if (onEnter != null && e.key == Key.Enter && e.type == KeyEventType.KeyDown) { onEnter(); true } else false
        },
        decorationBox = { inner ->
            Row(Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                if (prefix.isNotEmpty()) Text(prefix, color = C.faint, fontSize = 13.sp)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(hint, color = C.faint, fontSize = 13.sp)
                    inner()
                }
            }
        },
    )
}

// ---------------------------------------------------------------- main window

@Composable
fun MainScreen() {
    val core = Session.core
    var sel by remember { mutableStateOf<Sel>(Sel.Home) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    // the chat open in the right pane (a group icon on the rail opens that group; Home shows direct chats)
    var chat by remember { mutableStateOf<Sel>(if (Session.demo) Sel.Dm("maya_k") else Sel.Home) }

    Row(Modifier.fillMaxSize()) {
        Rail(sel, { sel = it; if (it is Sel.Grp) chat = it }, onAdd = { dialog = "add" })
        Column(Modifier.width(280.dp).fillMaxHeight().background(C.list)) {
            Column(Modifier.weight(1f)) {
                when (val s = sel) {
                    is Sel.Grp -> GroupColumn(s.id, chat, onOpen = { chat = it })
                    else -> HomeColumn(search, { search = it }, chat, onOpen = { chat = it }, onMenu = { dialog = it })
                }
            }
            UserPanel(onSettings = { dialog = "settings" })
        }
        Box(Modifier.weight(1f).fillMaxHeight().background(C.chat)) {
            when (val c = chat) {
                is Sel.Dm -> core.contact(c.user)?.let { ChatPane(Target.Dm(it)) { dialog = it } }
                is Sel.Grp -> core.group(c.id)?.let { ChatPane(Target.Grp(it)) { dialog = it } }
                else -> EmptyPane()
            }
        }
    }
    when (dialog) {
        "add" -> AddDialog(onClose = { dialog = null }, onOpen = { chat = it; sel = if (it is Sel.Grp) it else Sel.Home })
        "settings" -> SettingsDialog(onClose = { dialog = null })
    }
}

@Composable
fun EmptyPane() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text("Select a chat to start messaging", color = C.muted, fontSize = 14.sp,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.field).padding(horizontal = 10.dp, vertical = 4.dp))
}

// ---------------------------------------------------------------- Discord-style rail

@Composable
fun Rail(sel: Sel, onSelect: (Sel) -> Unit, onAdd: () -> Unit) {
    Session.tick.intValue
    val core = Session.core
    val groups = synchronized(core) { core.groups.toList() }
    val dmUnread = synchronized(core) { core.contacts.sumOf { it.unread } }
    Column(
        Modifier.width(54.dp).fillMaxHeight().background(C.rail).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RailIcon("T", C.blurple, selected = sel is Sel.Home, badge = dmUnread, tip = "Direct messages") { onSelect(Sel.Home) }
        Box(Modifier.width(24.dp).height(1.dp).background(C.field))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            for (g in groups) RailIcon(initials(g.name), C.avatar(g.id), selected = (sel as? Sel.Grp)?.id == g.id, badge = g.unread, tip = g.name) { onSelect(Sel.Grp(g.id)) }
        }
        RailIcon("+", C.list, selected = false, hoverColor = C.green, textColor = C.green, tip = "Add a friend or group") { onAdd() }
    }
}

@Composable
fun RailIcon(label: String, color: Color, selected: Boolean, badge: Int = 0, hoverColor: Color? = null, textColor: Color = Color.White, tip: String = "", onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val round by animateDpAsState(if (selected || hovered) 6.dp else 10.dp)
    val fill = if (hovered && hoverColor != null) hoverColor else color
    val fg = if (hovered && hoverColor != null) Color.White else textColor
    Box(Modifier.width(54.dp).height(38.dp), contentAlignment = Alignment.Center) {
        // Discord's white pill on the left edge
        Box(Modifier.align(Alignment.CenterStart).width(4.dp).height(if (selected) 24.dp else if (hovered) 12.dp else if (badge > 0) 6.dp else 0.dp)
            .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)).background(Color.White))
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(round)).background(if (selected) C.blurple else fill)
                .hoverable(src).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text(label, color = if (selected) Color.White else fg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        if (badge > 0) Box(Modifier.align(Alignment.BottomEnd).padding(end = 4.dp).clip(CircleShape).background(C.rail).padding(1.dp)) {
            Box(Modifier.clip(CircleShape).background(C.red).padding(horizontal = 5.dp, vertical = 1.dp)) {
                Text(if (badge > 99) "99+" else "$badge", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---------------------------------------------------------------- chat list (Telegram style)

@Composable
fun HomeColumn(search: String, onSearch: (String) -> Unit, chat: Sel, onOpen: (Sel) -> Unit, onMenu: (String) -> Unit) {
    Session.tick.intValue
    val core = Session.core
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Field(search, onSearch, "Search", modifier = Modifier.weight(1f))
    }
    val q = search.trim().lowercase()
    val items = synchronized(core) {
        val d = core.contacts.map { Triple(Sel.Dm(it.user) as Sel, it.name, it.lastTs) }
        d.filter { q.isEmpty() || it.second.lowercase().contains(q) }.sortedByDescending { it.third }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (items.isEmpty()) item {
            Text(if (q.isEmpty()) "No chats yet.\nPress + on the left to add a friend." else "Nothing found", color = C.faint, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
        }
        items(items.size) { i ->
            val s = items[i].first as Sel.Dm
            val c = core.contact(s.user) ?: return@items
            ChatRow(
                title = c.name, avatarBytes = c.avatar, seed = c.user,
                subtitle = c.msgs.lastOrNull { it.state != Msg.NOTE }?.let { (if (it.mine) "You: " else "") + previewLine(it) } ?: if (c.status == Contact.ASKING) "Wants to be friends" else "",
                ts = c.lastTs, unread = c.unread, selected = (chat as? Sel.Dm)?.user == c.user,
                online = c.theirOnlineUntil > System.currentTimeMillis(),
            ) { onOpen(s) }
        }
    }
}

@Composable
fun GroupColumn(id: String, chat: Sel, onOpen: (Sel) -> Unit) {
    Session.tick.intValue
    val g = Session.core.group(id) ?: return
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(g.name, color = C.text, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.divider))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
        Text("CHANNELS", color = C.faint, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
        val on = (chat as? Sel.Grp)?.id == id
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (on) C.selected else Color.Transparent)
                .clickable { onOpen(Sel.Grp(id)) }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("#", color = C.faint, fontSize = 20.sp, modifier = Modifier.width(22.dp))
            Text(g.name.lowercase().replace(' ', '-'), color = if (on || g.unread > 0) C.text else C.muted, fontWeight = if (g.unread > 0) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (g.unread > 0) Badge(g.unread, C.red)
        }
        Spacer(Modifier.height(16.dp))
        val members = synchronized(Session.core) { g.members.toList().sorted() }
        Text("MEMBERS — ${members.size}", color = C.faint, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
        for (m in members) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            val c = Session.core.contact(m)
            Avatar(c?.name ?: "@$m", c?.avatar, m, 32.dp, online = c?.let { it.theirOnlineUntil > System.currentTimeMillis() })
            Spacer(Modifier.width(10.dp))
            Text(c?.name ?: "@$m", color = if (g.owner == m) C.yellow else C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (g.owner == m) Text("👑", fontSize = 12.sp)
        }
    }
}

@Composable
fun ChatRow(title: String, avatarBytes: ByteArray?, seed: String, subtitle: String, ts: Long, unread: Int, selected: Boolean, online: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp).clip(RoundedCornerShape(6.dp))
            .background(if (selected) C.blurple else if (hovered) C.hover else Color.Transparent)
            .hoverable(src).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(title, avatarBytes, seed, 42.dp, online = online)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(title, color = if (selected) Color.White else C.text, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(shortTime(ts), color = if (selected) Color.White.copy(alpha = .8f) else C.faint, fontSize = 12.sp)
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(subtitle, color = if (selected) Color.White.copy(alpha = .85f) else C.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (unread > 0) Badge(unread, if (selected) Color.White else C.blurple, if (selected) C.blurple else Color.White)
            }
        }
    }
}

@Composable
fun Badge(n: Int, bg: Color, fg: Color = Color.White) = Box(Modifier.clip(CircleShape).background(bg).padding(horizontal = 7.dp, vertical = 1.dp)) {
    Text(if (n > 99) "99+" else "$n", color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun Avatar(name: String, bytes: ByteArray?, seed: String, size: androidx.compose.ui.unit.Dp, online: Boolean? = null) {
    val img = remember(bytes) { bytes?.let { runCatching { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() } }
    Box(Modifier.size(size)) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(C.avatar(seed)), contentAlignment = Alignment.Center) {
            if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            else Text(initials(name), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.36f).sp)
        }
        if (online != null) Box(Modifier.align(Alignment.BottomEnd).size(size * 0.3f).clip(CircleShape).background(C.list).padding(2.dp)) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(if (online) C.green else C.faint))
        }
    }
}

// ---------------------------------------------------------------- Discord user panel

@Composable
fun UserPanel(onSettings: () -> Unit) {
    Session.tick.intValue
    val me = Session.core.me ?: return
    val ok by Session.connected
    Row(Modifier.fillMaxWidth().height(44.dp).background(C.panel).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(me.name, me.avatar, me.user, 30.dp, online = ok)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(me.name, color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (ok) "Connected via Tor" else Session.torStatus.value.take(34), color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconBtn("⚙", onSettings)
    }
}

@Composable
fun IconBtn(glyph: String, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(4.dp)).background(if (hovered) C.hover else Color.Transparent).hoverable(src).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(glyph, color = C.muted, fontSize = 15.sp)
    }
}

// ---------------------------------------------------------------- chat pane

sealed interface Target {
    data class Dm(val c: Contact) : Target
    data class Grp(val g: Group) : Target
}

@Composable
fun ChatPane(t: Target, onDialog: (String) -> Unit) {
    Session.tick.intValue
    val core = Session.core
    val key = when (t) { is Target.Dm -> "u:${t.c.user}"; is Target.Grp -> "g:${t.g.id}" }
    val msgs = synchronized(core) { when (t) { is Target.Dm -> t.c.msgs.toList(); is Target.Grp -> t.g.msgs.toList() } }
    val unread = when (t) { is Target.Dm -> t.c.unread; is Target.Grp -> t.g.unread }
    val list = rememberLazyListState()
    var reactingTo by remember(key) { mutableStateOf<String?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }

    // this chat is on screen: no unread badge, tell them it was read
    LaunchedEffect(key, msgs.size, unread) {
        core.openChat = key
        if (unread > 0) Session.io.execute {
            when (t) { is Target.Dm -> core.markRead(t.c); is Target.Grp -> { t.g.unread = 0; t.g.mentioned = false; core.onChange() } }
        }
    }
    LaunchedEffect(key, msgs.size) { if (msgs.isNotEmpty()) list.scrollToItem(msgs.size - 1) }

    val title = when (t) { is Target.Dm -> t.c.name; is Target.Grp -> "# " + t.g.name.lowercase().replace(' ', '-') }
    val sub = when (t) {
        is Target.Dm -> when {
            t.c.theirTypingUntil > System.currentTimeMillis() -> "typing…"
            t.c.theirOnlineUntil > System.currentTimeMillis() -> "online"
            t.c.theirSeen > 0 -> "last seen " + shortTime(t.c.theirSeen)
            else -> "@" + t.c.user
        }
        is Target.Grp -> {
            val typing = t.g.typing.filter { it.value > System.currentTimeMillis() }.keys
            if (typing.isNotEmpty()) typing.joinToString { "@$it" } + " typing…" else "${t.g.members.size} members"
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(48.dp).background(C.chat).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            when (t) {
                is Target.Dm -> Avatar(t.c.name, t.c.avatar, t.c.user, 34.dp, online = t.c.theirOnlineUntil > System.currentTimeMillis())
                is Target.Grp -> Box(Modifier.size(34.dp).clip(CircleShape).background(C.avatar(t.g.id)), contentAlignment = Alignment.Center) { Text("#", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = C.text, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, color = if (sub == "online" || sub.endsWith("typing…")) C.blurple else C.muted, fontSize = 13.sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.divider))

        if (t is Target.Dm && t.c.status == Contact.ASKING) {
            Row(Modifier.fillMaxWidth().background(C.list).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${t.c.name} wants to be friends.", color = C.text, modifier = Modifier.weight(1f))
                Button({ Session.run({ core.accept(t.c, true) }) { error = it } }, colors = ButtonDefaults.buttonColors(containerColor = C.green)) { Text("Accept") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton({ Session.run({ core.remove(t.c) }) { error = it } }) { Text("Decline", color = C.muted) }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(msgs.size, key = { msgs[it].id }) { i ->
                    val m = msgs[i]
                    val prev = msgs.getOrNull(i - 1)
                    if (prev == null || day(prev.ts) != day(m.ts)) DayChip(m.ts)
                    if (m.state == Msg.NOTE) {
                        Text(m.text, color = C.muted, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    } else {
                        val first = prev == null || prev.mine != m.mine || prev.sender != m.sender || prev.state == Msg.NOTE
                        Bubble(t, m, showName = first && t is Target.Grp && !m.mine, reacting = reactingTo == m.id,
                            onToggleReact = { reactingTo = if (reactingTo == m.id) null else m.id },
                            onReact = { e -> reactingTo = null; Session.run({ when (t) { is Target.Dm -> core.react(t.c, m, e); is Target.Grp -> core.reactGroup(t.g, m, e) } }) { error = it } },
                            onRetry = { Session.io.execute { when (t) { is Target.Dm -> core.deliver(t.c, m.also { it.state = Msg.SENDING }); is Target.Grp -> core.deliverGroup(t.g, m.also { it.state = Msg.SENDING }) } } })
                    }
                }
            }
        }
        error?.let { Text(it, color = C.red, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp)) }
        val canSend = when (t) { is Target.Dm -> t.c.canChat || t.c.status == Contact.ASKED; is Target.Grp -> t.g.canPost(core.me?.user ?: "") }
        if (canSend) Composer(key,
            onSend = { text -> Session.run({
                when (t) {
                    is Target.Dm -> { val m = core.queue(t.c, text); core.deliver(t.c, m) }
                    is Target.Grp -> { val m = core.queueGroup(t.g, text); core.deliverGroup(t.g, m) }
                }
            }) { error = it } },
            onTyping = { Session.io.execute { runCatching { when (t) { is Target.Dm -> core.sendTyping(t.c); is Target.Grp -> core.sendGroupTyping(t.g) } } } },
            onFile = { name, mime, data -> Session.run({
                when (t) {
                    is Target.Dm -> { val m = core.queueFile(t.c, name, mime, data, null); core.deliver(t.c, m) }
                    is Target.Grp -> { val m = core.queueGroupFile(t.g, name, mime, data, null); core.deliverGroup(t.g, m) }
                }
            }) { error = it } },
            onError = { error = it },
        ) else Text("Only admins can post here.", color = C.muted, modifier = Modifier.padding(16.dp))
    }
}

@Composable
fun DayChip(ts: Long) = Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
    Text(SimpleDateFormat("MMMM d").format(Date(ts)), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x66000000)).padding(horizontal = 12.dp, vertical = 3.dp))
}

private val EMOJIS = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")

@Composable
fun Bubble(t: Target, m: Msg, showName: Boolean, reacting: Boolean, onToggleReact: () -> Unit, onReact: (String) -> Unit, onRetry: () -> Unit) {
    val mine = m.mine
    val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = if (mine) 8.dp else 2.dp, bottomEnd = if (mine) 2.dp else 8.dp)
    val read = (t as? Target.Dm)?.c?.theirReadUpto?.let { it >= m.ts } == true
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = 440.dp)) {
            Column(Modifier.clip(shape).background(if (mine) C.bubbleOut else C.bubbleIn).clickable(onClick = onToggleReact).padding(horizontal = 9.dp, vertical = 5.dp)) {
                if (showName) Text("@" + (m.sender ?: ""), color = C.avatar(m.sender ?: "").let { Color(it.red * .5f + .5f, it.green * .5f + .5f, it.blue * .5f + .5f) }, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                m.fwd?.let { Text("Forwarded from $it", color = Color.White.copy(alpha = .75f), fontSize = 12.sp) }
                m.reply?.let { r ->
                    Row(Modifier.padding(bottom = 4.dp).height(IntrinsicSize.Min)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(if (mine) Color.White else C.blurple))
                        Column(Modifier.padding(start = 8.dp)) {
                            Text("@" + r.optString("u"), color = if (mine) Color.White else C.blurple, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(r.optString("p"), color = Color.White.copy(alpha = .8f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                m.file?.let { AttachmentView(m) }
                m.call?.let { Text("📞 " + callTitle(it), color = C.text) }
                m.poll?.let { Text("📊 " + it.optString("q"), color = C.text) }
                m.loc?.let { Text("📍 Location shared", color = C.text) }
                m.lp?.let { Text(it.optString("t") + "\n" + it.optString("u"), color = Color(0xFFB9C3FF), fontSize = 13.sp) }
                if (m.text.isNotEmpty()) SelectionContainer { Text(m.text, color = C.text, fontSize = 13.sp) }
                Row(Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (m.edited) Text("edited  ", color = Color.White.copy(alpha = .6f), fontSize = 10.sp)
                    Text(SimpleDateFormat("HH:mm").format(Date(m.ts)), color = Color.White.copy(alpha = if (mine) .75f else .55f), fontSize = 10.sp)
                    if (mine) {
                        val tick = when (m.state) { Msg.SENDING, Msg.SCHEDULED -> " …"; Msg.FAILED -> " ✗"; else -> if (read) " ✓✓" else " ✓" }
                        Text(tick, color = if (m.state == Msg.FAILED) C.red else Color.White.copy(alpha = .85f), fontSize = 10.sp,
                            modifier = if (m.state == Msg.FAILED) Modifier.clickable(onClick = onRetry) else Modifier)
                    }
                }
            }
            // Discord-style reaction pills
            val counts = m.reactions.values.groupingBy { it }.eachCount()
            if (counts.isNotEmpty()) Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val me = Session.core.me?.user
                for ((e, n) in counts) Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(if (m.reactions[me] == e) Color(0x445865F2) else C.field)
                        .border(1.dp, if (m.reactions[me] == e) C.blurple else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { onReact(e) }.padding(horizontal = 7.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { Text(e, fontSize = 14.sp); Text(" $n", color = C.muted, fontSize = 12.sp) }
            }
            if (reacting) Row(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).background(C.panel).padding(4.dp)) {
                for (e in EMOJIS) Box(Modifier.clip(RoundedCornerShape(6.dp)).clickable { onReact(e) }.padding(6.dp)) { Text(e, fontSize = 18.sp) }
            }
        }
    }
}

@Composable
fun AttachmentView(m: Msg) {
    val f = m.file ?: return
    val name = f.optString("n")
    val mime = f.optString("m")
    var img by remember(m.id, f.optString("local")) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(m.id, f.optString("local")) {
        if (mime.startsWith("image/") && f.has("local")) {
            img = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { Session.core.openFile(m)?.let { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() } }.getOrNull()
            }
        }
    }
    val bmp = img
    if (bmp != null) Image(bmp, name, Modifier.widthIn(max = 380.dp).heightIn(max = 380.dp).clip(RoundedCornerShape(10.dp)))
    else Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = .2f)), contentAlignment = Alignment.Center) { Text(if (mime.startsWith("image/")) "🖼" else "📎", fontSize = 18.sp) }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(name.ifEmpty { "File" }, color = C.text, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(humanSize(f.optLong("s")) + if (!f.has("local") && !m.mine) " · downloading…" else "", color = Color.White.copy(alpha = .7f), fontSize = 12.sp)
        }
    }
}

@Composable
fun Composer(key: String, onSend: (String) -> Unit, onTyping: () -> Unit, onFile: (String, String, ByteArray) -> Unit, onError: (String) -> Unit) {
    var text by remember(key) { mutableStateOf("") }
    val send = {
        val t = text.trim()
        if (t.isNotEmpty()) { onSend(t.take(Core.MAX_TEXT)); text = "" }
    }
    var lastTyping by remember { mutableStateOf(0L) }
    Row(Modifier.fillMaxWidth().background(C.list).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
        Row(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(C.field).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn("📎") {
                val dlg = FileDialog(null as Frame?, "Send a file", FileDialog.LOAD).apply { isVisible = true }
                val f = dlg.file?.let { java.io.File(dlg.directory, it) }
                if (f != null) {
                    if (f.length() > Core.MAX_FILE) onError("That file is over ${Core.MAX_FILE / 1024 / 1024} MB")
                    else onFile(f.name, java.net.URLConnection.guessContentTypeFromName(f.name) ?: "application/octet-stream", f.readBytes())
                }
            }
            BasicTextField(
                value = text, onValueChange = {
                    text = it
                    if (it.isNotEmpty() && System.currentTimeMillis() - lastTyping > 4000) { lastTyping = System.currentTimeMillis(); onTyping() }
                },
                textStyle = TextStyle(color = C.text, fontSize = 13.sp), cursorBrush = SolidColor(C.text), maxLines = 8,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp).onPreviewKeyEvent { e ->
                    if (e.key == Key.Enter && !e.isShiftPressed && e.type == KeyEventType.KeyDown) { send(); true } else false
                },
                decorationBox = { inner -> Box { if (text.isEmpty()) Text("Write a message…", color = C.faint, fontSize = 13.sp); inner() } },
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)).background(C.blurple).clickable { send() }, contentAlignment = Alignment.Center) {
            Text("➤", color = Color.White, fontSize = 14.sp)
        }
    }
}

// ---------------------------------------------------------------- dialogs

@Composable
fun DialogCard(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) = Dialog(onDismissRequest = onClose) {
    Column(Modifier.width(360.dp).clip(RoundedCornerShape(8.dp)).background(C.list).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        content()
    }
}

@Composable
fun AddDialog(onClose: () -> Unit, onOpen: (Sel) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var v by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val core = Session.core
    DialogCard("Add to Tellem", onClose) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Add friend", "New group", "Join group").forEachIndexed { i, s ->
                Text(s, color = if (tab == i) Color.White else C.muted, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (tab == i) C.blurple else C.field).clickable { tab = i; v = ""; err = null }.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        Field(v, { v = it }, when (tab) { 0 -> "@username"; 1 -> "Group name"; else -> "Paste a group link" }, bg = C.search)
        err?.let { Text(it, color = C.red, fontSize = 13.sp) }
        Button(
            onClick = {
                busy = true; err = null
                var open: Sel? = null
                Session.run({
                    open = when (tab) {
                        0 -> Sel.Dm(core.addFriend(v).user)
                        1 -> Sel.Grp(core.createGroup(v, public = true).id)
                        else -> Sel.Grp(core.joinGroupLink(v).id)
                    }
                }) { e -> busy = false; err = e; if (e == null) { open?.let(onOpen); onClose() } }
            },
            enabled = !busy && v.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = C.blurple, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Please wait…" else when (tab) { 0 -> "Send friend request"; 1 -> "Create group"; else -> "Join" }) }
    }
}

@Composable
fun SettingsDialog(onClose: () -> Unit) {
    val me = Session.core.me ?: return
    DialogCard("Settings", onClose) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(me.name, me.avatar, me.user, 56.dp)
            Spacer(Modifier.width(14.dp))
            Column { Text(me.name, color = C.text, fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("@" + me.user, color = C.muted) }
        }
        Text("Connection: " + (if (Session.connected.value) "connected" else "not connected") + " · " + Session.torStatus.value, color = C.muted, fontSize = 13.sp)
        Text("Your chats are stored encrypted in ${Session.dir.path}. Use BitLocker / LUKS disk encryption for full protection.", color = C.faint, fontSize = 12.sp)
        Button({ Session.logOut(); onClose() }, colors = ButtonDefaults.buttonColors(containerColor = C.red, contentColor = Color.White), modifier = Modifier.fillMaxWidth()) { Text("Log out of this computer") }
        TextButton(onClose, modifier = Modifier.align(Alignment.End)) { Text("Close", color = C.muted) }
    }
}

// ---------------------------------------------------------------- helpers

fun initials(s: String): String {
    val w = s.trim().removePrefix("@").split(Regex("[\\s_]+")).filter { it.isNotEmpty() }
    return when {
        w.isEmpty() -> "?"
        w.size == 1 -> w[0].take(1).uppercase()
        else -> (w[0].take(1) + w[1].take(1)).uppercase()
    }
}

fun day(ts: Long) = ts / 86_400_000L + java.util.TimeZone.getDefault().getOffset(ts) / 86_400_000L

fun shortTime(ts: Long): String {
    if (ts <= 0) return ""
    val now = System.currentTimeMillis()
    return when {
        now - ts < 86_400_000L && day(now) == day(ts) -> SimpleDateFormat("HH:mm").format(Date(ts))
        now - ts < 7 * 86_400_000L -> SimpleDateFormat("EEE").format(Date(ts))
        else -> SimpleDateFormat("dd.MM.yy").format(Date(ts))
    }
}

fun humanSize(n: Long) = when {
    n >= 1 shl 20 -> "%.1f MB".format(n / 1048576.0)
    n >= 1024 -> "%d KB".format(n / 1024)
    else -> "$n B"
}

fun previewLine(m: Msg): String {
    if (m.loc != null) return "📍 Location"
    m.poll?.let { return "📊 " + it.optString("q") }
    m.call?.let { return callTitle(it) }
    m.file?.let { f -> val mime = f.optString("m"); return when { mime == "image/gif" -> "GIF"; mime.startsWith("image/") -> "🖼 Photo"; mime.startsWith("video/") -> "🎞 Video"; mime.startsWith("audio/") -> "🎤 Voice message"; else -> "📎 " + f.optString("n") } }
    return m.text.replace('\n', ' ')
}

fun callTitle(k: org.json.JSONObject): String {
    val out = k.optString("d") == "out"
    return when (k.optString("s")) {
        "ok" -> if (out) "Outgoing call" else "Incoming call"
        "missed" -> "Missed call"
        "declined" -> if (out) "Call declined" else "Declined call"
        "cancelled" -> "Cancelled call"
        "busy" -> "Line busy"
        "noanswer" -> "No answer"
        else -> "Call failed"
    }
}
