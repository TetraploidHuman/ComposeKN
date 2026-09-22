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
     *
     * [files] 非空时写入 CF_HDROP（资源管理器可粘贴的路径列表）。
     */
    fun setRich(
        text: String?,
        html: String?,
        rtf: String?,
        image: ClipboardImage?,
        files: List<String>? = null,
    ) {
        findWindow()?.clipboardSetRich(text, html, rtf, image, files)
    }

    /**
     * 多窗口时优先用最近焦点/活跃窗口（[lastActiveCompositionWindow]），
     * 否则退回注册表里最后一个仍存活的入口。
     */
    private fun findWindow(): Win32Window? =
        lastActiveCompositionWindow
            ?: (compositionWindowRegistry.lastOrNull() as? Win32Window)
}

// The Windows renderer records attached windows here（attach 加、detach 删）。
internal val compositionWindowRegistry = mutableListOf<Any>()

/**
 * 最近一次获得焦点（或最新 attach）的窗口。
 * 剪贴板 / 全局 IME 回调在多窗口下按它路由，避免总打到 firstOrNull。
 */
internal var lastActiveCompositionWindow: Win32Window? = null

/**
 * 宿主在 FocusEvent 时调用：把剪贴板/IME 的「最近活跃窗」指到 [window]。
 * （[lastActiveCompositionWindow] 本身是 internal，跨模块不能写。）
 */
fun noteLastActiveCompositionWindow(window: Win32Window?) {
    lastActiveCompositionWindow = window
}
