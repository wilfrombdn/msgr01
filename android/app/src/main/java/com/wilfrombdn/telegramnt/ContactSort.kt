package com.wilfrombdn.telegramnt

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

// The sort button next to the magnifying glass on the Phone window's Contacts tab:
// pick the order (names or emails, A to Z or Z to A) and tick "Group by company".

private val SORTS = listOf("name_az" to "Name, A to Z", "name_za" to "Name, Z to A", "email_az" to "Email, A to Z", "email_za" to "Email, Z to A")

internal fun MainActivity.showSortMenu() {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    var dialog: android.app.Dialog? = null
    lateinit var render: () -> Unit
    fun row(label: String, mark: String, markColor: Int, onClick: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(12), 0, dp(12)); background = pressable(); isClickable = true
        addView(tv(label, 16f, white), LinearLayout.LayoutParams(0, -2, 1f))
        addView(tv(mark, 18f, markColor, true))
        setOnClickListener { onClick() }
    }
    render = {
        col.removeAllViews()
        val cur = UiPrefs.contactSort(this)
        SORTS.forEach { (key, label) ->
            col.addView(row(label, if (key == cur) "●" else "○", if (key == cur) blue else dim) { UiPrefs.setContactSort(this, key); showChats(); render() })
        }
        col.addView(View(this).apply { setBackgroundColor(divider) }, LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(4); bottomMargin = dp(4) })
        val on = UiPrefs.groupByCompany(this)
        col.addView(row("Group by company", if (on) "☑" else "☐", if (on) blue else dim) { UiPrefs.setGroupByCompany(this, !on); showChats(); render() })
    }
    render()
    popup("Sort contacts", null, col, "Done" to white, dialogOut = { dialog = it })
}
