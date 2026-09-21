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

    // ---- 富文本格式（HTML / RTF / 位图）----
    //
    // 名字仍叫 WaylandClipboard 是历史原因（这套平台代码最初是给 Wayland 写的，
    // 见文件头）；Windows 侧就是直接转发到 Win32 剪贴板桥。

    /** CF_HDROP：剪贴板上的文件路径列表（资源管理器里复制文件就是这个）。 */
    fun getFiles(): List<String> = findWindow()?.clipboardGetFiles() ?: emptyList()

    /** CF_HTML 的**片段**（头部的偏移解析在 C 侧）。 */
    fun getHtml(): String? = findWindow()?.clipboardGetHtml()

    /** 注册格式 "Rich Text Format"。 */
    fun getRtf(): String? = findWindow()?.clipboardGetRtf()

    /** CF_DIBV5/CF_DIB -> BGRA、自上而下。 */
    fun getImage(): ClipboardImage? = findWindow()?.clipboardGetImage()

    /**
     * **一次事务**写多个格式 —— 必须是一次：Windows 上分几次调用会把前面的擦掉
     * （`EmptyClipboard` 是事务的开始）。
     */
    fun setRich(text: String?, html: String?, rtf: String?, image: ClipboardImage?) {
        findWindow()?.clipboardSetRich(text, html, rtf, image)
    }

    private fun findWindow(): Win32Window? =
        (compositionWindowRegistry.firstOrNull() as? Win32Window)
}

// The Windows renderer records the most recent attached window here.
internal val compositionWindowRegistry = mutableListOf<Any>()
