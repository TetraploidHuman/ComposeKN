@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.jetbrains.skiko

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned

/**
 * Linux：xdg-desktop-portal FileChooser（libdbus）。
 * [ownerPlatform] 暂忽略（无 parent_window 绑定）。
 */
object ComposeKNFileDialog {
    const val MODE_LOAD = 0
    const val MODE_SAVE = 1

    fun available(): Boolean = composekn_linux_file_dialog_available()

    fun show(
        ownerPlatform: Any?,
        mode: Int,
        title: String? = null,
        initialDirectory: String? = null,
        initialFileName: String? = null,
        multiple: Boolean = false,
        filterUtf8: String? = null,
    ): List<String> {
        @Suppress("UNUSED_PARAMETER")
        ownerPlatform
        // 两段式：先探测长度，再取结果（对齐 Win32）。
        val needed = callNative(
            mode = mode,
            title = title,
            initialDirectory = initialDirectory,
            initialFileName = initialFileName,
            multiple = multiple,
            filterUtf8 = filterUtf8,
            buffer = null,
            bufferSize = 0,
        )
        if (needed <= 0) return emptyList()
        val buf = ByteArray(needed + 1)
        val written = buf.usePinned { pinned ->
            callNative(
                mode = mode,
                title = title,
                initialDirectory = initialDirectory,
                initialFileName = initialFileName,
                multiple = multiple,
                filterUtf8 = filterUtf8,
                buffer = pinned.addressOf(0),
                bufferSize = buf.size,
            )
        }
        if (written <= 0) return emptyList()
        val text = buf.decodeToString(0, written)
        return text.split('\n').filter { it.isNotEmpty() }
    }

    private fun callNative(
        mode: Int,
        title: String?,
        initialDirectory: String?,
        initialFileName: String?,
        multiple: Boolean,
        filterUtf8: String?,
        buffer: CPointer<ByteVar>?,
        bufferSize: Int,
    ): Int {
        return title.orEmpty().useCString { t ->
            initialDirectory.orEmpty().useCString { d ->
                initialFileName.orEmpty().useCString { n ->
                    filterUtf8.orEmpty().useCString { f ->
                        composekn_linux_file_dialog(
                            mode,
                            t,
                            d,
                            n,
                            multiple,
                            f,
                            buffer,
                            bufferSize,
                        )
                    }
                }
            }
        }
    }
}

@SymbolName("composekn_linux_file_dialog_available")
internal external fun composekn_linux_file_dialog_available(): Boolean

@SymbolName("composekn_linux_file_dialog")
internal external fun composekn_linux_file_dialog(
    mode: Int,
    title: CPointer<ByteVar>?,
    initialDir: CPointer<ByteVar>?,
    initialName: CPointer<ByteVar>?,
    allowMultiple: Boolean,
    filterUtf8: CPointer<ByteVar>?,
    buffer: CPointer<ByteVar>?,
    bufferSize: Int,
): Int

private inline fun <R> String.useCString(block: (CPointer<ByteVar>) -> R): R {
    val bytes = encodeToByteArray()
    val buf = ByteArray(bytes.size + 1)
    bytes.copyInto(buf)
    return buf.usePinned { block(it.addressOf(0)) }
}
