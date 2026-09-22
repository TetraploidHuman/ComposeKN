/*
 * Copyright 2026 The ComposeKN Authors (linuxX64 / mingw port)
 *
 * 系统文件对话框（打开 / 保存）。
 * Windows：comdlg32 GetOpenFileNameW / GetSaveFileNameW（Wine 友好）。
 * Linux：skiko stub（取消 → 空列表）；后续可接 xdg-desktop-portal。
 */

package androidx.compose.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import org.jetbrains.skiko.ComposeKNFileDialog

/** 打开（Load）或保存（Save）。 */
enum class FileDialogMode { Load, Save }

/**
 * 文件类型过滤器（映射为 COMDLG 的 `描述\0*.ext;*.ext2\0`）。
 *
 * @param description 过滤器显示名（如「图片」）
 * @param extensions 扩展名列表，可带或不带前导点（`png` / `.png`）
 */
data class FileDialogFilter(
    val description: String,
    val extensions: List<String>,
)

/**
 * 声明式文件对话框：进入 composition 后在下一帧弹出一次（不阻塞重组）。
 *
 * 取消或失败时 [onCloseRequest] 收到 [emptyList]。
 * 用完后应让本 composable 离开 composition（典型写法：`if (open) FileDialog(...)`）。
 *
 * @param parent 归属窗；传 [ComposeNativeWindowHandle]，内部经
 *   `asPlatformWindow()` → WindowsComposeWindow.nativeWindow；null = 最近活跃窗
 */
@Composable
fun FileDialog(
    onCloseRequest: (paths: List<String>) -> Unit,
    mode: FileDialogMode = FileDialogMode.Load,
    title: String? = null,
    initialDirectory: String? = null,
    initialFileName: String? = null,
    multiple: Boolean = false,
    filters: List<FileDialogFilter> = emptyList(),
    parent: ComposeNativeWindowHandle? = null,
) {
    val latestOnClose = rememberUpdatedState(onCloseRequest)
    LaunchedEffect(Unit) {
        val paths = runNativeFileDialog(
            ownerPlatform = parent?.asPlatformWindow(),
            mode = mode,
            title = title,
            initialDirectory = initialDirectory,
            initialFileName = initialFileName,
            multiple = multiple,
            filters = filters,
        )
        latestOnClose.value(paths)
    }
}

/**
 * 命令式：打开文件（可多选）。取消 → 空列表。
 *
 * **UI 线程同步阻塞**（模态对话框自带消息循环）。
 */
fun FrameWindowScope.openFileDialog(
    title: String? = null,
    initialDirectory: String? = null,
    initialFileName: String? = null,
    multiple: Boolean = false,
    filters: List<FileDialogFilter> = emptyList(),
): List<String> = runNativeFileDialog(
    ownerPlatform = window.asPlatformWindow(),
    mode = FileDialogMode.Load,
    title = title,
    initialDirectory = initialDirectory,
    initialFileName = initialFileName,
    multiple = multiple,
    filters = filters,
)

/**
 * 命令式：保存文件。取消 → null。
 *
 * **UI 线程同步阻塞**。
 */
fun FrameWindowScope.saveFileDialog(
    title: String? = null,
    initialDirectory: String? = null,
    initialFileName: String? = null,
    filters: List<FileDialogFilter> = emptyList(),
): String? = runNativeFileDialog(
    ownerPlatform = window.asPlatformWindow(),
    mode = FileDialogMode.Save,
    title = title,
    initialDirectory = initialDirectory,
    initialFileName = initialFileName,
    multiple = false,
    filters = filters,
).firstOrNull()

/** 把 [FileDialogFilter] 列表编成 C 桥认的 `'\n'` 分隔串。 */
fun buildFileDialogFilterUtf8(filters: List<FileDialogFilter>): String? {
    if (filters.isEmpty()) return null
    return buildString {
        for (f in filters) {
            append(f.description)
            append('\n')
            append(
                f.extensions.joinToString(";") { ext ->
                    val e = ext.trim().removePrefix(".")
                    if (e == "*" || e == "*.*") "*.*" else "*.$e"
                },
            )
            append('\n')
        }
        // 末尾多一个 '\n' → C 侧双 NUL 终结
        append('\n')
    }
}

private fun runNativeFileDialog(
    ownerPlatform: Any?,
    mode: FileDialogMode,
    title: String?,
    initialDirectory: String?,
    initialFileName: String?,
    multiple: Boolean,
    filters: List<FileDialogFilter>,
): List<String> = ComposeKNFileDialog.show(
    ownerPlatform = ownerPlatform,
    mode = if (mode == FileDialogMode.Save) ComposeKNFileDialog.MODE_SAVE else ComposeKNFileDialog.MODE_LOAD,
    title = title,
    initialDirectory = initialDirectory,
    initialFileName = initialFileName,
    multiple = multiple,
    filterUtf8 = buildFileDialogFilterUtf8(filters),
)
