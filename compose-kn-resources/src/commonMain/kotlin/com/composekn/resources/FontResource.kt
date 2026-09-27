package com.composekn.resources

/**
 * ComposeKN 字体资源句柄 —— API 形态对齐官方 `compose.resources` 的 `FontResource`，
 * 供生成的 `Res.font.*` 与 [Font] 使用。
 *
 * 加载顺序见 [ResourceLoads.loadBytes]。
 */
class FontResource internal constructor(
    /** 稳定 identity（通常等于文件名去扩展名），供 Compose Font 缓存区分。 */
    val identity: String,
    /**
     * 相对资源根的路径，例如 `font/noto_sans_regular.otf`
     *（与官方 `composeResources/font/` 布局一致）。
     */
    val relativePath: String,
    /**
     * 构建期写入的绝对路径（开发时 composeResources 源目录），
     * 便于未 copy 到 exe 旁时也能直接跑 Gradle 产物。
     */
    val developmentPath: String? = null,
) {
    override fun toString(): String = "FontResource($identity → $relativePath)"
}

/** 由 Gradle 插件生成 `Res.font.xxx` 时调用。 */
fun fontResource(
    identity: String,
    relativePath: String,
    developmentPath: String? = null,
): FontResource = FontResource(identity, relativePath, developmentPath)
