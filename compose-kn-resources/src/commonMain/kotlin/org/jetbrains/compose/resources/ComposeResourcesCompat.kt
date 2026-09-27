@file:Suppress("PackageDirectoryMismatch")

/**
 * 官方 `org.jetbrains.compose.resources` 包名对齐层。
 *
 * 官方 components-resources 无 linuxX64/mingwX64；消费者可：
 * 1. 直接用 `com.composekn.resources.*`，或
 * 2. import 本包（API 形态对齐），并在 settings 里
 *    `substitute(module("org.jetbrains.compose.components:components-resources"))
 *       .using(module("com.composekn:compose-kn-resources:<ver>"))`
 */
package org.jetbrains.compose.resources

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.composekn.resources.Font as ComposeKnFont
import com.composekn.resources.FontFromFile as ComposeKnFontFromFile
import com.composekn.resources.FontResource as ComposeKnFontResource
import com.composekn.resources.fontFamilyOf as composeKnFontFamilyOf
import com.composekn.resources.fontResource as composeKnFontResource

typealias FontResource = ComposeKnFontResource

fun fontResource(
    identity: String,
    relativePath: String,
    developmentPath: String? = null,
): FontResource = composeKnFontResource(identity, relativePath, developmentPath)

fun Font(
    resource: FontResource,
    weight: FontWeight = FontWeight.Normal,
    style: FontStyle = FontStyle.Normal,
): Font = ComposeKnFont(resource, weight, style)

fun Font(
    identity: String,
    bytes: ByteArray,
    weight: FontWeight = FontWeight.Normal,
    style: FontStyle = FontStyle.Normal,
): Font = ComposeKnFont(identity, bytes, weight, style)

fun FontFromFile(
    path: String,
    identity: String = path.substringAfterLast('/').substringAfterLast('\\')
        .substringBeforeLast('.'),
    weight: FontWeight = FontWeight.Normal,
    style: FontStyle = FontStyle.Normal,
): Font = ComposeKnFontFromFile(path, identity, weight, style)

fun fontFamilyOf(vararg fonts: Font) = composeKnFontFamilyOf(*fonts)
