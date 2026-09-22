package org.jetbrains.skiko

/**
 * 系统文件对话框（compose-core 的 [androidx.compose.ui.window.FileDialog] 走这里）。
 *
 * Windows：comdlg32 GetOpenFileNameW / GetSaveFileNameW。
 * [ownerPlatform] 可以是 [Win32Window]，或经 [composeKnFileDialogOwnerResolver]
 * 解析的宿主窗（例如 WindowsComposeWindow）。
 */
object ComposeKNFileDialog {
    const val MODE_LOAD = 0
    const val MODE_SAVE = 1

    /** comdlg32 已链接可用（CI 只断言这个，不弹交互对话框）。 */
    fun available(): Boolean = composekn_win32_file_dialog_available()

    /**
     * @param ownerPlatform [Win32Window] / WindowsComposeWindow / null（退回最近活跃窗）
     * @param mode [MODE_LOAD] / [MODE_SAVE]
     * @param filterUtf8 `'\n'` 分隔的过滤器字段；空 = 不设过滤
     * @return 选中路径；取消 → 空列表
     */
    fun show(
        ownerPlatform: Any?,
        mode: Int,
        title: String? = null,
        initialDirectory: String? = null,
        initialFileName: String? = null,
        multiple: Boolean = false,
        filterUtf8: String? = null,
    ): List<String> {
        val owner = resolveOwner(ownerPlatform)
        return win32ShowFileDialog(
            owner = owner,
            mode = mode,
            title = title,
            initialDirectory = initialDirectory,
            initialFileName = initialFileName,
            multiple = multiple && mode == MODE_LOAD,
            filterUtf8 = filterUtf8,
        )
    }

    private fun resolveOwner(platform: Any?): Win32Window? {
        if (platform is Win32Window) return platform
        composeKnFileDialogOwnerResolver?.invoke(platform)?.let { return it }
        return lastActiveCompositionWindow
            ?: (compositionWindowRegistry.lastOrNull() as? Win32Window)
    }
}

/**
 * 宿主登记：`asPlatformWindow()` 得到的对象 → [Win32Window]。
 * compose-kn-windows 在登记 backend 时设为 `{ (it as? WindowsComposeWindow)?.nativeWindow }`。
 */
var composeKnFileDialogOwnerResolver: ((Any?) -> Win32Window?)? = null
