package dev.ide.android.support.metadata.gen

import com.android.tools.r8.BackportedMethodList
import com.android.tools.r8.BackportedMethodListCommand
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.StringConsumer
import dev.ide.android.support.apilevels.AndroidApiDatabase
import dev.ide.android.support.apilevels.ApiVersionsXml
import java.nio.file.Files
import java.nio.file.Path

/**
 * Generates the bundled API-level table (`android-api-levels.bin`) from a platform's `data/api-versions.xml`,
 * marking the `java.*` methods D8 backports (so the minSdk check does not report them).
 *
 * Usage:
 * ```
 * ./gradlew :android-sdk-metadata:generateApiLevels --args \
 *   "$SDK/platforms/android-36/data/api-versions.xml android/android-support/src/main/resources/android-api-levels.bin"
 * ```
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: <api-versions.xml> <output.bin>")
        return
    }
    val xml = Path.of(args[0])
    val out = Path.of(args[1])

    val backported = backportedMethods()
    val parsed = Files.newInputStream(xml).use { ApiVersionsXml.parse(it, backported) }
    val bytes = AndroidApiDatabase.write(parsed.platformLevel, parsed.classes)
    out.parent?.let { Files.createDirectories(it) }
    Files.write(out, bytes)

    val members = parsed.classes.sumOf { it.methods.size + it.fields.size }
    val desugared = parsed.classes.sumOf { c -> c.methods.count { it.desugared } }
    println(
        "Wrote ${out.toAbsolutePath()}: api=${parsed.platformLevel}, ${parsed.classes.size} classes, " +
            "$members members ($desugared backported by D8), ${bytes.size} bytes"
    )
}

/** Every method D8 backports for any minSdk, as `owner#name(desc)ret` (the shape [ApiVersionsXml] matches). */
private fun backportedMethods(): Set<String> {
    val lines = LinkedHashSet<String>()
    val consumer = object : StringConsumer {
        override fun accept(string: String, handler: DiagnosticsHandler) {
            string.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach(lines::add)
        }
    }
    BackportedMethodList.run(BackportedMethodListCommand.builder().setMinApiLevel(1).setConsumer(consumer).build())
    return lines
}
