package com.lumicode.editor.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.text.font.FontFamily

/** ComposeKN：暂用系统字体，后续再接内置 TTF。 */
@Composable
actual fun InstallArchiveFonts() {
    SideEffect {
        RlFonts.sans = FontFamily.SansSerif
        RlFonts.mono = FontFamily.Monospace
    }
}
