/*
 * Copyright 2021 The Android Open Source Project
 * Copyright 2026 The ComposeKN Authors (linuxX64 / mingw port)
 */

package androidx.compose.ui.window

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp

/** Constructs an [WindowPosition.Absolute] from [x] and [y] [Dp] values. */
fun WindowPosition(x: Dp, y: Dp) = WindowPosition.Absolute(x, y)

/** Constructs an [WindowPosition.Aligned] from [alignment] value. */
fun WindowPosition(alignment: Alignment) = WindowPosition.Aligned(alignment)

/**
 * Position of the window on the screen in [Dp].
 */
@Immutable
sealed class WindowPosition {
    @Stable
    abstract val x: Dp

    @Stable
    abstract val y: Dp

    /** `true` if coordinates are known; `false` for [PlatformDefault] / [Aligned]. */
    @Stable
    abstract val isSpecified: Boolean

    /**
     * Initial platform-dependent position (cascade / last display).
     * Only meaningful before the window is shown.
     */
    object PlatformDefault : WindowPosition() {
        override val x: Dp get() = Dp.Unspecified
        override val y: Dp get() = Dp.Unspecified
        override val isSpecified: Boolean get() = false

        @Stable
        override fun toString() = "PlatformDefault"
    }

    /**
     * Align within the work area when first shown.
     */
    @Immutable
    class Aligned(val alignment: Alignment) : WindowPosition() {
        override val x: Dp get() = Dp.Unspecified
        override val y: Dp get() = Dp.Unspecified
        override val isSpecified: Boolean get() = false

        fun copy(alignment: Alignment = this.alignment) = Aligned(alignment)

        @Stable
        override fun toString() = "Aligned($alignment)"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as Aligned
            return alignment == other.alignment
        }

        override fun hashCode(): Int = alignment.hashCode()
    }

    /**
     * Absolute position on the screen.
     */
    @Immutable
    class Absolute(override val x: Dp, override val y: Dp) : WindowPosition() {
        override val isSpecified: Boolean get() = true

        @Stable
        operator fun component1(): Dp = x

        @Stable
        operator fun component2(): Dp = y

        fun copy(x: Dp = this.x, y: Dp = this.y) = Absolute(x, y)

        @Stable
        override fun toString() = "Absolute($x, $y)"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as Absolute
            return x == other.x && y == other.y
        }

        override fun hashCode(): Int {
            var result = x.hashCode()
            result = 31 * result + y.hashCode()
            return result
        }
    }
}
