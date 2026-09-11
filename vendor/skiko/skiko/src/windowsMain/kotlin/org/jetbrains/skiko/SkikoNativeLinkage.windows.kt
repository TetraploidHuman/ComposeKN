@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.jetbrains.skiko

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.staticCFunction
import org.jetbrains.skia.impl.skikoKtCallBooleanCallback
import org.jetbrains.skia.impl.skikoKtCallIntCallback
import org.jetbrains.skia.impl.skikoKtCallNativePtrCallback
import org.jetbrains.skia.impl.skikoKtCallVoidCallback
import org.jetbrains.skia.impl.skikoKtDisposeCallback
import kotlin.native.SymbolName

@SymbolName("skiko_initCallbacks")
private external fun skikoInitCallbacks(
    callBoolean: COpaquePointer?,
    callInt: COpaquePointer?,
    callNativePointer: COpaquePointer?,
    callVoid: COpaquePointer?,
    dispose: COpaquePointer?,
)

internal actual fun registerSkikoNativeCallbacks() {
    retainCallbackSymbols(
        ::skikoKtCallVoidCallback,
        ::skikoKtCallBooleanCallback,
        ::skikoKtCallIntCallback,
        ::skikoKtCallNativePtrCallback,
        ::skikoKtDisposeCallback,
    )
    skikoInitCallbacks(
        callBoolean = staticCFunction<COpaquePointer?, Boolean> { ptr ->
            skikoKtCallBooleanCallback(ptr!!)
        },
        callInt = staticCFunction<COpaquePointer?, Int> { ptr ->
            skikoKtCallIntCallback(ptr!!)
        },
        callNativePointer = staticCFunction<COpaquePointer?, Long> { ptr ->
            skikoKtCallNativePtrCallback(ptr!!)
        },
        callVoid = staticCFunction<COpaquePointer?, Unit> { ptr ->
            skikoKtCallVoidCallback(ptr!!)
        },
        dispose = staticCFunction<COpaquePointer?, Unit> { ptr ->
            skikoKtDisposeCallback(ptr!!)
        },
    )
}

private fun retainCallbackSymbols(vararg callbacks: Any) {
    check(callbacks.size == 5)
}
