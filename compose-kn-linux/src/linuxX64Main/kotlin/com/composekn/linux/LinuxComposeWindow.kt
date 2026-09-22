package com.composekn.linux

import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import org.jetbrains.skiko.WaylandEvent
import org.jetbrains.skiko.WaylandEventType
import org.jetbrains.skiko.WaylandWindow
import org.jetbrains.skiko.flushMainUIDispatcher
import org.jetbrains.skiko.initLinuxMainThread

/**
 * Wayland window + SkiaLayer with vsync frame callbacks and input events.
 *
 * 支持两种驱动：
 * - [run]：独占 poll 循环（旧 demo / 自检）
 * - [attachToHost] + [LinuxApplicationHost.runSharedPump]：多窗口共享泵
 */
class LinuxComposeWindow(
    private val title: String,
    private val width: Int = 960,
    private val height: Int = 640,
) {
    val window = WaylandWindow(title, width, height)
    val layer = SkiaLayer()

    /**
     * 系统关窗回调（对齐 Desktop DO_NOTHING_ON_CLOSE）。
     * xdg_toplevel_close / requestClose 只调此回调，**不** destroy。
     */
    var onCloseRequest: (() -> Unit)? = null

    /** 原生几何变化提示（resize / maximize）；声明式 Window 写回 WindowState。 */
    internal var onGeometryHint: (() -> Unit)? = null

    /** true = DialogWindow；v1 软模态未实现（Wayland 无 EnableWindow 等价物）。 */
    internal var isDialogWindow: Boolean = false

    var resizable: Boolean = true
    var alwaysOnTop: Boolean = false

    /** 是否已挂到 [LinuxApplicationHost]（共享泵）；与独占 [run] 互斥。 */
    var isHostAttached: Boolean = false
        private set

    private var hostEventHandler: ((WaylandEvent) -> Unit)? = null
    private var layerAttached: Boolean = false
    private var destroyed: Boolean = false

    fun setTitle(title: String) {
        window.setTitle(title)
    }

    /**
     * 创建 attach SkiaLayer，并注册到共享宿主。**不**进入阻塞循环。
     */
    fun attachToHost(onEvent: (WaylandEvent) -> Unit) {
        check(!isHostAttached) { "already attached to LinuxApplicationHost" }
        check(!destroyed) { "window already destroyed" }
        initLinuxMainThread()
        ensureLayerAttached()
        hostEventHandler = onEvent
        isHostAttached = true
        window.onEvent = { event ->
            when (event.type) {
                WaylandEventType.Frame -> layer.renderImmediately()
                WaylandEventType.Scale -> {
                    onGeometryHint?.invoke()
                    onEvent(event)
                }
                else -> onEvent(event)
            }
        }
        LinuxApplicationHost.register(this)
        layer.needRender()
        println("composekn: attachToHost ready ${window.width}x${window.height}")
    }

    /**
     * 共享泵：poll + 关窗回调。返回 false 表示 display 失败。
     * Close：只调 [onCloseRequest]，**不** destroy。
     */
    fun pollAndDispatchForHost(): Boolean {
        if (destroyed || !isHostAttached) return false
        if (!window.poll()) return false
        if (window.consumeCloseRequested()) {
            onCloseRequest?.invoke()
        }
        if (window.consumeResized()) {
            layer.needRender()
            onGeometryHint?.invoke()
        }
        return true
    }

    /**
     * Host 在 poll 失败并 [LinuxApplicationHost.unregister] 后调用：
     * 只清宿主标志，**不** destroy（留给 [detachFromHost] / dispose）。
     */
    internal fun markHostDetachedAfterPollFailure() {
        isHostAttached = false
        hostEventHandler = null
        window.onEvent = null
    }

    /** 从共享宿主摘掉并销毁 Wayland surface。 */
    fun detachFromHost() {
        if (!isHostAttached && !layerAttached && destroyed) return
        println("composekn: detachFromHost")
        if (isHostAttached) {
            LinuxApplicationHost.unregister(this)
        }
        isHostAttached = false
        hostEventHandler = null
        window.onEvent = null
        if (layerAttached) {
            layer.detach()
            layerAttached = false
        }
        destroyNative()
    }

    /**
     * 独占消息循环（单窗口遗留路径）。
     *
     * 系统关窗默认退出本循环并 destroy（若未另设 [onCloseRequest] 且未挂宿主）。
     */
    fun run(onEvent: (WaylandEvent) -> Unit) {
        check(!isHostAttached) { "use shared host or exclusive run, not both" }
        initLinuxMainThread()
        ensureLayerAttached()
        check(layer.renderDelegate != null) {
            "SkiaLayer.renderDelegate must be set before LinuxComposeWindow.run()"
        }
        // 命令式默认：关窗 = 退出循环（随后 destroy）
        if (onCloseRequest == null) {
            onCloseRequest = { /* exclusive: exit loop via consumeCloseRequested */ }
        }
        window.onEvent = { event ->
            when (event.type) {
                WaylandEventType.Frame -> layer.renderImmediately()
                else -> onEvent(event)
            }
        }
        layer.needRender()
        var running = true
        while (running && !destroyed) {
            if (!window.poll()) {
                running = false
                break
            }
            if (window.consumeCloseRequested()) {
                onCloseRequest?.invoke()
                // 独占：关窗即退出（对齐旧 poll==false）；共享宿主路径不会进这里
                if (!isHostAttached) {
                    running = false
                }
            }
            flushMainUIDispatcher()
            if (window.consumeResized()) {
                layer.needRender()
                onGeometryHint?.invoke()
            }
        }
        if (layerAttached) {
            layer.detach()
            layerAttached = false
        }
        destroyNative()
    }

    /** Legacy Skia-only loop without Compose scene. */
    fun runSkiaOnly(drawFrame: (Canvas, Int, Int) -> Unit) {
        layer.renderDelegate = object : SkikoRenderDelegate {
            override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
                drawFrame(canvas, width, height)
            }
        }
        run(onEvent = {})
    }

    fun destroy() {
        if (isHostAttached) {
            detachFromHost()
            return
        }
        if (layerAttached) {
            layer.detach()
            layerAttached = false
        }
        destroyNative()
    }

    private fun ensureLayerAttached() {
        if (layerAttached) return
        layer.attachTo(window)
        layerAttached = true
    }

    private fun destroyNative() {
        if (destroyed) return
        destroyed = true
        window.destroy()
    }
}

fun drawHelloFrame(canvas: Canvas, width: Int, height: Int) {
    val paint = org.jetbrains.skia.Paint().apply { color = org.jetbrains.skia.Color.makeRGB(30, 120, 220) }
    canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(40f, 40f, width.toFloat() - 80f, height.toFloat() - 80f), paint)
    val font = org.jetbrains.skia.Font(null, 28f)
    val textPaint = org.jetbrains.skia.Paint().apply { color = org.jetbrains.skia.Color.WHITE }
    canvas.drawString("ComposeKN / Wayland", 60f, 100f, font, textPaint)
}
