package com.wilfrombdn.telegramnt

// Request codes for startActivityForResult / requestPermissions in MainActivity. Each must be unique.
// (Two of them were once equal, which sent a picked contact to the call-role handler.)

internal const val PICK_PHOTO = 7
internal const val PICK_ATTACH_PHOTO = 8
internal const val PICK_ATTACH_FILE = 9
internal const val TAKE_MEDIA = 12
internal const val SNAP = 61
internal const val CREATE_RECOVERY = 10
internal const val OPEN_RECOVERY = 11
internal const val SAVE_IMAGE = 13
internal const val MIC_FOR_CALL = 14
internal const val MIC_FOR_VOICE = 15
internal const val LOCATION_FOR_SHARE = 16
internal const val SCAN_QR = 17
internal const val CONFIRM_IDENTITY = 18
internal const val ROUND_VIDEO = 19   // was 18, the same as CONFIRM_IDENTITY, so recorded round videos were dropped
internal const val TEXT_ROLE = 20
internal const val CALL_ROLE = 21
internal const val CALL_PERM = 26
internal const val ADD_CONTACT_PERM = 27
internal const val PICK_CONTACT = 28   // was 21, the same as CALL_ROLE, so a picked contact was handled as a call-role result
internal const val TEXT_IMAGE = 22
internal const val TEXT_PERMS = 23
internal const val CALL_LOG_PERM = 24
internal const val CONTACTS_PERM = 25
