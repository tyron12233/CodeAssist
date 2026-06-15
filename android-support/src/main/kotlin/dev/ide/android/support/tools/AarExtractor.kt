package dev.ide.android.support.tools

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/**
 * Explodes an Android `.aar` (a zip) into its parts. An AAR
 * bundles compiled code (`classes.jar` plus optional jars under `libs/`), Android resources (`res/`),
 * `assets/`, a library `AndroidManifest.xml`, and native libs under `jni/`. The build feeds the code to
 * compile + dex, the resources to aapt2, and the assets/jni to packaging. Extraction is idempotent —
 * an AAR's content is immutable per coordinate, so an already-exploded dir is reused.
 */
object AarExtractor {

    data class Exploded(
        val classesJars: List<Path>,   // classes.jar + any libs/*.jar
        val resDir: Path?,
        val assetsDir: Path?,
        val manifest: Path?,
        val jniDir: Path?,
    )

    fun explode(aar: Path, into: Path): Exploded {
        if (!isAlreadyExploded(into)) unzip(aar, into)
        val classes = buildList {
            into.resolve("classes.jar").takeIf { Files.isRegularFile(it) }?.let { add(it) }
            val libs = into.resolve("libs")
            if (Files.isDirectory(libs)) {
                Files.list(libs).use { s -> s.filter { it.toString().endsWith(".jar") }.sorted().forEach { add(it) } }
            }
        }
        return Exploded(
            classesJars = classes,
            resDir = into.resolve("res").takeIf { Files.isDirectory(it) },
            assetsDir = into.resolve("assets").takeIf { Files.isDirectory(it) },
            manifest = into.resolve("AndroidManifest.xml").takeIf { Files.isRegularFile(it) },
            jniDir = into.resolve("jni").takeIf { Files.isDirectory(it) },
        )
    }

    private fun isAlreadyExploded(into: Path): Boolean =
        Files.isRegularFile(into.resolve("classes.jar")) || Files.isRegularFile(into.resolve(".exploded"))

    private fun unzip(aar: Path, into: Path) {
        Files.createDirectories(into)
        ZipFile(aar.toFile()).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                val target = into.resolve(e.name).normalize()
                require(target.startsWith(into)) { "unsafe zip entry (zip slip): ${e.name}" }
                if (e.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    target.parent?.let { Files.createDirectories(it) }
                    zf.getInputStream(e).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
                }
            }
        }
        Files.writeString(into.resolve(".exploded"), aar.fileName.toString())
    }
}
