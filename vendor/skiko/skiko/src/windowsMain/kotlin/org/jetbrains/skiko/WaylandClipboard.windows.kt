package org.jetbrains.skiko

/**
 * Windows shim behind the same name used by the (shared) Linux-sourced compose
 * platform code so `linuxX64Main` compose-core sources also compile for mingwX64.
 * Backed by the Win32 clipboard.
 */
object WaylandClipboard {
    fun getText(): String? {
        val w = findWindow() ?: return null
        return w.clipboard?.takeIf { it.isNotEmpty() }
    }

    fun setText(text: String) {
        findWindow()?.clipboard = text
    }

    private fun findWindow(): Win32Window? =
        (compositionWindowRegistry.firstOrNull() as? Win32Window)
}

// The Windows renderer records the most recent attached window here.
internal val compositionWindowRegistry = mutableListOf<Any>()
