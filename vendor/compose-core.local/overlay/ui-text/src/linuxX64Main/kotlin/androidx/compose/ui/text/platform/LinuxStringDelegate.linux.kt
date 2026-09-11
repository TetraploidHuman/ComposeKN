package androidx.compose.ui.text.platform

import androidx.compose.ui.text.PlatformStringDelegate
import androidx.compose.ui.text.intl.Locale

private class LinuxStringDelegate : PlatformStringDelegate {
    override fun toUpperCase(string: String, locale: Locale): String = string.uppercase()
    override fun toLowerCase(string: String, locale: Locale): String = string.lowercase()
    override fun capitalize(string: String, locale: Locale): String =
        string.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    override fun decapitalize(string: String, locale: Locale): String =
        string.replaceFirstChar { it.lowercase() }
}

internal actual fun ActualStringDelegate(): PlatformStringDelegate = LinuxStringDelegate()
