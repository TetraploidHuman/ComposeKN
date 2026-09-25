@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.jetbrains.skiko

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.F_OK
import platform.posix.access

/**
 * Linux 托盘：StatusNotifierItem + DBusMenu；通知走 notify-send。
 *
 * [setMenu] 把 [TrayMenuItem] 编成 C 侧行协议：`id\\tkind\\tenabled\\tlabel\\n`
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

    fun available(): Boolean = composekn_linux_tray_available()

    fun ensureCreated(tooltip: String?) {
        if (created) {
            setTooltip(tooltip)
            return
        }
        val ok = tooltip.orEmpty().useCString { tip ->
            composekn_linux_tray_create(tip, trayCallback, null)
        }
        if (!ok) {
            // 无 dbus 时仍允许 notify-send 通道。
            created = notifySendPath() != null
            if (!created) {
                println("ComposeKNTray: linux tray create failed")
            }
            return
        }
        created = true
    }

    fun setTooltip(tooltip: String?) {
        if (!created) return
        tooltip.orEmpty().useCString { composekn_linux_tray_set_tooltip(it) }
    }

    fun setOnAction(handler: (() -> Unit)?) {
        onAction = handler
    }

    fun setMenu(items: List<TrayMenuItem>) {
        if (!created) ensureCreated(null)
        menuClickMap.clear()
        val encoded = encodeMenu(items)
        encoded.useCString { composekn_linux_tray_set_menu(it) }
    }

    fun setIcon(width: Int, height: Int, bgra: ByteArray) {
        if (!created) ensureCreated(null)
        if (!created) return
        if (width <= 0 || height <= 0 || bgra.size < width * height * 4) return
        bgra.usePinned { pinned ->
            composekn_linux_tray_set_icon(
                width,
                height,
                pinned.addressOf(0).reinterpret(),
            )
        }
    }

    fun showNotification(title: String, message: String, type: Int) {
        if (!created) ensureCreated(null)
        title.useCString { t ->
            message.useCString { m ->
                composekn_linux_tray_notify(t, m, type)
            }
        }
    }

    /** UI 泵应周期性调用。 */
    fun dispatch() {
        if (created) composekn_linux_tray_dispatch()
    }

    fun destroy() {
        if (!created) return
        composekn_linux_tray_destroy()
        created = false
        onAction = null
        menuClickMap.clear()
    }

    private fun encodeMenu(items: List<TrayMenuItem>): String = buildString {
        var nextId = 1
        fun walk(nodes: List<TrayMenuItem>) {
            for (n in nodes) {
                when {
                    n.isSeparator -> {
                        append(nextId++).append("\tS\t1\t\n")
                    }
                    n.children.isNotEmpty() -> {
                        // 子菜单扁平化为带前缀标签的项（DBusMenu v1 无嵌套）。
                        walk(
                            n.children.map { child ->
                                if (child.isSeparator) child
                                else TrayMenuItem(
                                    text = "${n.text} › ${child.text}",
                                    enabled = child.enabled && n.enabled,
                                    onClick = child.onClick,
                                )
                            },
                        )
                    }
                    else -> {
                        val id = nextId++
                        n.onClick?.let { menuClickMap[id] = it }
                        append(id).append('\t').append('I').append('\t')
                        append(if (n.enabled) '1' else '0').append('\t')
                        append(n.text.replace('\t', ' ').replace('\n', ' '))
                        append('\n')
                    }
                }
            }
        }
        walk(items)
    }

    internal fun dispatchCallback(kind: Int, arg: Int) {
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

    private fun notifySendPath(): String? {
        val candidates = listOf(
            "/usr/bin/notify-send",
            "/bin/notify-send",
            "/run/current-system/sw/bin/notify-send",
        )
        for (p in candidates) {
            if (access(p, F_OK) == 0) return p
        }
        val path = platform.posix.getenv("PATH")?.toKString() ?: return null
        for (dir in path.split(':')) {
            if (dir.isEmpty()) continue
            val candidate = "$dir/notify-send"
            if (access(candidate, F_OK) == 0) return candidate
        }
        return null
    }
}

@SymbolName("composekn_linux_tray_available")
internal external fun composekn_linux_tray_available(): Boolean

@SymbolName("composekn_linux_tray_create")
internal external fun composekn_linux_tray_create(
    tooltipUtf8: CPointer<ByteVar>?,
    callback: CPointer<kotlinx.cinterop.CFunction<(Int, Int, COpaquePointer?) -> Unit>>?,
    user: COpaquePointer?,
): Boolean

@SymbolName("composekn_linux_tray_set_tooltip")
internal external fun composekn_linux_tray_set_tooltip(tooltipUtf8: CPointer<ByteVar>?)

@SymbolName("composekn_linux_tray_set_menu")
internal external fun composekn_linux_tray_set_menu(itemsUtf8: CPointer<ByteVar>?)

@SymbolName("composekn_linux_tray_set_icon")
internal external fun composekn_linux_tray_set_icon(
    w: Int,
    h: Int,
    bgra: CPointer<UByteVar>?,
): Boolean

@SymbolName("composekn_linux_tray_notify")
internal external fun composekn_linux_tray_notify(
    titleUtf8: CPointer<ByteVar>?,
    bodyUtf8: CPointer<ByteVar>?,
    type: Int,
)

@SymbolName("composekn_linux_tray_dispatch")
internal external fun composekn_linux_tray_dispatch()

@SymbolName("composekn_linux_tray_destroy")
internal external fun composekn_linux_tray_destroy()

private val trayCallback =
    staticCFunction<Int, Int, COpaquePointer?, Unit> { kind, arg, _ ->
        ComposeKNTray.dispatchCallback(kind, arg)
    }

private inline fun <R> String.useCString(block: (CPointer<ByteVar>) -> R): R {
    val bytes = encodeToByteArray()
    val buf = ByteArray(bytes.size + 1)
    bytes.copyInto(buf)
    return buf.usePinned { block(it.addressOf(0)) }
}
