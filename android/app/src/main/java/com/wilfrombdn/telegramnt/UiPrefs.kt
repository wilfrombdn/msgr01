package com.wilfrombdn.telegramnt

import android.content.Context

/** The small on/off choices in Settings that live in the phone's own preferences file ("ui"), not in the account. */
object UiPrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ui", 0)

    /** Screenshots and the recent-apps preview of chat screens (off by default). */
    fun screenshots(ctx: Context) = prefs(ctx).getBoolean("shots", false)
    fun setScreenshots(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("shots", on).apply()

    /** Reply to a notification from the lock screen without unlocking (on by default; off with app lock). */
    fun lockReply(ctx: Context) = prefs(ctx).getBoolean("lockreply", true)
    fun setLockReply(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("lockreply", on).apply()

    /** Keep Saved Messages in the Tellem window only, instead of also in the Phone window's texts. */
    fun savedTellemOnly(ctx: Context) = prefs(ctx).getBoolean("savedTellemOnly", false)
    fun setSavedTellemOnly(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("savedTellemOnly", on).apply()

    /** Slide and grow animations (on by default). Off: screens, tabs and the round button change instantly. */
    fun animations(ctx: Context) = prefs(ctx).getBoolean("animations", true)
    fun setAnimations(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("animations", on).apply()

    /** Contacts order: "name_az" (default), "name_za", "email_az" or "email_za"; and whether they are grouped by company. */
    fun contactSort(ctx: Context) = prefs(ctx).getString("contactSort", "name_az") ?: "name_az"
    fun setContactSort(ctx: Context, sort: String) = prefs(ctx).edit().putString("contactSort", sort).apply()
    fun groupByCompany(ctx: Context) = prefs(ctx).getBoolean("groupCompany", false)
    fun setGroupByCompany(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("groupCompany", on).apply()

    /** Where the text folders (Archive, Codes, Spam) show: "top", "bottom", "menu" or "tab". */
    fun folderPlace(ctx: Context) = prefs(ctx).getString("folders", "top") ?: "top"
    fun setFolderPlace(ctx: Context, place: String) = prefs(ctx).edit().putString("folders", place).apply()
}
