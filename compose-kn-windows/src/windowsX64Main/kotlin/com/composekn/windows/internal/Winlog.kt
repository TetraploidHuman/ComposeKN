package com.composekn.windows.internal

/**
 * 启动诊断日志：写入 exe 同目录的 composekn-startup.log。
 *
 * 单独放在 windowsX64Main：它依赖 skiko 的 win32Log（Win32 专属），
 * 而 Win32Structures.kt 里的常量/工具函数要保持「纯 Kotlin」以便单测复用。
 */
fun winlog(message: String) {
    try {
        org.jetbrains.skiko.win32Log(message)
    } catch (t: Throwable) {
        // 日志失败不影响主流程
    }
}
