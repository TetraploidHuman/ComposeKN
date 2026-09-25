/*
 * Copyright 2021 The Android Open Source Project
 * Copyright 2026 The ComposeKN Authors (linuxX64 / mingw port)
 *
 * Port of Notification.desktop.kt — no AWT.
 */

package androidx.compose.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Creates a [Notification] that is remembered across compositions.
 */
@Composable
fun rememberNotification(
    title: String,
    message: String,
    type: Notification.Type = Notification.Type.None,
): Notification = remember(title, message, type) {
    Notification(title, message, type)
}

/**
 * Notification shown via the platform tray / notification center.
 *
 * Attach a [Tray] and call [TrayState.sendNotification].
 */
class Notification(
    val title: String,
    val message: String,
    val type: Type = Type.None,
) {
    fun copy(
        title: String = this.title,
        message: String = this.message,
        type: Type = this.type,
    ) = Notification(title, message, type)

    override fun toString(): String =
        "Notification(title=$title, message=$message, type=$type)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as Notification
        return title == other.title && message == other.message && type == other.type
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + message.hashCode()
        result = 31 * result + type.hashCode()
        return result
    }

    enum class Type {
        None,
        Info,
        Warning,
        Error,
    }
}
