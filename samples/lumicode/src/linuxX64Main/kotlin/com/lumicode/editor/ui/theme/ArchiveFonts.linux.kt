package com.lumicode.editor.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.composekn.resources.Font
import com.lumicode.editor.resources.Res

/**
 * ComposeKN：通过 compose-kn-resources 加载与 Desktop 相同的 Noto / JetBrains Mono
 *（`Res.font.*`，字体文件在 exe 旁 `composeResources/font/` 或 developmentPath）。
 */
@Composable
actual fun InstallArchiveFonts() {
    val sans = FontFamily(
        Font(Res.font.noto_sans_regular, FontWeight.Normal),
        Font(Res.font.noto_sans_bold, FontWeight.Bold),
    )
    val mono = FontFamily(
        Font(Res.font.jbmono_regular, FontWeight.Normal),
        Font(Res.font.jbmono_medium, FontWeight.Medium),
        Font(Res.font.jbmono_bold, FontWeight.Bold),
    )
    SideEffect {
        RlFonts.sans = sans
        RlFonts.mono = mono
    }
}
