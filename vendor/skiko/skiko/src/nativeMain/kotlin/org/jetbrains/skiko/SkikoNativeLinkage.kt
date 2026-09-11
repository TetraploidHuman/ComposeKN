package org.jetbrains.skiko

/**
 * Retains Skiko @CName callback symbols and registers them with native C++ interop.
 */
@InternalSkikoApi
object SkikoNativeLinkage {
    private var registered = false

    fun ensureLinked() {
        if (registered) return
        registered = true
        registerSkikoNativeCallbacks()
    }
}

internal expect fun registerSkikoNativeCallbacks()
