/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.AnnotatedString
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skiko.ClipboardImage
import org.jetbrains.skiko.WaylandClipboard

actual typealias NativeClipboard = LinuxClipboardHandle

class LinuxClipboardHandle internal constructor()

/**
 * 剪贴板里**一个条目**可以同时带多种格式（文本 + HTML + RTF + 位图）—— 这是桌面剪贴板的
 * 常规形态：老程序拿纯文本、支持富文本的拿 HTML（Word/Chrome 都是这么放的）。
 */
@Suppress("DEPRECATION")
private class LinuxPlatformClipboardManager : ClipboardManager {
    override fun getText(): AnnotatedString? = WaylandClipboard.getText()?.let { AnnotatedString(it) }

    override fun setText(annotatedString: AnnotatedString) {
        WaylandClipboard.setText(annotatedString.text)
    }

    override fun hasText(): Boolean = !WaylandClipboard.getText().isNullOrEmpty()

    override fun getClip(): ClipEntry? = platformClipEntry()

    @Suppress("GetterSetterNames")
    override fun setClip(clipEntry: ClipEntry?) = platformSetClipEntry(clipEntry)
}

internal class LinuxPlatformClipboard : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = platformClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) = platformSetClipEntry(clipEntry)

    override val nativeClipboard: NativeClipboard
        get() = LinuxClipboardHandle()
}

@Suppress("DEPRECATION")
internal actual fun createPlatformClipboardManager(): ClipboardManager =
    LinuxPlatformClipboardManager()

internal actual fun createPlatformClipboard(): Clipboard = LinuxPlatformClipboard()

/**
 * 平台剪贴板 -> [ClipEntry]：把**所有**读得出来的格式都装进同一个条目。
 *
 * 平台层是实现 richest 的那一方（上游 desktop 也是这样：`ClipEntry` 只是个壳，
 * 真正能读什么由平台的 `Transferable` / 原生格式决定）。
 */
private fun platformClipEntry(): ClipEntry? {
    val text = WaylandClipboard.getText()
    val files = WaylandClipboard.getFiles()
    val html = WaylandClipboard.getHtml()
    val rtf = WaylandClipboard.getRtf()
    val image = WaylandClipboard.getImage()
    if (text == null && files.isEmpty() && html == null && rtf == null && image == null) return null
    return ClipEntry().apply {
        plainText = text
        this.files = files
        this.html = html
        this.rtf = rtf
        this.image = image?.toImageBitmap()
    }
}

/**
 * [ClipEntry] -> 平台剪贴板：**一次事务**把条目里有的格式全放上去。
 *
 * 为什么强调"一次"：Windows 那边 `EmptyClipboard` + 多次 `SetClipboardData` 才是一个
 * 事务，分开写会把前一次的格式擦掉；Wayland 那边则是一个 `wl_data_source` 声明多个
 * MIME（目前只实现了 text/plain，见 skiko 的 `WaylandClipboard`）。
 *
 * `clipEntry == null`（上游语义是"清空剪贴板"）暂时不做任何事 —— 与之前的实现一致。
 */
private fun platformSetClipEntry(clipEntry: ClipEntry?) {
    if (clipEntry == null) return
    WaylandClipboard.setRich(
        text = clipEntry.plainText,
        html = clipEntry.html,
        rtf = clipEntry.rtf,
        image = clipEntry.image?.toClipboardImage(),
    )
}

/** 剪贴板位图（BGRA、自上而下）-> [ImageBitmap]。 */
private fun ClipboardImage.toImageBitmap(): ImageBitmap {
    val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
    return Image.makeRaster(info, pixels, width * 4).toComposeImageBitmap()
}

/** [ImageBitmap] -> 剪贴板位图（BGRA、自上而下）。Compose 的 `readPixels` 给的是 ARGB。 */
private fun ImageBitmap.toClipboardImage(): ClipboardImage {
    val argb = IntArray(width * height)
    readPixels(argb)
    val bgra = ByteArray(width * height * 4)
    for (i in argb.indices) {
        val color = argb[i]
        bgra[i * 4 + 0] = (color and 0xFF).toByte()              // B
        bgra[i * 4 + 1] = ((color shr 8) and 0xFF).toByte()      // G
        bgra[i * 4 + 2] = ((color shr 16) and 0xFF).toByte()     // R
        bgra[i * 4 + 3] = ((color shr 24) and 0xFF).toByte()     // A
    }
    return ClipboardImage(width, height, bgra)
}

actual class ClipEntry internal constructor() {
    actual val clipMetadata: ClipMetadata
        // 上游 desktop 的 clipMetadata 也是 TODO/stub（CMP-1260），这里保持同一状态：
        // 想判断"有哪些格式"就读下面这几个访问器 / 看哪个不是 null。
        get() = ClipMetadata.PlainText

    internal var plainText: String? = null

    /** 剪贴板上的**文件路径列表**（Windows 的 CF_HDROP；资源管理器里复制文件就是这个）。 */
    internal var files: List<String> = emptyList()
    internal var html: String? = null
    internal var rtf: String? = null
    internal var image: ImageBitmap? = null

    /** 纯文本（CF_UNICODETEXT）；没有则 null。 */
    @ExperimentalComposeUiApi
    fun getPlainText(): String? = plainText

    /**
     * 剪贴板上的**文件路径列表**（Windows 上是 `CF_HDROP`）。
     *
     * 典型场景：在资源管理器里 Ctrl+C 选中的文件，再到应用里粘贴 —— 拿到的就是这个
     * 列表（剪贴板上**没有**图片数据，只有路径）。
     *
     * 空列表 = 这次剪贴板里没有文件。
     *
     * ⚠ 目前只支持**读**：把文件列表**写**进剪贴板（`CF_HDROP` + 首选拖放效果那套
     * shell 语义）还没做，所以没有对应的 `withFiles` 工厂。
     */
    @ExperimentalComposeUiApi
    fun getFiles(): List<String> = files

    /** HTML（Windows 上写/读的是 CF_HTML 里的**片段**，不含格式头）。 */
    @ExperimentalComposeUiApi
    fun getHtml(): String? = html

    /** RTF（Windows 上是注册格式 "Rich Text Format"）。 */
    @ExperimentalComposeUiApi
    fun getRtf(): String? = rtf

    /**
     * 位图。
     *
     * ⚠ 这里是**拷贝**出来的 ImageBitmap（剪贴板内容随时可能被别的程序改掉），
     * 不是懒加载视图。
     */
    @ExperimentalComposeUiApi
    fun getImage(): ImageBitmap? = image

    companion object {
        @ExperimentalComposeUiApi
        fun withPlainText(text: String): ClipEntry = ClipEntry().apply {
            plainText = text
        }

        /**
         * 一个 HTML 条目。强烈建议同时给 [plainText]：不认 HTML 的程序（记事本之类）
         * 会退回纯文本 —— Word/Chrome 复制内容时都是这么放的。
         */
        @ExperimentalComposeUiApi
        fun withHtml(html: String, plainText: String? = null): ClipEntry = ClipEntry().apply {
            this.html = html
            this.plainText = plainText
        }

        /** 一个 RTF 条目（同样建议带 [plainText]）。 */
        @ExperimentalComposeUiApi
        fun withRtf(rtf: String, plainText: String? = null): ClipEntry = ClipEntry().apply {
            this.rtf = rtf
            this.plainText = plainText
        }

        /** 一个位图条目。 */
        @ExperimentalComposeUiApi
        fun withImage(image: ImageBitmap, plainText: String? = null): ClipEntry = ClipEntry().apply {
            this.image = image
            this.plainText = plainText
        }
    }
}
