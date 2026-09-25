/*
 * Copyright 2021 The Android Open Source Project
 * Copyright 2026 The ComposeKN Authors (linuxX64 / mingw port)
 *
 * Port of Tray.desktop.kt for Kotlin/Native (Win32 Shell_NotifyIcon /
 * Linux StatusNotifierItem + notify-send).
 */

package androidx.compose.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.painter.Painter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import org.jetbrains.skiko.ComposeKNTray
import org.jetbrains.skiko.TrayMenuItem

/**
 * `true` if this build can show a tray icon and/or deliver tray notifications.
 *
 * Windows: Shell_NotifyIcon. Linux: StatusNotifierWatcher 或 notify-send。
 */
val isTraySupported: Boolean get() = ComposeKNTray.available()

/**
 * Adds a system tray icon when supported.
 *
 * @param icon Rasterized to 16×16 BGRA（Painter→HICON / IconPixmap）
 * @param state Hoisted [TrayState] for [TrayState.sendNotification]
 * @param tooltip Hover text
 * @param onAction Primary action (Windows: double-click; Linux: Activate)
 * @param menu Context menu
 */
@Composable
fun ApplicationScope.Tray(
    icon: Painter,
    state: TrayState = rememberTrayState(),
    tooltip: String? = null,
    onAction: () -> Unit = {},
    menu: @Composable MenuScope.() -> Unit = {},
) {
    if (!isTraySupported) {
        DisposableEffect(Unit) {
            println(
                "Tray is not supported on the current platform. " +
                    "Use the global property `isTraySupported` to check.",
            )
            onDispose {}
        }
        return
    }

    val currentOnAction by rememberUpdatedState(onAction)
    val roots = remember { MenuCollector() }
    CompositionLocalProvider(LocalMenuCollector provides roots) {
        MenuScope().menu()
    }
    val latestNodes by rememberUpdatedState(roots.toList())
    val coroutineScope = rememberCoroutineScope()
    val iconPixels = remember(icon) { icon.toTrayIconBgra() }

    SideEffect {
        ComposeKNTray.setTooltip(tooltip)
        ComposeKNTray.setOnAction { currentOnAction() }
        ComposeKNTray.setMenu(latestNodes.toTrayMenuItems())
        ComposeKNTray.setIcon(iconPixels.first, iconPixels.second, iconPixels.third)
    }

    DisposableEffect(state) {
        ComposeKNTray.ensureCreated(tooltip)
        ComposeKNTray.setOnAction { currentOnAction() }
        ComposeKNTray.setMenu(latestNodes.toTrayMenuItems())
        ComposeKNTray.setIcon(iconPixels.first, iconPixels.second, iconPixels.third)
        val job = state.notificationFlow
            .onEach { n ->
                ComposeKNTray.showNotification(n.title, n.message, n.type.ordinal)
            }
            .launchIn(coroutineScope)
        onDispose {
            job.cancel()
            ComposeKNTray.destroy()
        }
    }
}

@Composable
fun rememberTrayState() = remember { TrayState() }

/**
 * Hoisted tray controller. [sendNotification] only delivers while a [Tray]
 * is composed and listening.
 */
class TrayState {
    private val notificationChannel = Channel<Notification>(Channel.RENDEZVOUS)

    val notificationFlow: Flow<Notification>
        get() = notificationChannel.receiveAsFlow()

    fun sendNotification(notification: Notification) {
        notificationChannel.trySend(notification)
    }
}

private fun List<NativeMenuNode>.toTrayMenuItems(): List<TrayMenuItem> {
    fun convert(node: NativeMenuNode): TrayMenuItem = when (node) {
        is NativeMenuNode.Separator -> TrayMenuItem(isSeparator = true)
        is NativeMenuNode.Item -> TrayMenuItem(
            text = node.text,
            enabled = node.enabled,
            onClick = node.onClick,
        )
        is NativeMenuNode.Menu -> TrayMenuItem(
            text = node.text,
            enabled = node.enabled,
            children = node.children.map { convert(it) },
        )
    }
    return map { convert(it) }
}
