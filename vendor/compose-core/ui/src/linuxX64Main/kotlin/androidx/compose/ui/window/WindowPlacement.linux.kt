/*
 * Copyright 2021 The Android Open Source Project
 * Copyright 2026 The ComposeKN Authors (linuxX64 / mingw port)
 */

package androidx.compose.ui.window

/**
 * Describes how the window is placed on the screen.
 */
enum class WindowPlacement {
    /** Window doesn't occupy all available space and can be moved/resized. */
    Floating,

    /** Maximized, excluding taskbar/dock insets. */
    Maximized,

    /** Fullscreen, including areas normally reserved for system UI. */
    Fullscreen,
}
