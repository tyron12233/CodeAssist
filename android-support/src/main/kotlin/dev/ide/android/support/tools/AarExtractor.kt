package dev.ide.android.support.tools

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.io.path.writeText

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
        val proguardTxt: Path?,        // the AAR's consumer keep rules (root `proguard.txt`), applied by the app's R8
        val aarMetadata: Path?,        // META-INF/.../aar-metadata.properties (AGP's minCompileSdk etc.); null if absent
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
            proguardTxt = into.resolve("proguard.txt").takeIf { Files.isRegularFile(it) },
            aarMetadata = into.resolve(AarMetadata.ENTRY_PATH).takeIf { Files.isRegularFile(it) },
        )
    }

    // The `.exploded` marker (written last, inside a fully-extracted dir) is the ONLY completion signal.
    // Keying on `classes.jar` — which is written mid-stream, being just another zip entry — would treat a
    // crash/cancel that stopped after `classes.jar` but before `res/`+`assets/` as "already done", reusing a
    // dir with missing/truncated resources and native assets.
    private fun isAlreadyExploded(into: Path): Boolean = Files.isRegularFile(into.resolve(".exploded"))

    /**
     * Extract into a temp sibling dir and atomically swap it into place, so an interrupted extraction never
     * leaves a partial dir that reads as complete. The marker is written into the temp dir *before* the swap,
     * and [into] receives it only as part of the fully-populated directory.
     */
    private fun unzip(aar: Path, into: Path) {
        val parent = checkNotNull(into.parent) { "AAR explode target must have a parent dir: $into" }
        Files.createDirectories(parent)
        val tmp = Files.createTempDirectory(parent, "aar-explode-")
        try {
            ZipFile(aar.toFile()).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    val target = tmp.resolve(e.name).normalize()
                    require(target.startsWith(tmp)) { "unsafe zip entry (zip slip): ${e.name}" }
                    if (e.isDirectory) {
                        Files.createDirectories(target)
                    } else {
                        target.parent?.let { Files.createDirectories(it) }
                        zf.getInputStream(e).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
                    }
                }
            }
            (tmp.resolve(".exploded")).writeText(aar.fileName.toString())
            // Swap into place: drop any partial/previous dir (no marker → not trusted), then move the
            // fully-extracted temp dir over it. A crash in the gap leaves NO `into`, so the next run re-extracts.
            if (Files.exists(into)) into.toFile().deleteRecursively()
            try {
                Files.move(tmp, into, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, into)
            } catch (e: FileAlreadyExistsException) {
                // A concurrent explode of the same AAR won the swap; its result is complete — reuse it.
                if (!isAlreadyExploded(into)) throw e
            }
        } finally {
            if (Files.exists(tmp)) tmp.toFile().deleteRecursively()
        }
    }
}
