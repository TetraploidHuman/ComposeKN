@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.jetbrains.skiko

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned

/**
 * Windows 系统托盘（Shell_NotifyIcon）。
 *
 * [TrayMenuItem] 由 compose-ui 的 NativeMenuNode 转换而来；菜单命令经 C 回调回到
 * [onMenuCommand]，再按 id 调 onClick。
 */
class TrayMenuItem(
    val text: String = "",
    val enabled: Boolean = true,
    val isSeparator: Boolean = false,
    val onClick: (() -> Unit)? = null,
    val children: List<TrayMenuItem> = emptyList(),
)

object ComposeKNTray {
    private var created = false
    private var onAction: (() -> Unit)? = null
    private val menuClickMap = mutableMapOf<Int, () -> Unit>()
    private var menuStructureKey: String? = null
    private var ownedMenu: Win32Menu? = null

    fun available(): Boolean = composekn_win32_tray_available()

    fun ensureCreated(tooltip: String?) {
        if (created) {
            setTooltip(tooltip)
            return
        }
        val ok = tooltip.orEmpty().useCString { tip ->
            composekn_win32_tray_create(tip, trayCallback, null)
        }
        if (!ok) {
            println("ComposeKNTray: Shell_NotifyIcon create failed")
            return
        }
        created = true
    }

    fun setTooltip(tooltip: String?) {
        if (!created) return
        tooltip.orEmpty().useCString { composekn_win32_tray_set_tooltip(it) }
    }

    fun setOnAction(handler: (() -> Unit)?) {
        onAction = handler
    }

    fun setMenu(items: List<TrayMenuItem>) {
        if (!created && items.isEmpty()) return
        if (!created) ensureCreated(null)
        val key = structureKey(items)
        if (key == menuStructureKey) {
            rematerializeClicks(items)
            return
        }
        menuClickMap.clear()
        var nextId = 1
        fun buildPopup(nodes: List<TrayMenuItem>): Win32Menu {
            val menu = Win32Menu.createPopup()
            for (node in nodes) {
                when {
                    node.isSeparator -> menu.appendSeparator()
                    node.children.isNotEmpty() -> {
                        val child = buildPopup(node.children)
                        menu.appendPopup(node.text, child, node.enabled)
                    }
                    else -> {
                        val id = nextId++
                        menu.appendString(id, node.text, node.enabled)
                        node.onClick?.let { menuClickMap[id] = it }
                    }
                }
            }
            return menu
        }
        // 旧菜单由 C 侧 DestroyMenu；Kotlin 侧先 markTransferred 再交出。
        ownedMenu?.let {
            // 所有权已在上次 set_menu 交给 C；这里只丢引用。
        }
        ownedMenu = null
        if (items.isEmpty()) {
            composekn_win32_tray_set_menu(null)
            menuStructureKey = key
            return
        }
        val popup = buildPopup(items)
        composekn_win32_tray_set_menu(popup.handle)
        popup.markTransferred()
        ownedMenu = popup
        menuStructureKey = key
    }

    fun showNotification(title: String, message: String, type: Int) {
        if (!created) ensureCreated(null)
        title.useCString { t ->
            message.useCString { m ->
                composekn_win32_tray_notify(t, m, type)
            }
        }
    }

    /**
     * BGRA top-down pixels（与剪贴板位图约定一致）。通常 16×16。
     */
    fun setIcon(width: Int, height: Int, bgra: ByteArray) {
        if (!created) ensureCreated(null)
        if (!created) return
        if (width <= 0 || height <= 0 || bgra.size < width * height * 4) return
        bgra.usePinned { pinned ->
            composekn_win32_tray_set_icon(
                width,
                height,
                pinned.addressOf(0).reinterpret(),
            )
        }
    }

    fun destroy() {
        if (!created) return
        composekn_win32_tray_destroy()
        created = false
        onAction = null
        menuClickMap.clear()
        menuStructureKey = null
        ownedMenu = null
    }

    private fun rematerializeClicks(items: List<TrayMenuItem>) {
        menuClickMap.clear()
        var nextId = 1
        fun walk(nodes: List<TrayMenuItem>) {
            for (node in nodes) {
                when {
                    node.isSeparator -> Unit
                    node.children.isNotEmpty() -> walk(node.children)
                    else -> {
                        val id = nextId++
                        node.onClick?.let { menuClickMap[id] = it }
                    }
                }
            }
        }
        walk(items)
    }

    private fun structureKey(items: List<TrayMenuItem>): String = buildString {
        fun walk(nodes: List<TrayMenuItem>) {
            for (n in nodes) {
                when {
                    n.isSeparator -> append('|')
                    n.children.isNotEmpty() -> {
                        append('M')
                        append(n.text.length).append(':').append(n.text)
                        append(if (n.enabled) '1' else '0')
                        append('{')
                        walk(n.children)
                        append('}')
                    }
                    else -> {
                        append('I')
                        append(n.text.length).append(':').append(n.text)
                        append(if (n.enabled) '1' else '0')
                    }
                }
            }
        }
        walk(items)
    }

    internal fun dispatch(kind: Int, arg: Int) {
        try {
            when (kind) {
                0 -> onAction?.invoke()
                1 -> menuClickMap[arg]?.invoke()
            }
        } catch (t: Throwable) {
            println(
                "ComposeKNTray: callback kind=$kind arg=$arg " +
                    "${t::class.simpleName}: ${t.message}",
            )
        }
    }
}

@SymbolName("composekn_win32_tray_available")
internal external fun composekn_win32_tray_available(): Boolean

@SymbolName("composekn_win32_tray_create")
internal external fun composekn_win32_tray_create(
    tooltipUtf8: CPointer<ByteVar>?,
    callback: CPointer<kotlinx.cinterop.CFunction<(Int, Int, COpaquePointer?) -> Unit>>?,
    user: COpaquePointer?,
): Boolean

@SymbolName("composekn_win32_tray_set_tooltip")
internal external fun composekn_win32_tray_set_tooltip(tooltipUtf8: CPointer<ByteVar>?)

@SymbolName("composekn_win32_tray_set_menu")
internal external fun composekn_win32_tray_set_menu(hmenu: COpaquePointer?)

@SymbolName("composekn_win32_tray_notify")
internal external fun composekn_win32_tray_notify(
    titleUtf8: CPointer<ByteVar>?,
    bodyUtf8: CPointer<ByteVar>?,
    type: Int,
)

@SymbolName("composekn_win32_tray_destroy")
internal external fun composekn_win32_tray_destroy()

@SymbolName("composekn_win32_tray_set_icon")
internal external fun composekn_win32_tray_set_icon(
    w: Int,
    h: Int,
    bgra: CPointer<UByteVar>?,
): Boolean

private val trayCallback =
    staticCFunction<Int, Int, COpaquePointer?, Unit> { kind, arg, _ ->
        ComposeKNTray.dispatch(kind, arg)
    }

private inline fun <R> String.useCString(block: (CPointer<ByteVar>) -> R): R {
    val bytes = encodeToByteArray()
    val buf = ByteArray(bytes.size + 1)
    bytes.copyInto(buf)
    return buf.usePinned { block(it.addressOf(0)) }
}
