package com.wilfrombdn.telegramnt

/** Which full-screen page [MainActivity] is showing. Back-press, refreshes and incoming events all branch on it. */
enum class Screen {
    NONE, WELCOME, SIGNUP, LOGIN, LOCK,
    CHATS, CHAT, GROUP, NEW_GROUP, GROUP_SETTINGS, NEW_CHAT, ADD_CONTACT, VIEWER,
    TEXT_LIST, TEXT_THREAD, TEXT_SPAM, NEW_TEXT, SNAP_SEND, TEXT_TOOLS,
    SETTINGS, PROFILE, STORAGE, APPEARANCE, CROP,
}
