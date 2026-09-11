package androidx.compose.ui.util

actual inline fun <T> trace(sectionName: String, block: () -> T): T = block()

actual fun traceValue(tag: String, value: Long) = Unit
