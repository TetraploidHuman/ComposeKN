@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.composekn.windows

import androidx.compose.ui.platform.DefaultArchitectureComponentsOwner
import androidx.compose.ui.text.input.SetSelectionCommand
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
import org.jetbrains.skiko.setWindowsImeCaretProvider
import org.jetbrains.skiko.setWindowsImeReconvertProvider
import org.jetbrains.skiko.setWindowsImeTextProvider
import org.jetbrains.skiko.win32Log
import com.composekn.windows.internal.winlog

/**
 * Windows host for real Compose UI ([CanvasLayersComposeScene] + [FrameRecomposer]).
 */
/**
 * Windows 宿主。
 *
 * [width]/[height] 的单位是 **dp（逻辑像素）**，跟 Compose 桌面的
 * `WindowState(size = DpSize(...))` 一致 —— 200% 缩放的显示器上 `1100x760(dp)`
 * 会得到 2200x1520 物理像素的客户区，UI 仍然按 1100x760dp 布局。
 */
class WindowsComposeApplication(
    private val title: String,
    private val width: Int = 960,
    private val height: Int = 640,
    /**
     * false（默认）= 系统标题栏（对齐 Compose JVM 桌面 `Window()`）；
     * true = 无边框 + Compose 自绘 CSD 标题栏（对应 `undecorated = true`）。
     */
    private val undecorated: Boolean = false,
) {
    val window: WindowsComposeWindow =
        WindowsComposeWindow(title, width, height, undecorated)
    private val inputState = WindowsInputState()

    /**
     * 当前按下的触摸触点数。
     *
     * 自检用：触摸抬起后必须归零 —— 残留触点会让后续拖动被当成多指手势，
     * 表现为「滚着滚着就不动了」。
     */
    val activeTouchCount: Int get() = inputState.activeTouchCount

    /**
     * 「没有 DOWN 的 MOVE/UP」被宿主丢弃的次数。
     *
     * 笔悬停时（不接触数字转换器）Windows 会一直发 `WM_POINTERUPDATE`，到达宿主时
     * 同样是"移动"事件；丢掉它们的原因见 `WindowsInputState.updateTouch` / HANDOVER §17.29。
     */
    val droppedUntrackedTouchCount: Int get() = inputState.droppedUntrackedTouchCount

    /** 打开后每条触摸事件都 println 一行（自检/真机排查触摸问题时用）。 */
    var debugTouchTrace: Boolean
        get() = inputState.debugTouchTrace
        set(value) {
            inputState.debugTouchTrace = value
        }
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
        ensureWindowsComposeBackendRegistered()
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
    fun setContent(withChrome: Boolean = undecorated, content: @Composable () -> Unit) {
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

    /**
     * 自检用：直接问一次「组字串里第 [charIndex] 个字符在哪」。
     *
     * 走的正是 IME 那条路（PlatformTextInputMethodRequest 的光标矩形 + 文本排版），
     * 用来断言「dwCharPos=0 的答案在拼音变长之后**不变**」（候选窗不该往右滑）。
     * 返回 [x, y, w, h]（客户区物理像素）或 null。
     */
    fun imeCaretRectForChar(charIndex: Int): IntArray? =
        textInputService.caretRectForCompositionChar(charIndex)?.let { rect ->
            intArrayOf(
                rect.left.toInt(),
                rect.top.toInt(),
                rect.width.toInt(),
                rect.height.toInt(),
            )
        }

    /**
     * 自检用：「重新转换」的范围映射（见 [WindowsTextInputService.mapReconvertRange]）。
     * 返回文档里的 `[start, end)`，映射不了返回 null。
     */
    fun mapReconvertRange(text: String, targetOffsetInText: Int, targetLen: Int): IntArray? =
        textInputService.mapReconvertRange(text, targetOffsetInText, targetLen)

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
     *
     * 仍走单窗口独占消息泵（自检依赖 frameHook + requestClose）。
     * 多窗口请用 `androidx.compose.ui.window.application { Window(...) }`。
     */
    fun run(content: @Composable () -> Unit) {
        ensureWindowsComposeBackendRegistered()
        setContent(withChrome = undecorated, content = content)

        // Compose 的光标请求（clickable -> Hand 等）转成 Win32 光标。
        platformContext.cursorSink = { kind -> window.pointerIconKind = kind }
        // 拖放发出侧：DoDragDrop 需要当前 HWND。
        platformContext.dragWindowProvider = { window.nativeWindow }

        installExclusiveImeProviders()
        // 文本会话结束 -> 取消 IME 组字（否则候选窗会赖在屏幕上）。
        textInputService.onSessionEnded = { window.imeCancelComposition() }
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
     * 装内容并挂到 [WindowsApplicationHost]（不阻塞）。
     * 供声明式 Window / 多窗口自检使用；之后由共享泵驱动。
     */
    fun attachToSharedHost(
        withChrome: Boolean = undecorated,
        onCloseRequest: (() -> Unit)? = null,
        content: @Composable () -> Unit,
    ) {
        ensureWindowsComposeBackendRegistered()
        setContent(withChrome = withChrome, content = content)
        platformContext.cursorSink = { kind -> window.pointerIconKind = kind }
        platformContext.dragWindowProvider = { window.nativeWindow }
        textInputService.onSessionEnded = { window.imeCancelComposition() }
        if (onCloseRequest != null) {
            window.onCloseRequest = onCloseRequest
        }
        window.attachToHost(onEvent = ::handleEvent)
        WindowsApplicationHost.installImeProviders(
            window = window,
            caret = { charIndex ->
                textInputService.caretRectForCompositionChar(charIndex)?.let { rect ->
                    intArrayOf(
                        rect.left.toInt(),
                        rect.top.toInt(),
                        rect.width.toInt(),
                        rect.height.toInt(),
                    )
                }
            },
            text = { textInputService.imeDocument() },
            reconvert = { text, targetOffset, targetLen ->
                textInputService.mapReconvertRange(text, targetOffset, targetLen)
            },
        )
        win32Log("app: attached to shared host")
    }

    /** 从共享宿主摘掉并释放场景（声明式 Window 离开 composition 时）。 */
    fun detachFromSharedHost() {
        WindowsApplicationHost.installImeProviders(window, null, null, null)
        window.detachFromHost()
        close()
    }

    /** 独占泵路径：直接挂全局 IME（单窗，无 Host 路由）。 */
    private fun installExclusiveImeProviders() {
        setWindowsImeCaretProvider { charIndex ->
            textInputService.caretRectForCompositionChar(charIndex)?.let { rect ->
                intArrayOf(
                    rect.left.toInt(),
                    rect.top.toInt(),
                    rect.width.toInt(),
                    rect.height.toInt(),
                )
            }
        }
        setWindowsImeTextProvider { textInputService.imeDocument() }
        setWindowsImeReconvertProvider { text, targetOffset, targetLen ->
            textInputService.mapReconvertRange(text, targetOffset, targetLen)
        }
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
            is WindowsEvent.TouchEvent -> {
                scene.dispatchWindowsTouchEvent(event, inputState)
            }
            is WindowsEvent.DragEvent -> {
                // 拖放：Compose 的判定要写回宿主，OLE 才能把光标从「可放下」改成
                // 「禁止」（见 WindowsComposeWindow.setDropAccept 的说明）。
                val accepted = scene.dispatchWindowsDragEvent(event)
                window.setDropAccept(accepted)
                winlog(
                    "drag: ${event.phase} 位置=(${event.x},${event.y}) 文件=${event.files.size} " +
                        "文本=${event.text?.length ?: 0} 接受=$accepted"
                )
            }
            is WindowsEvent.ImeStartEvent -> {
                // Compose 侧的文本会话在输入框聚焦时就开了，这里不需要额外动作。
                winlog("event: IME 组字开始")
            }
            is WindowsEvent.ImeReconvertSelectEvent -> {
                // 「重新转换」：先把原文本选中，随后那段组字（setComposingText）会替换它。
                // 不做这一步的话，组字会插到光标处 —— 原文还在，文本就重复了。
                val commands = listOf(SetSelectionCommand(event.start, event.end))
                if (!textInputService.applyEditCommands(commands)) {
                    winlog("event: 重转换选区被丢弃（没有活动文本会话）: ${event.start}..${event.end}")
                } else {
                    winlog("event: 重转换 -> 选中 ${event.start}..${event.end} 等待组字替换")
                }
            }
            is WindowsEvent.ImeCompositionEvent,
            is WindowsEvent.ImeCommitEvent,
            is WindowsEvent.ImeEndEvent -> {
                val commands = imeEditCommands(event)
                if (commands != null && !textInputService.applyEditCommands(commands)) {
                    // 组字/提交到达时没有活动文本会话（例如输入框刚失焦）：
                    // 丢掉即可 —— 与 AWT 在 disableInput 之后丢弃 InputMethodEvent 一致。
                    winlog("event: IME 事件被丢弃（没有活动文本会话）: $event")
                }
            }
            is WindowsEvent.ResizeEvent -> {
                winlog("event: resize ${event.width}x${event.height}")
                scene.density = effectiveDensity()
                window.layer.needRender()
            }
            is WindowsEvent.CloseEvent -> {
                // DO_NOTHING_ON_CLOSE：系统关窗只到这里。
                // - 共享宿主 / 声明式 Window：onCloseRequest 已在 drainEventsForHost 调过，不销毁
                // - 命令式独占 run：销毁 HWND，让 win.pump() 因 quit 退出
                if (!window.isHostAttached) {
                    window.destroy()
                }
            }
            is WindowsEvent.FocusEvent -> {
                WindowsApplicationHost.noteFocus(window, event.hasFocus)
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
