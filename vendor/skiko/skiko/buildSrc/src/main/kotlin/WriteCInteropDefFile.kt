import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class WriteCInteropDefFile : DefaultTask() {
    @get:Input
    abstract val linkerOpts: ListProperty<String>

    @get:InputFile
    @get:Optional
    abstract val originalDefFile: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun run() {
        val outputFile = outputFile.get().asFile
        outputFile.parentFile.mkdirs()

        outputFile.bufferedWriter().use { writer ->
            // First, copy content from original .def file if it exists
            if (originalDefFile.isPresent) {
                val originalFile = originalDefFile.get().asFile
                if (originalFile.exists()) {
                    originalFile.forEachLine { line ->
                        // Skip linkerOpts line from original, we'll add our own
                        if (!line.trim().startsWith("linkerOpts=")) {
                            writer.appendLine(line)
                        }
                    }
                    writer.appendLine()
                }
            }

            // Then add/override with our linkerOpts
            val linkerOpts = linkerOpts.get()
            if (linkerOpts.isNotEmpty()) {
                writer.appendLine("linkerOpts=${linkerOpts.joinToString(" ")}")
            }
        }
    }
}