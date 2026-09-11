import java.io.File
import java.util.concurrent.TimeUnit
import org.gradle.api.GradleException

private fun runPkgConfigRaw(vararg args: String): List<String> {
    val process = ProcessBuilder(*args)
        .run {
            environment()["PKG_CONFIG_ALLOW_SYSTEM_LIBS"] = "1"
            start()
        }
        .also { it.waitFor(10, TimeUnit.SECONDS) }

    if (process.exitValue() != 0) {
        throw GradleException("Error executing ${args.joinToString(" ")}: ${process.errorStream.bufferedReader().readText()}")
    }

    return process.inputStream.bufferedReader().readText()
        .split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

fun runPkgConfig(
    vararg packageNames: String,
): List<File> {
    return runPkgConfigRaw("pkg-config", "--cflags", *packageNames)
        .map { it.removePrefix("-I") }
        .map(::File)
}

fun runPkgConfigLinkerOpts(
    vararg packageNames: String,
): List<String> = runPkgConfigRaw("pkg-config", "--libs", *packageNames)