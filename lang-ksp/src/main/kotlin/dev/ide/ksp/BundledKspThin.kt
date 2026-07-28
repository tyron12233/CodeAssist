package dev.ide.ksp

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Supplies the **thin KSP2 runner** — KSP's own classes (`com.google.devtools.ksp.**`) with its 78 MB bundled
 * Analysis API stripped out, so KSP runs on the AA we ALREADY ship (`:kotlin-compiler-deps`). The jar is a
 * `:lang-ksp` classpath resource (`/ksp-thin.jar`, ~776 KB, built by the `kspThinJar` task from
 * `symbol-processing-aa`); this extracts it to a real file on demand — [KspProcessorLoader] needs a jar/dir to
 * load `com.google.devtools.ksp.impl.KotlinSymbolProcessing` from.
 *
 * Loaded through a [KspProcessorLoader] parented to a classloader carrying our Analysis API (the app
 * classloader in production, which holds the dexed `:kotlin-compiler-deps`), so KSP's impl resolves
 * `org.jetbrains.kotlin.analysis.*` against OUR compiler. Bundled like `ComposeCompilerPlugin` /
 * `BundledKotlinStdlib`. Proven end to end (incl. Room) by `ThinKspOnOurAaSpikeTest`.
 */
object BundledKspThin {

    private const val RESOURCE = "/ksp-thin.jar"
    private const val FILE_NAME = "ksp-thin.jar"

    @Volatile private var cachedPath: Path? = null

    /** True when the thin-KSP jar is bundled on the classpath (false only in a stripped-down test classpath). */
    fun isBundled(): Boolean = BundledKspThin::class.java.getResource(RESOURCE) != null

    /** Extract the bundled jar into [dir], reusing an already-extracted copy. Null when the resource is absent. */
    fun extractTo(dir: Path): Path? = runCatching {
        val target = dir.resolve(FILE_NAME)
        if (Files.isRegularFile(target) && Files.size(target) > 0L) return target
        val stream = BundledKspThin::class.java.getResourceAsStream(RESOURCE) ?: return null
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, "ksp-thin", ".tmp")
        stream.use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
        runCatching { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE) }
            .getOrElse { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING) }
        target
    }.getOrNull()

    /** The bundled thin-KSP jar extracted to a process-wide, **content-keyed** temp cache (so an app-update
     *  version bump re-extracts instead of serving a stale copy); null when the resource is absent. */
    fun jar(): Path? {
        cachedPath?.let { if (Files.isRegularFile(it)) return it }
        val bytes = BundledKspThin::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() } ?: return null
        val key = MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val dir = Paths.get(System.getProperty("java.io.tmpdir"), "codeassist", "ksp-thin", key)
        return extractTo(dir)?.also { cachedPath = it }
    }
}
