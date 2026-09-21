package org.jetbrains.skiko

/**
 * 剪贴板里的一张位图（跨平台的**中间表示**）。
 *
 * 为什么不让 shim 直接交换 Compose 的 `ImageBitmap`：skiko 的 `linuxMain` /
 * `windowsMain` 是原生平台层，不该依赖 Compose（依赖方向是 compose-core -> skiko）。
 * 所以两边都只交换「宽高 + BGRA 字节」，由 compose-core 的 `PlatformClipboard` 那一层
 * 负责转成/转回 `ImageBitmap`。
 *
 * 字节布局固定为：**BGRA、每像素 4 字节、自上而下、stride = width * 4**
 * （Windows 的 CF_DIBV5 是自下而上的，转换在 C 侧做掉 —— 解码要处理的坑都在一处）。
 * 没有 premultiply，alpha 就是原样的 0..255。
 */
class ClipboardImage(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "ClipboardImage 尺寸非法: ${width}x$height" }
        require(pixels.size >= width * height * 4) {
            "ClipboardImage 像素数据不够: ${pixels.size} < ${width * height * 4}"
        }
    }
}
