@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.composekn.linux

import androidx.compose.ui.platform.DefaultArchitectureComponentsOwner

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.SingleComposeSceneRenderingScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.enableSavedStateHandles
import org.jetbrains.skiko.SkikoDispatchers
import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkikoRenderDelegate
import org.jetbrains.skiko.WaylandEvent
import org.jetbrains.skiko.WaylandEventType
import org.jetbrains.skiko.WaylandWindow
import org.jetbrains.skiko.initLinuxMainThread

/**
 * Wayland host for real Compose UI ([CanvasLayersComposeScene] + [FrameRecomposer]).
 *
 * - [run]：独占单窗循环（旧 demo）
 * - [attachToSharedHost] / [detachFromSharedHost]：声明式 `application { Window }` 多窗
 */
class LinuxComposeApplication(
    private val title: String,
    private val width: Int = 960,
    private val height: Int = 640,
    /**
     * false（默认）= 尽量用 SSD / 不套自绘标题栏（与 [LinuxWindowChrome] 的
     * usesServerDecoration 分支一致）；true = 无边框风格，始终套 CSD。
     *
     * 注意：Wayland 是否真有系统装饰由 compositor 决定；[LinuxWindowChrome]
     * 在无 SSD 时总会画标题栏。
     */
    val undecorated: Boolean = false,
) {
    val window: WaylandWindow get() = composeWindow.window
    val composeWindow = LinuxComposeWindow(title, width, height)
    private val inputState = WaylandInputState()
    private val archComponentsOwner = DefaultArchitectureComponentsOwner(enforceMainThread = false)
    private val textInputService = LinuxTextInputService(
        window = composeWindow.window,
        contentScale = { composeWindow.layer.contentScale },
    )
    private val backNavigationInput = LinuxBackNavigationInput()
    private val platformContext = LinuxPlatformContext(archComponentsOwner, textInputService)

    private val frameRecomposer = FrameRecomposer(SkikoDispatchers.Main) {
        composeWindow.layer.needRender()
    }
    private val sceneRenderingScope = SingleComposeSceneRenderingScope {
        composeWindow.layer.needRender()
    }

    private val scene = CanvasLayersComposeScene(
        frameRecomposer = frameRecomposer,
        platformContext = platformContext,
        invalidateLayout = sceneRenderingScope::onSceneInvalidation,
        invalidateDraw = sceneRenderingScope::onSceneInvalidation,
    )

    private var closed = false
    private var savedStateHandlesEnabled = false

    init {
        initLinuxMainThread()
        ensureLinuxComposeBackendRegistered()
    }

    /** 渲染用的 Compose 场景。 */
    val composeScene: ComposeScene get() = scene

    /**
     * 装载内容（可重复调用；声明式 Window 的 DisposableEffect 会调）。
     *
     * @param withChrome true 时套 [LinuxWindowChrome]（对应 undecorated / 无 SSD）
     */
    fun setContent(withChrome: Boolean = undecorated, content: @Composable () -> Unit) {
        val initialSize = initialSceneSize()
        scene.size = initialSize
        scene.density = Density(composeWindow.layer.contentScale)
        scene.setContent {
            // withChrome / 无 SSD → CSD；有 SSD 且非 undecorated → 纯内容
            if (withChrome || !composeWindow.window.usesServerDecoration) {
                LinuxWindowChrome(title = title, window = composeWindow.window, content = content)
            } else {
                content()
            }
        }
        if (!savedStateHandlesEnabled) {
            archComponentsOwner.enableSavedStateHandles()
            archComponentsOwner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            archComponentsOwner.navigationEventDispatcher.addInput(backNavigationInput)
            savedStateHandlesEnabled = true
        }

        composeWindow.layer.renderDelegate = object : SkikoRenderDelegate {
            override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
                renderFrame(canvas, width, height, nanoTime)
            }
        }
    }

    fun renderFrame(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
        val density = Density(composeWindow.layer.contentScale)
        scene.density = density
        scene.size = IntSize(width, height)
        with(sceneRenderingScope) {
            scene.render(frameRecomposer, canvas.asComposeCanvas(), nanoTime)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        if (savedStateHandlesEnabled) {
            archComponentsOwner.navigationEventDispatcher.removeInput(backNavigationInput)
            archComponentsOwner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            archComponentsOwner.viewModelStore.clear()
        }
        scene.close()
        frameRecomposer.close()
    }

    private fun initialSceneSize(): IntSize {
        val scale = composeWindow.layer.contentScale
        val w = composeWindow.window.width.takeIf { it > 0 } ?: width
        val h = composeWindow.window.height.takeIf { it > 0 } ?: height
        return IntSize(
            (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1),
        )
    }

    /**
     * 独占单窗口循环（旧 demo）。多窗口请用
     * `androidx.compose.ui.window.application { Window(...) }`。
     */
    fun run(content: @Composable () -> Unit) {
        ensureLinuxComposeBackendRegistered()
        // 独占路径：无 SSD 时 withChrome 由 setContent 内逻辑自动补 CSD
        setContent(withChrome = undecorated, content = content)

        composeWindow.window.onImeEvent = { imeEvent -> textInputService.handleImeEvent(imeEvent) }
        composeWindow.run(onEvent = ::handleEvent)

        close()
    }

    /**
     * 装内容并挂到 [LinuxApplicationHost]（不阻塞）。
     *
     * @param content null 时只挂宿主、不装 Compose 内容（声明式 createWindow 用）
     */
    fun attachToSharedHost(
        withChrome: Boolean = undecorated,
        onCloseRequest: (() -> Unit)? = null,
        content: (@Composable () -> Unit)? = null,
    ) {
        ensureLinuxComposeBackendRegistered()
        if (content != null) {
            setContent(withChrome = withChrome, content = content)
        } else {
            val initialSize = initialSceneSize()
            scene.size = initialSize
            scene.density = Density(composeWindow.layer.contentScale)
            composeWindow.layer.renderDelegate = object : SkikoRenderDelegate {
                override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
                    renderFrame(canvas, width, height, nanoTime)
                }
            }
        }
        composeWindow.window.onImeEvent = { imeEvent -> textInputService.handleImeEvent(imeEvent) }
        if (onCloseRequest != null) {
            composeWindow.onCloseRequest = onCloseRequest
        }
        composeWindow.attachToHost(onEvent = ::handleEvent)
        println("composekn: app attached to shared host")
    }

    /** 从共享宿主摘掉并释放场景（声明式 Window 离开 composition 时）。 */
    fun detachFromSharedHost() {
        composeWindow.detachFromHost()
        close()
    }

    private fun handleEvent(event: WaylandEvent) {
        if (event.type == WaylandEventType.Scale) {
            scene.density = Density(event.scale)
            return
        }
        if (event.type == WaylandEventType.Key) {
            println("composekn: key keyCode=${event.keyCode} keysym=${event.keysym} pressed=${event.pressed}")
            scene.dispatchWaylandKeyEvent(event, inputState, backNavigationInput)
            return
        }
        scene.dispatchWaylandEvent(event, composeWindow.layer.contentScale, inputState)
    }
}
