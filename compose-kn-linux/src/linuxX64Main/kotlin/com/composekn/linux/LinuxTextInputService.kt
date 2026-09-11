@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.composekn.linux

import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.input.CommitTextCommand
import kotlinx.coroutines.awaitCancellation
import org.jetbrains.skiko.WaylandImeEvent
import org.jetbrains.skiko.WaylandWindow

/**
 * Bridges Compose text input to the Wayland `zwp_text_input_v3` IME protocol.
 *
 * When a [TextField] is focused, [startInputMethod] enables the text input and reports the
 * surrounding text + cursor rectangle to the compositor (so the IME, e.g. fcitx5, can anchor
 * its candidate window). Committed text arrives as [WaylandImeEvent.Commit] via [handleImeEvent]
 * and is applied through the field's [onEditCommand].
 *
 * v1 is commit-based: preedit (composing) text is tracked by the IME and not mirrored into the
 * field, and [WaylandImeEvent.Delete] is a no-op (there is no preedit in the field to remove).
 */
internal class LinuxTextInputService(
    private val window: WaylandWindow,
    private val contentScale: () -> Float,
) {
    var activeRequest: PlatformTextInputMethodRequest? = null
        private set

    suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
        activeRequest = request
        window.textInputSetEnabled(true)
        updateImeContext(request)
        try {
            awaitCancellation()
        } finally {
            if (activeRequest === request) {
                activeRequest = null
                window.textInputSetEnabled(false)
            }
        }
    }

    /** Dispatch a text-input-v3 event to the focused field. Called from the event loop (main thread). */
    fun handleImeEvent(event: WaylandImeEvent) {
        when (event) {
            is WaylandImeEvent.Commit -> {
                imeLog("commit '${event.text}'")
                if (handleCommit(event.text)) {
                    refreshContext()
                }
            }
            is WaylandImeEvent.Preedit -> {
                // v1: composing text is displayed by the IME; not mirrored into the field.
                if (event.text.isNotEmpty()) imeLog("preedit '${event.text}'")
            }
            is WaylandImeEvent.Delete -> {
                // v1: no-op — the preedit is not placed in the field, so nothing to remove.
                imeLog("delete before=${event.beforeLength} after=${event.afterLength}")
            }
            is WaylandImeEvent.Done -> {
                // Batch boundary; nothing to flush in v1.
            }
            is WaylandImeEvent.Enter -> {
                // Text-input focus is driven by startInputMethod cancellation/enable.
                imeLog("enter (text input enabled)")
            }
            is WaylandImeEvent.Leave -> {
                // Focus loss is handled by startInputMethod cancellation.
                imeLog("leave (text input disabled)")
            }
        }
    }

    private fun imeLog(message: String) {
        println("composekn: ime $message")
    }

    private fun handleCommit(text: String): Boolean {
        val request = activeRequest ?: return false
        if (text.isEmpty()) return false
        request.onEditCommand(listOf(CommitTextCommand(text, 1)))
        return true
    }

    private fun refreshContext() {
        activeRequest?.let { updateImeContext(it) }
    }

    private fun updateImeContext(request: PlatformTextInputMethodRequest) {
        val value = request.value()
        window.textInputSetSurroundingText(
            text = value.text,
            cursor = value.selection.max,
            anchor = value.selection.min,
        )
        val rect = request.focusedRectInRoot()
        val scale = contentScale()
        window.textInputSetCursorRectangle(
            x = ((rect?.left ?: 0f) * scale).toInt(),
            y = ((rect?.top ?: 0f) * scale).toInt(),
            width = (((rect?.width ?: 8f).coerceAtLeast(1f)) * scale).toInt(),
            height = (((rect?.height ?: 18f).coerceAtLeast(1f)) * scale).toInt(),
        )
    }
}
