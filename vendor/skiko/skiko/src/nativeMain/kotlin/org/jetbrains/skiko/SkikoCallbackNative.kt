@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.jetbrains.skiko

import kotlin.native.SymbolName

@Suppress("KotlinNativeMissingLibrary")
internal object SkikoCallbackNative {
    @SymbolName("skiko_register_callbacks_via_dlsym")
    external fun registerCallbacksViaDlsym()
}
