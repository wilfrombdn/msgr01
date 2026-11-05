package tellem

import androidx.compose.ui.graphics.Color

/** Telegram Desktop's dark look; the group rail, user panel and reaction pills are the only Discord touches. */
object C {
    val rail = Color(0xFF0B121A)
    val list = Color(0xFF17212B)
    val panel = Color(0xFF0E1621)
    val chat = Color(0xFF0E1621)
    val field = Color(0xFF242F3D)
    val search = Color(0xFF242F3D)
    val hover = Color(0xFF202B36)
    val selected = Color(0xFF2B5278)
    val blurple = Color(0xFF5288C1)      // accent (Telegram blue)
    val blurpleDark = Color(0xFF3E6A9B)
    val green = Color(0xFF4DCD5E)
    val red = Color(0xFFEC3942)
    val yellow = Color(0xFFF0B232)
    val text = Color(0xFFF5F5F5)
    val muted = Color(0xFF8D9AA8)
    val faint = Color(0xFF6D7F8F)
    val bubbleIn = Color(0xFF182533)
    val bubbleOut = Color(0xFF2B5278)
    val divider = Color(0xFF0A1017)

    private val avatarColors = listOf(0xFFE17076, 0xFFFAA774, 0xFFA695E7, 0xFF7BC862, 0xFF6EC9CB, 0xFF65AADD, 0xFFEE7AAE)
    fun avatar(seed: String) = Color(avatarColors[(seed.hashCode() and 0x7fffffff) % avatarColors.size])
}
