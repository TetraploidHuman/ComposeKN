package com.composekn.resources

/**
 * 解析并读取 ComposeKN 资源字节。
 *
 * 查找顺序：
 * 1. `COMPOSEKN_RESOURCES` 环境变量（目录）+ relativePath
 * 2. `<exeDir>/composeResources/` + relativePath
 * 3. `<exeDir>/` + relativePath
 * 4. `<exeDir>/fonts/` + fileName（仅 font/ 前缀时）
 * 5. [FontResource.developmentPath]（Gradle 生成的源文件绝对路径）
 */
object ResourceLoads {
    fun loadBytes(resource: FontResource): ByteArray? {
        val rel = resource.relativePath.trimStart('/', '\\')
        val fileName = rel.substringAfterLast('/').substringAfterLast('\\')
        val candidates = ArrayList<String>(8)

        environResourceRoot()?.let { root ->
            candidates += join(root, rel)
            candidates += join(root, "composeResources", rel)
            candidates += join(root, "font", fileName)
            candidates += join(root, "fonts", fileName)
        }

        executableDirectory()?.let { exeDir ->
            candidates += join(exeDir, "composeResources", rel)
            candidates += join(exeDir, rel)
            candidates += join(exeDir, "fonts", fileName)
            candidates += join(exeDir, "font", fileName)
        }

        resource.developmentPath?.let { candidates += it }

        for (path in candidates) {
            val bytes = readFile(path)
            if (bytes != null && bytes.isNotEmpty()) return bytes
        }
        return null
    }

    fun readFile(path: String): ByteArray? = readFileBytes(path)

    private fun environResourceRoot(): String? =
        getenv("COMPOSEKN_RESOURCES")?.trim()?.takeIf { it.isNotEmpty() }

    private fun join(first: String, vararg parts: String): String {
        var out = first.trimEnd('/', '\\')
        for (p in parts) {
            val piece = p.trim('/', '\\')
            if (piece.isEmpty()) continue
            out = "$out/$piece"
        }
        return out
    }
}

internal expect fun executableDirectory(): String?
internal expect fun readFileBytes(path: String): ByteArray?
internal expect fun getenv(name: String): String?
