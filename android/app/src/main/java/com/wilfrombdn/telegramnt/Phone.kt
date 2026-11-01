package com.wilfrombdn.telegramnt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract.CommonDataKinds.Phone as P

/**
 * The phone's own call history and contacts, read on this phone only (nothing leaves it).
 * Tellem never links them to Tellem accounts: Tellem has no phone numbers.
 */
object Phone {
    /** One row of call history: a normal phone call ([number]) or a Tellem call ([friend]). */
    class Call(val date: Long, val incoming: Boolean, val missed: Boolean, val seconds: Int, val number: String? = null, val friend: String? = null)
    class Person(val id: Long, val name: String, val numbers: List<Pair<String, String>>)   // (number, "Mobile"/"Home"…)

    fun canReadCalls(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
    fun canReadContacts(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Recent phone calls, newest first. Blocking. */
    fun calls(ctx: Context, limit: Int = 300): List<Call> {
        if (!canReadCalls(ctx)) return emptyList()
        val out = ArrayList<Call>()
        runCatching {
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION), null, null,
                "${CallLog.Calls.DATE} DESC LIMIT $limit")?.use { c ->
                while (c.moveToNext()) {
                    val type = c.getInt(1)
                    if (type == CallLog.Calls.BLOCKED_TYPE) continue
                    val missed = type == CallLog.Calls.MISSED_TYPE || type == CallLog.Calls.REJECTED_TYPE
                    out += Call(c.getLong(2), type != CallLog.Calls.OUTGOING_TYPE, missed, c.getInt(3), number = c.getString(0) ?: "")
                }
            }
        }
        return out
    }

    /** Everyone in the phone's contacts who has a number, A to Z. Blocking. */
    fun people(ctx: Context): List<Person> {
        if (!canReadContacts(ctx)) return emptyList()
        val byId = LinkedHashMap<Long, Pair<String, MutableList<Pair<String, String>>>>()
        runCatching {
            ctx.contentResolver.query(P.CONTENT_URI, arrayOf(P.CONTACT_ID, P.DISPLAY_NAME, P.NUMBER, P.TYPE, P.LABEL), null, null,
                "${P.DISPLAY_NAME} COLLATE LOCALIZED ASC")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val num = c.getString(2) ?: continue
                    val label = P.getTypeLabel(ctx.resources, c.getInt(3), c.getString(4)).toString()
                    val e = byId.getOrPut(id) { (c.getString(1) ?: num) to ArrayList() }
                    // the same number saved twice (spaces, +1) shows once
                    if (e.second.none { android.telephony.PhoneNumberUtils.compare(it.first, num) }) e.second += num to label
                }
            }
        }
        return byId.map { (id, e) -> Person(id, e.first, e.second) }
    }
}
