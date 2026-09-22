package org.jetbrains.skiko

/**
 * Linux/Wayland stub：与 Windows 同名，供 compose-core 的 linuxX64Main 共享源码编译。
 * 文件对话框尚未接 portal / GtkFileChooser；调用即视为取消。
 */
object ComposeKNFileDialog {
    const val MODE_LOAD = 0
    const val MODE_SAVE = 1

    fun available(): Boolean = false

    fun show(
        ownerPlatform: Any?,
        mode: Int,
        title: String? = null,
        initialDirectory: String? = null,
        initialFileName: String? = null,
        multiple: Boolean = false,
        filterUtf8: String? = null,
    ): List<String> {
        // 避免未使用参数告警，同时保持与 Windows 签名一致。
        @Suppress("UNUSED_EXPRESSION")
        ownerPlatform
        @Suppress("UNUSED_EXPRESSION")
        mode
        @Suppress("UNUSED_EXPRESSION")
        title
        @Suppress("UNUSED_EXPRESSION")
        initialDirectory
        @Suppress("UNUSED_EXPRESSION")
        initialFileName
        @Suppress("UNUSED_EXPRESSION")
        multiple
        @Suppress("UNUSED_EXPRESSION")
        filterUtf8
        return emptyList()
    }
}
