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
import androidx.compose.ui.text.AnnotatedString
import org.jetbrains.skiko.WaylandClipboard

actual typealias NativeClipboard = LinuxClipboardHandle

class LinuxClipboardHandle internal constructor()

@Suppress("DEPRECATION")
private class LinuxPlatformClipboardManager : ClipboardManager {
    override fun getText(): AnnotatedString? = WaylandClipboard.getText()?.let { AnnotatedString(it) }

    override fun setText(annotatedString: AnnotatedString) {
        WaylandClipboard.setText(annotatedString.text)
    }

    override fun hasText(): Boolean = !WaylandClipboard.getText().isNullOrEmpty()

    override fun getClip(): ClipEntry? = WaylandClipboard.getText()?.let { ClipEntry.withPlainText(it) }

    @Suppress("GetterSetterNames")
    override fun setClip(clipEntry: ClipEntry?) {
        val text = clipEntry?.plainText
        if (text != null) {
            WaylandClipboard.setText(text)
        }
    }
}

internal class LinuxPlatformClipboard : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? =
        WaylandClipboard.getText()?.let { ClipEntry.withPlainText(it) }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val text = clipEntry?.plainText
        if (text != null) {
            WaylandClipboard.setText(text)
        }
    }

    override val nativeClipboard: NativeClipboard
        get() = LinuxClipboardHandle()
}

@Suppress("DEPRECATION")
internal actual fun createPlatformClipboardManager(): ClipboardManager =
    LinuxPlatformClipboardManager()

internal actual fun createPlatformClipboard(): Clipboard = LinuxPlatformClipboard()

actual class ClipEntry internal constructor() {
    actual val clipMetadata: ClipMetadata
        get() = ClipMetadata.PlainText

    internal var plainText: String? = null

    @ExperimentalComposeUiApi
    fun getPlainText(): String? = plainText

    companion object {
        @ExperimentalComposeUiApi
        fun withPlainText(text: String): ClipEntry = ClipEntry().apply {
            plainText = text
        }
    }
}
