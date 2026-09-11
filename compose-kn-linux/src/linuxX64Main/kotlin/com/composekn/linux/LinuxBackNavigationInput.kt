package com.composekn.linux

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.navigationevent.NavigationEventInput

internal class LinuxBackNavigationInput : NavigationEventInput() {
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        return when (event.key) {
            Key.Escape,
            Key.Back,
            -> {
                dispatchOnBackCompleted()
                true
            }
            else -> false
        }
    }
}
