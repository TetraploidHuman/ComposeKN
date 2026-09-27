package com.composekn.resources

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font as PlatformFont

/**
 * 从 [FontResource] 创建 Compose [Font]（对齐官方 `org.jetbrains.compose.resources.Font`）。
 *
 * 底层：读字节 → [PlatformFont] / `LoadedFont` → Skia `FontMgr.makeFromData`。
 */
fun Font(
    resource: FontResource,
    weight: FontWeight = FontWeight.Normal,
    style: FontStyle = FontStyle.Normal,
): Font {
    val bytes = ResourceLoads.loadBytes(resource)
        ?: error(
            "ComposeKN font not found: ${resource.relativePath} " +
                "(tried exeDir/composeResources, fonts/, COMPOSEKN_RESOURCES, developmentPath)",
        )
    return PlatformFont(resource.identity, bytes, weight, style)
}

/** 直接从内存字节创建字体（无需资源管线）。 */
fun Font(
    identity: String,
    bytes: ByteArray,
    weight: FontWeight = FontWeight.Normal,
    style: FontStyle = FontStyle.Normal,
): Font = PlatformFont(identity, bytes, weight, style)

/** 从文件系统路径加载（native 无 JVM `Font(File)`，用这个）。 */
fun FontFromFile(
    path: String,
    identity: String = path.substringAfterLast('/').substringAfterLast('\\')
        .substringBeforeLast('.'),
    weight: FontWeight = FontWeight.Normal,
    style: FontStyle = FontStyle.Normal,
): Font {
    val bytes = ResourceLoads.readFile(path)
        ?: error("Cannot read font file: $path")
    return PlatformFont(identity, bytes, weight, style)
}

/** 便捷：若干 [Font] → [FontFamily]。 */
fun fontFamilyOf(vararg fonts: Font): FontFamily = FontFamily(*fonts)
