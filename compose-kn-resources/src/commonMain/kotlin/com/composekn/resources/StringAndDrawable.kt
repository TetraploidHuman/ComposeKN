package com.composekn.resources

/**
 * 字符串资源句柄（对齐官方 `StringResource` 形态；v0.5.54 起）。
 * 内容来自 `composeResources/values/strings.xml` 的 name → 生成 `Res.string.*`。
 */
class StringResource internal constructor(
    val identity: String,
    val relativePath: String,
    val developmentPath: String? = null,
    /** 已解析的默认文案（生成期写入）；运行时可被 values-xx 覆盖（后续）。 */
    val defaultValue: String,
) {
    override fun toString(): String = "StringResource($identity)"
}

fun stringResource(
    identity: String,
    relativePath: String,
    defaultValue: String,
    developmentPath: String? = null,
): StringResource = StringResource(identity, relativePath, developmentPath, defaultValue)

/** 读取字符串（当前 = [StringResource.defaultValue]；后续接 locale overlay）。 */
fun stringResource(resource: StringResource): String = resource.defaultValue

/**
 * Drawable / 图片资源句柄（对齐官方 `DrawableResource` 形态）。
 * 文件在 `composeResources/drawable/`；运行时查找同 [FontResource]。
 */
class DrawableResource internal constructor(
    val identity: String,
    val relativePath: String,
    val developmentPath: String? = null,
) {
    override fun toString(): String = "DrawableResource($identity → $relativePath)"
}

fun drawableResource(
    identity: String,
    relativePath: String,
    developmentPath: String? = null,
): DrawableResource = DrawableResource(identity, relativePath, developmentPath)

/** 读 drawable 字节（png/webp/svg 等）；查找顺序同字体。 */
fun loadDrawableBytes(resource: DrawableResource): ByteArray? =
    ResourceLoads.loadBytes(
        FontResource(resource.identity, resource.relativePath, resource.developmentPath),
    )
