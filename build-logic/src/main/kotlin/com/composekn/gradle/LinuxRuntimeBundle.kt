package com.composekn.gradle

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object LinuxRuntimeBundle {
    /** Directory containing libcrypt.so.1 (Kotlin/Native stdlib NEEDED on NixOS). */
    fun libcryptLibDir(): File? {
        System.getenv("COMPOSEKN_LIBCRYPT_LIB")?.let { path ->
            val dir = File(path)
            if (File(dir, "libcrypt.so.1").exists()) return dir
        }
        System.getenv("LD_LIBRARY_PATH")?.split(":")?.forEach { entry ->
            if (entry.isBlank()) return@forEach
            val dir = File(entry)
            if (File(dir, "libcrypt.so.1").exists()) return dir
        }
        return null
    }

    fun bundleLibcryptNextToKexe(kexe: File, logger: (String) -> Unit) {
        val src = libcryptSo1File() ?: run {
            logger(
                "libcrypt.so.1 not found (set COMPOSEKN_LIBCRYPT_LIB or use nix-shell ./shell.nix); " +
                    "run via ./scripts/run-linux-native.sh"
            )
            return
        }
        val libDir = kexe.parentFile.resolve("lib")
        libDir.mkdirs()
        val dest = libDir.resolve("libcrypt.so.1")
        Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        logger("Bundled ${dest.name} for ${kexe.name} (\$ORIGIN/lib)")
    }

    private fun libcryptSo1File(): File? {
        val dir = libcryptLibDir() ?: return null
        val file = File(dir, "libcrypt.so.1")
        return file.takeIf { it.isFile }
    }
}
