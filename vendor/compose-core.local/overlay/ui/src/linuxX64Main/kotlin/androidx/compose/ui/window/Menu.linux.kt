/*
 * Copyright 2026 The ComposeKN Authors (linuxX64 / mingw port)
 *
 * Win32 原生 HMENU 菜单栏（FrameWindowScope.MenuBar）。
 * 策略：composition 用稳定 slot 收集树 → SideEffect rebuild-on-change → SetMenu。
 * 非 Windows / 无边框窗口：ComposeNativeWindowHandle.setMenuBar 为 no-op。
 */

package androidx.compose.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * 原生菜单树节点（平台无关模型；Windows 上映射为 HMENU）。
 */
sealed class NativeMenuNode {
    data class Menu(
        val text: String,
        val enabled: Boolean = true,
        val children: List<NativeMenuNode> = emptyList(),
    ) : NativeMenuNode()

    data class Item(
        val text: String,
        val enabled: Boolean = true,
        val onClick: () -> Unit,
    ) : NativeMenuNode()

    data object Separator : NativeMenuNode()
}

/**
 * 整棵菜单栏模型（顶层必须是 [NativeMenuNode.Menu]）。
 */
data class NativeMenuBarModel(
    val menus: List<NativeMenuNode.Menu>,
)

/**
 * 结构指纹（忽略 onClick）：宿主用它避免动画每帧 DestroyMenu/CreateMenu。
 */
fun NativeMenuBarModel.structureKey(): String = buildString {
    fun walk(nodes: List<NativeMenuNode>) {
        for (n in nodes) {
            when (n) {
                is NativeMenuNode.Separator -> append('|')
                is NativeMenuNode.Item -> {
                    append('I')
                    append(n.text.length).append(':').append(n.text)
                    append(if (n.enabled) '1' else '0')
                }
                is NativeMenuNode.Menu -> {
                    append('M')
                    append(n.text.length).append(':').append(n.text)
                    append(if (n.enabled) '1' else '0')
                    append('{')
                    walk(n.children)
                    append('}')
                }
            }
        }
    }
    walk(menus)
}

/** 组合期稳定挂载点：同一 slot 重复 ensure 不会打乱顺序。 */
internal class MenuSlot(var node: NativeMenuNode)

/** MenuBar / Tray 共用：收集 Menu / Item / Separator 树。 */
internal class MenuCollector {
    private val slots = mutableListOf<MenuSlot>()

    fun ensure(slot: MenuSlot) {
        if (slot !in slots) slots.add(slot)
    }

    fun remove(slot: MenuSlot) {
        slots.remove(slot)
    }

    fun toList(): List<NativeMenuNode> = slots.map { it.node }

    fun toMenus(): List<NativeMenuNode.Menu> =
        slots.mapNotNull { it.node as? NativeMenuNode.Menu }
}

internal val LocalMenuCollector =
    compositionLocalOf<MenuCollector> {
        error("Menu/Item 只能用在 MenuBar / Tray / Menu 内容里")
    }

/**
 * 在窗口标题栏下挂原生菜单栏（Win32 = HMENU；其它平台 no-op）。
 *
 * 无边框窗口（`undecorated = true`）不会显示系统菜单栏，宿主会忽略本次设置。
 *
 * @param content 菜单列表（[MenuBarScope.Menu]）
 */
@Composable
fun FrameWindowScope.MenuBar(content: @Composable MenuBarScope.() -> Unit) {
    val roots = remember { MenuCollector() }
    CompositionLocalProvider(LocalMenuCollector provides roots) {
        MenuBarScope().content()
    }
    SideEffect {
        window.setMenuBar(NativeMenuBarModel(roots.toMenus()))
    }
    DisposableEffect(Unit) {
        onDispose { window.setMenuBar(null) }
    }
}

/**
 * [FrameWindowScope.MenuBar] 的 receiver：往菜单栏追加顶层 [Menu]。
 */
class MenuBarScope internal constructor() {
    /**
     * @param text 菜单栏上显示的标题
     * @param enabled 是否可打开
     * @param content 子项（[MenuScope.Item] / [MenuScope.Separator] / 嵌套 [MenuScope.Menu]）
     */
    @Composable
    fun Menu(
        text: String,
        enabled: Boolean = true,
        content: @Composable MenuScope.() -> Unit,
    ) {
        menuNode(text, enabled, content)
    }
}

/**
 * 单个弹出菜单 / 子菜单的 receiver。
 */
class MenuScope internal constructor() {
    @Composable
    fun Menu(
        text: String,
        enabled: Boolean = true,
        content: @Composable MenuScope.() -> Unit,
    ) {
        menuNode(text, enabled, content)
    }

    @Composable
    fun Separator() {
        val parent = LocalMenuCollector.current
        val slot = remember { MenuSlot(NativeMenuNode.Separator) }
        slot.node = NativeMenuNode.Separator
        parent.ensure(slot)
        DisposableEffect(parent) {
            onDispose { parent.remove(slot) }
        }
    }

    /**
     * @param onClick 用户点选该项时回调（在 UI 线程 / 消息泵线程）
     */
    @Composable
    fun Item(
        text: String,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) {
        val parent = LocalMenuCollector.current
        val latestOnClick by rememberUpdatedState(onClick)
        val slot = remember {
            MenuSlot(
                NativeMenuNode.Item(
                    text = text,
                    enabled = enabled,
                    onClick = { latestOnClick() },
                ),
            )
        }
        slot.node = NativeMenuNode.Item(
            text = text,
            enabled = enabled,
            onClick = { latestOnClick() },
        )
        parent.ensure(slot)
        DisposableEffect(parent) {
            onDispose { parent.remove(slot) }
        }
    }
}

@Composable
private fun menuNode(
    text: String,
    enabled: Boolean,
    content: @Composable MenuScope.() -> Unit,
) {
    val parent = LocalMenuCollector.current
    val children = remember { MenuCollector() }
    CompositionLocalProvider(LocalMenuCollector provides children) {
        MenuScope().content()
    }
    val slot = remember {
        MenuSlot(NativeMenuNode.Menu(text = text, enabled = enabled, children = emptyList()))
    }
    slot.node = NativeMenuNode.Menu(
        text = text,
        enabled = enabled,
        children = children.toList(),
    )
    parent.ensure(slot)
    DisposableEffect(parent) {
        onDispose { parent.remove(slot) }
    }
}
