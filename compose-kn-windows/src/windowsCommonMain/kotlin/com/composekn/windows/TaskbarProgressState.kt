package com.composekn.windows

/**
 * 任务栏进度条的状态。
 *
 * 取值与 Windows 的 `TBPFLAG` 一一对应（数字是 Win32 的约定，**不要改**）：
 *   * [None] = `TBPF_NOPROGRESS`：没有进度条（正常状态）
 *   * [Indeterminate] = `TBPF_INDETERMINATE`：滚动/不确定（不知道百分比时用）
 *   * [Normal] = `TBPF_NORMAL`：绿色
 *   * [Error] = `TBPF_ERROR`：红色
 *   * [Paused] = `TBPF_PAUSED`：黄色
 *
 * 对齐上游的点：Compose Desktop 桌面上没有直接的封装（要用 JNA/AWT 拿 HWND 自己调），
 * 所以这里按 Win32 的原始语义给，命名用 Windows 自己的名字。
 */
enum class TaskbarProgressState(val win32Value: Int) {
    None(0),
    Indeterminate(1),
    Normal(2),
    Error(4),
    Paused(8),
    ;

    companion object {
        /** Windows 的 `TBPFLAG` -> 这里的枚举；不认识的值（比如组合标志）当成 [None]。 */
        fun fromWin32(value: Int): TaskbarProgressState =
            entries.firstOrNull { it.win32Value == value } ?: None
    }
}
