@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.composekn.windows

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
import org.jetbrains.skiko.flushMainUIDispatcher
import org.jetbrains.skiko.initWindowsMainThread
import org.jetbrains.skiko.win32Log
import com.composekn.windows.internal.winlog

/**
 * Windows host for real Compose UI ([CanvasLayersComposeScene] + [FrameRecomposer]).
 */
class WindowsComposeApplication(
    private val title: String,
    private val width: Int = 960,
    private val height: Int = 640,
) {
    val window: WindowsComposeWindow = WindowsComposeWindow(title, width, height)
    private val inputState = WindowsInputState()
    private val archComponentsOwner = DefaultArchitectureComponentsOwner(enforceMainThread = false)
    private val textInputService = WindowsTextInputService()
    private val platformContext = WindowsPlatformContext(archComponentsOwner, textInputService)

    private val frameRecomposer = FrameRecomposer(SkikoDispatchers.Main) {
        window.layer.needRender()
    }
    private val sceneRenderingScope = SingleComposeSceneRenderingScope {
        window.layer.needRender()
    }

    private val scene = CanvasLayersComposeScene(
        frameRecomposer = frameRecomposer,
        platformContext = platformContext,
        invalidateLayout = sceneRenderingScope::onSceneInvalidation,
        invalidateDraw = sceneRenderingScope::onSceneInvalidation,
    )

    private var closed = false

    init {
        // 必须尽早标记「当前线程 = UI 主线程」。
        // Skiko 的 WindowsMainDispatcher 只有在 isWindowsMainThread() 为真时才会把
        // 任务直接 inline 执行；否则一律进队列，而队列只能由 flushMainUIDispatcher()
        // 排空 —— 而 flush() 自己也会先检查 isWindowsMainThread()。
        // 漏掉这一句的后果（离屏/无窗口驱动时必现）：
        //   所有 launch/LaunchedEffect/snapshotFlow 都不执行，
        //   典型表现是鼠标滚轮完全不动（MouseWheelScrollingLogic 靠一个
        //   launch 出来的协程从 channel 取 delta）—— 这是自检
        //   `interaction/wheel-scroll` 抓到的真实 bug。
        initWindowsMainThread()
    }

    // =====================================================================
    // 自动化测试 / 离屏渲染接口
    //
    // 以下成员原本是 run() 的内部实现。抽出来是为了让
    // samples/windows-demo 的 `--selftest` 能在**不开窗口**的情况下驱动真实的
    // Compose 场景：setContent() -> renderFrame(离屏 raster surface)
    // -> dispatchEvent(合成输入) -> 再渲染一帧并断言像素。
    // 这样渲染管线（Skia 光栅化、文本排版、重组、布局）和输入派发都能被自动断言，
    // 而不是只能靠人眼看窗口。详见 HANDOVER §14。
    // =====================================================================

    /** 渲染用的 Compose 场景（`size`/`density` 可写）。 */
    val composeScene: ComposeScene get() = scene

    /** 测试用：覆盖 layout density（null = 跟随窗口 DPI）。 */
    var densityOverride: Density? = null

    /**
     * 装载内容但**不**进入窗口消息循环。
     *
     * @param withChrome false 时不套 CSD 标题栏（纯内容渲染，便于像素断言）
     */
    fun setContent(withChrome: Boolean = true, content: @Composable () -> Unit) {
        // 先给场景一个合理的初始尺寸，再装内容。
        //
        // 否则在「第一次 renderFrame 之前」到达的指针事件会让 sendPointerEvent
        // 在 scene.size == 0 的状态下触发 measureAndLayout，根节点拿到无界约束，
        // 内容里任何 verticalScroll / LazyColumn 都会抛
        //   IllegalStateException: Vertically scrollable component was measured with
        //   an infinity maximum height constraints ...
        // （真机上窗口创建后消息泵里通常已经排着鼠标 Enter/Move，因此表现为启动即崩。）
        val initialSize = initialSceneSize()
        scene.size = initialSize
        scene.density = effectiveDensity()
        // 第一次组合之前就要有正确的容器尺寸（Popup/Dialog 可能首帧就存在）
        platformContext.updateContainerSize(initialSize, scene.density)
        scene.setContent {
            if (withChrome) {
                WindowsWindowChrome(title = title, window = window, content = content)
            } else {
                content()
            }
        }
        archComponentsOwner.enableSavedStateHandles()
        archComponentsOwner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        window.layer.renderDelegate = object : SkikoRenderDelegate {
            override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
                renderFrame(canvas, width, height, nanoTime)
            }
        }
    }

    /**
     * 渲染一帧到给定画布。
     *
     * 窗口模式下由 [SkikoRenderDelegate.onRender] 调用；离屏模式下由测试直接调用。
     */
    fun renderFrame(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
        // 每帧同步 density：初始 scene.density 是默认值(1.0)，
        // 只在收到 ResizeEvent 时才设，会导致第一帧（HiDPI 下）UI 特别小，
        // 直到窗口被缩放触发重组才恢复。
        val density = effectiveDensity()
        // 容器尺寸必须在 scene.size / measure **之前**同步：Popup/Dialog 的
        // measure policy 直接读 `LocalWindowInfo.current.containerSize` 来定位和裁剪，
        // 缺了它弹层会被夹到 (0,0)（见 WindowsWindowInfo 的注释）。
        platformContext.updateContainerSize(IntSize(width, height), density)
        scene.density = density
        scene.size = IntSize(width, height)
        with(sceneRenderingScope) {
            scene.render(frameRecomposer, canvas.asComposeCanvas(), nanoTime)
        }
    }

    /** 派发一个（真实或合成的）平台事件。 */
    fun dispatchEvent(event: WindowsEvent) = handleEvent(event)

    /** 推进 Main dispatcher / 重组队列（离屏驱动时每帧调用一次）。 */
    fun pumpDispatchers() = flushMainUIDispatcher()

    /** 释放场景资源（[run] 结束时调用）。 */
    fun close() {
        if (closed) return
        closed = true
        archComponentsOwner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        archComponentsOwner.viewModelStore.clear()
        scene.close()
        frameRecomposer.close()
    }

    private fun effectiveDensity(): Density =
        densityOverride ?: Density(window.layer.contentScale)

    /** 窗口尚未 attach（真实尺寸未知）时，按构造参数给的逻辑尺寸 × DPI 估算场景尺寸。 */
    private fun initialSceneSize(): IntSize {
        val scale = window.layer.contentScale
        return IntSize(
            (window.logicalWidth * scale).toInt().coerceAtLeast(1),
            (window.logicalHeight * scale).toInt().coerceAtLeast(1),
        )
    }

    /**
     * Run the application with the given Compose content.
     */
    fun run(content: @Composable () -> Unit) {
        setContent(withChrome = true, content = content)

        win32Log("app: scene + content ready, starting window loop")
        try {
            window.run(onEvent = ::handleEvent)
        } catch (t: Throwable) {
            win32Log("app: EXCEPTION from window.run -> ${t::class.simpleName}: ${t.message}")
            throw t
        }
        win32Log("app: window loop finished normally")

        close()
    }

    /**
     * Handle Windows events.
     */
    private fun handleEvent(event: WindowsEvent) {
        when (event) {
            is WindowsEvent.KeyEvent -> {
                scene.dispatchWindowsKeyEvent(event, inputState)
            }
            is WindowsEvent.MouseMoveEvent -> {
                inputState.updateModifiers(
                    isShiftPressed = event.isShiftPressed,
                    isCtrlPressed = event.isCtrlPressed,
                    isAltPressed = event.isAltPressed,
                )
                scene.dispatchWindowsMouseEvent(event, inputState)
            }
            is WindowsEvent.MouseButtonEvent -> {
                inputState.updateModifiers(
                    isShiftPressed = event.isShiftPressed,
                    isCtrlPressed = event.isCtrlPressed,
                    isAltPressed = event.isAltPressed,
                )
                scene.dispatchWindowsMouseButtonEvent(event, inputState)
            }
            is WindowsEvent.MouseWheelEvent -> {
                inputState.updateModifiers(
                    isShiftPressed = event.isShiftPressed,
                    isCtrlPressed = event.isCtrlPressed,
                    isAltPressed = event.isAltPressed,
                )
                scene.dispatchWindowsMouseWheelEvent(event, inputState)
            }
            is WindowsEvent.ResizeEvent -> {
                winlog("event: resize ${event.width}x${event.height}")
                scene.density = effectiveDensity()
                window.layer.needRender()
            }
            is WindowsEvent.CloseEvent -> {
                // Window close handled by message loop
            }
            is WindowsEvent.FocusEvent -> {
                // Focus change handled
            }
            is WindowsEvent.PaintEvent -> {
                window.layer.renderImmediately()
            }
            is WindowsEvent.MoveEvent -> {
                // Move event handled
            }
        }
    }
}
