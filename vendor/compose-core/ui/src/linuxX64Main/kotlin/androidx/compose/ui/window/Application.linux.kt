/*
 * Copyright 2021 The Android Open Source Project
 * Copyright 2026 The ComposeKN Authors (linuxX64 / mingw port)
 *
 * Port of Application.desktop.kt: Swing → SkikoDispatchers.Main + 原生共享泵。
 */

@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package androidx.compose.ui.window

import androidx.compose.runtime.Applier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.GlobalSnapshotManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import kotlin.concurrent.Volatile
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jetbrains.skiko.SkikoDispatchers
import org.jetbrains.skiko.currentNanoTime

/**
 * Compose 应用入口（对齐 Desktop [application]）。
 *
 * 阻塞当前线程直到应用结束。内部用 [ComposeNativeWindowBackend.runApplicationPump]
 * 驱动 Win32/Wayland 消息与 [SkikoDispatchers.Main] 任务队列。
 *
 * @param exitProcessOnExit 结束后是否 `exitProcess(0)`（默认 true，加快进程退出）。
 */
fun application(
    exitProcessOnExit: Boolean = true,
    content: @Composable ApplicationScope.() -> Unit,
) {
    runBlocking {
        awaitApplication {
            content()
        }
    }
    if (exitProcessOnExit) {
        exitProcess(0)
    }
}

/**
 * 在已有 [CoroutineScope] 里启动应用（不阻塞调用方）。
 * 注意：全局 daemon 协程不会保活进程，main 里请用 [application]。
 */
fun CoroutineScope.launchApplication(
    content: @Composable ApplicationScope.() -> Unit,
): Job = launch {
    awaitApplication(content = content)
}

/**
 * 挂起版应用入口。通常由 [application] 通过 runBlocking 调用。
 *
 * 当没有任何活动 composition（无 Window / 无 LaunchedEffect）且
 * [ApplicationScope.exitApplication] 已调用（或最后一扇窗离开）时结束。
 */
suspend fun awaitApplication(
    content: @Composable ApplicationScope.() -> Unit,
) {
    val backend = ComposeNativeWindowBackendRegistry.requireBackend()
    backend.initMainThread()

    val finished = FinishedFlag()
    var isOpen by mutableStateOf(true)

    val applicationScope = object : ApplicationScope {
        override fun exitApplication() {
            isOpen = false
            backend.wakeApplicationPump()
        }
    }

    val mainJob = Job()
    val scope = CoroutineScope(SkikoDispatchers.Main + mainJob)
    val globalSnapshotRegistration = GlobalSnapshotManager.register(SkikoDispatchers.Main)
    val recomposer = Recomposer(SkikoDispatchers.Main)

    scope.launch {
        recomposer.runRecomposeAndApplyChanges()
    }

    scope.launch {
        val composition = Composition(ApplicationApplier(), recomposer)
        try {
            composition.setContent {
                if (isOpen) {
                    CompositionLocalProvider(
                        LocalDensity provides ApplicationGlobalDensity,
                        LocalLayoutDirection provides LayoutDirection.Ltr,
                    ) {
                        applicationScope.content()
                    }
                }
            }
            // 与 Desktop 相同：setContent 后 close，join 等到无活动 composition
            recomposer.close()
            recomposer.join()
        } finally {
            composition.dispose()
            globalSnapshotRegistration?.close()
            finished.value = true
            backend.wakeApplicationPump()
        }
    }

    // 共享泵在主线程阻塞；Main 队列任务在每次 flush 时执行。
    backend.runApplicationPump { !finished.value }

    mainJob.cancel()
}

/** @Volatile 不能标在局部变量上，用这个小盒子。 */
private class FinishedFlag {
    @Volatile
    var value: Boolean = false
}

/**
 * Scope used by [application] / [awaitApplication] / [launchApplication].
 */
@Stable
interface ApplicationScope {
    /**
     * Close all windows created inside the application and cancel launched effects.
     */
    fun exitApplication()
}

/** 应用层默认 density（无显示器查询时 1.0；各 Window 内会按 DPI 覆盖）。 */
private val ApplicationGlobalDensity = Density(1f)

private class ApplicationApplier : Applier<Any> {
    override val current: Any = Unit
    override fun down(node: Any) = Unit
    override fun up() = Unit
    override fun insertTopDown(index: Int, instance: Any) {
        if (instance !is Unit) {
            throw IllegalStateException(
                "Composable content may not be added directly into ApplicationScope",
            )
        }
    }
    override fun insertBottomUp(index: Int, instance: Any) {
        if (instance !is Unit) {
            throw IllegalStateException(
                "Composable content may not be added directly into ApplicationScope",
            )
        }
    }
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun clear() = Unit
    override fun onEndChanges() = Unit
}

/**
 * 无显示器同步的帧钟：yield 让出，便于共享泵 flush 其它任务。
 * 动画应放在 Window 内容里（那里有真实 vsync 节流）。
 */
@Suppress("unused")
private object YieldFrameClock : MonotonicFrameClock {
    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        yield()
        return onFrame(currentNanoTime())
    }
}
