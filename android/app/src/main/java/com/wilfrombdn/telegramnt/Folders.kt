package com.wilfrombdn.telegramnt

import android.content.ClipData
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

// Chat folders and the text folders (Archive, Codes, Spam), including drag-and-drop into folders.

internal fun MainActivity.folderRow(f: Folder, unread: Int, count: Int): View {
    val row = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(14), dp(8))
        background = pressable()
        isClickable = true
        tag = "folder:${f.id}"
    }
    row.addView(icon(R.drawable.ic_folder, white, 26, 14).apply { background = circle(panel2) })
    val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
    mid.addView(tv(f.name, 16.5f, white, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
    mid.addView(tv(if (count == 0) T("Hold a chat and drag it here") else TP(count, "chat", "chats"), 14f, dim).apply { setPadding(0, dp(2), 0, 0) })
    row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
    if (unread > 0 && !f.open) row.addView(tv("$unread", 12.5f, white, true).apply {
        gravity = Gravity.CENTER; minWidth = dp(22); setPadding(dp(6), dp(1), dp(6), dp(1)); background = round(blue, 11)
    })
    // the arrow: points down when open, right when closed
    row.addView(icon(R.drawable.ic_arrow, dim, 20, 10).apply { rotation = if (f.open) 90f else 0f })
    row.setOnClickListener { f.open = !f.open; Messenger.settingsChanged(); showChats() }
    row.setOnLongClickListener { folderMenu(f); true }
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tag = "folder:${f.id}"
        addView(row, LinearLayout.LayoutParams(-1, -2))
        addView(View(context).apply { setBackgroundColor(divider) }, LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(76) })
    }
}

internal fun MainActivity.folderMenu(f: Folder) {
    longPressMenu(null, null, listOf(
        T("Rename folder") to {
            val box = field(T("Folder name"), f.name)
            popup(T("Rename folder"), null, box, T("Cancel") to dim, T("Save") to white) { i ->
                if (i == 1) box.text.toString().trim().take(40).takeIf { it.isNotEmpty() }?.let { f.name = it; Messenger.settingsChanged(); showChats() }
            }
        },
        T("Delete folder") to {
            popup(T("Delete \"${f.name}\"?"), T("Only the folder goes. The chats inside are NOT deleted — they move back to your main list."), null,
                T("Cancel") to dim, T("Delete") to red) { i -> if (i == 1) { Messenger.folders.remove(f); Messenger.settingsChanged(); showChats() } }
        },
    ))
}

internal fun MainActivity.newFolder() {
    val box = field(T("Folder name"))
    popup(T("New folder"), T("Group chats together, like Family or Work. Then hold a chat and drag it onto the folder."), box,
        T("Cancel") to dim, T("Create") to white) { i ->
        val n = box.text.toString().trim().take(40)
        if (i == 1 && n.isNotEmpty()) {
            Messenger.folders.add(Folder(java.util.UUID.randomUUID().toString(), n))
            Messenger.settingsChanged(); showChats()
        }
    }
}

internal fun MainActivity.startChatDrag(row: View, key: String) {
    row.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    dragging = true
    dragRow = row
    if (!row.startDragAndDrop(ClipData.newPlainText("chat", key), View.DragShadowBuilder(row), key, 0)) dragging = false
}

internal fun MainActivity.isInside(v: View, g: ViewGroup): Boolean {
    var p = v.parent
    while (p != null) { if (p === g) return true; p = p.parent }
    return false
}

/** Drop a dragged chat on a folder (or a chat inside one) to file it there; anywhere else takes it out. */
internal fun MainActivity.enableFolderDrops(list: LinearLayout, scroll: ScrollView) {
    var lit: View? = null
    fun folderAt(y: Float): Pair<String?, View?> {
        for (i in 0 until list.childCount) {
            val v = list.getChildAt(i)
            if (y >= v.top && y < v.bottom) {
                val t = v.tag as? String ?: return null to null
                return when {
                    t.startsWith("folder:") -> t.removePrefix("folder:") to v
                    t.startsWith("in:") -> t.removePrefix("in:") to v
                    else -> null to null
                }
            }
        }
        return null to null
    }
    fun light(v: View?) {
        if (v === lit) return
        lit?.foreground = null
        v?.foreground = android.graphics.drawable.ColorDrawable(Color.parseColor("#334EA4F6"))
        lit = v
    }
    list.setOnDragListener { _, e ->
        when (e.action) {
            android.view.DragEvent.ACTION_DRAG_STARTED -> e.localState is String
            android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                light(folderAt(e.y).second)
                // nudge the list when you drag near the top/bottom edge
                val onScreen = e.y - scroll.scrollY
                if (onScreen < dp(64)) scroll.scrollBy(0, -dp(14)) else if (onScreen > scroll.height - dp(64)) scroll.scrollBy(0, dp(14))
                true
            }
            android.view.DragEvent.ACTION_DRAG_EXITED -> { light(null); true }
            android.view.DragEvent.ACTION_DROP -> {
                val key = e.localState as? String ?: return@setOnDragListener false
                // let go on the same chat (didn't really drag it): show its menu instead of moving it
                val self = dragRow?.let { r -> (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { v -> v === r || (v as? ViewGroup)?.let { g -> isInside(r, g) } == true } }
                if (self != null && e.y >= self.top && e.y < self.bottom) { dragging = false; list.post { chatRowMenu(key) }; return@setOnDragListener true }
                val target = folderAt(e.y).first
                val fs = Messenger.folders
                fs.forEach { it.items.remove(key) }
                fs.firstOrNull { it.id == target }?.let { it.items.add(key); it.open = true }
                Messenger.settingsChanged()
                true
            }
            android.view.DragEvent.ACTION_DRAG_ENDED -> {
                light(null)
                if (dragging) { dragging = false; list.post { if (screen == Screen.CHATS) showChats() } }
                true
            }
            else -> true
        }
    }
}

/** Where Archive / Codes / Spam & blocked live: "top" or "bottom" of the texts, the side "menu", or their own "tab". */
internal fun MainActivity.folderPlace() = UiPrefs.folderPlace(this)

internal fun MainActivity.folderCounts() = Triple(Texts.threads.count { it.archived }, Texts.threads.count { it.code }, Texts.threads.count { it.spam })

/** The Archive, Codes and Spam & blocked rows (each hidden while empty). */
internal fun MainActivity.folderRows(list: LinearLayout) {
    val (a, c, s) = folderCounts()
    sectionRow("📦", T("Archive"), a) { showTextSection("archive") }?.let { list.addView(it) }
    sectionRow("🔑", T("Codes"), c) { showTextSection("codes") }?.let { list.addView(it) }
    sectionRow("🚫", T("Spam & blocked"), s) { showTextSection("spam") }?.let { list.addView(it) }
}

/** The "Folders" tab of the Phone screen. */
internal fun MainActivity.addFolderTab(list: LinearLayout) {
    folderRows(list)
    val (a, c, s) = folderCounts()
    if (a + c + s == 0) list.addView(tv(T("Nothing here yet.\n\nArchived texts, login codes and spam collect in this tab."), 15.5f, dim)
        .apply { gravity = Gravity.CENTER; setPadding(dp(32), dp(64), dp(32), dp(32)); setLineSpacing(0f, 1.2f) })
}
