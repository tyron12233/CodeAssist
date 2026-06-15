package dev.ide.lang.kotlin.compile

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Supplies the Kotlin standard-library jar WITHOUT borrowing it from the host runtime.
 *
 * The jar is bundled as a classpath resource (`/kotlin-stdlib.jar`, copied in by the lang-kotlin build at
 * the `kotlin` version in `libs.versions.toml`) and extracted to a real file on demand. This is the only
 * stdlib source that works identically on a desktop JVM and on ART: `Unit::class`'s code source is the
 * app's dex on device, not a jar kotlinc or the symbol reader can open.
 *
 * The IDE attaches the extracted jar as the `kotlin-stdlib` library dependency of every Kotlin module (so
 * it resolves onto the compile AND run/dex classpaths through the normal classpath machinery). These helpers
 * are the artifact source for that, and a safety net for the in-process compiler and the editor symbol
 * service when no such dependency is declared (e.g. an editor-only project, or a unit test).
 */
object BundledKotlinStdlib {
    /** The bundled stdlib version. Kept in lockstep with `libs.versions.toml` `kotlin`. */
    const val VERSION: String = "2.4.0"

    private const val RESOURCE = "/kotlin-stdlib.jar"
    private const val FILE_NAME = "kotlin-stdlib-$VERSION.jar"

    @Volatile private var cachedPath: Path? = null

    /** True when the stdlib jar is bundled on the classpath (false only in a stripped-down test classpath). */
    fun isBundled(): Boolean = BundledKotlinStdlib::class.java.getResource(RESOURCE) != null

    /**
     * Extract the bundled jar into [dir] as `kotlin-stdlib-<version>.jar`, reusing an already-extracted copy.
     * Returns null when the resource is absent. The IDE uses this to place the artifact at a stable
     * per-workspace path it can persist as a library root.
     */
    fun extractTo(dir: Path): Path? = runCatching {
        val target = dir.resolve(FILE_NAME)
        if (Files.isRegularFile(target) && Files.size(target) > 0L) return target
        val stream = BundledKotlinStdlib::class.java.getResourceAsStream(RESOURCE) ?: return null
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, "kotlin-stdlib", ".tmp")
        stream.use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
        runCatching { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE) }
            .getOrElse { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING) }
        target
    }.getOrNull()

    /**
     * The bundled jar extracted to a process-wide temp cache: the fallback artifact for in-process consumers
     * (the compiler, the symbol service) that have no workspace dir handy.
     */
    fun cached(): Path? {
        cachedPath?.let { if (Files.isRegularFile(it)) return it }
        val dir = Path.of(System.getProperty("java.io.tmpdir"), "codeassist", "kotlin-stdlib")
        return extractTo(dir)?.also { cachedPath = it }
    }

    /**
     * Last resort: the kotlin-stdlib jar already loaded into THIS process (desktop dev / tests). Null on ART,
     * where `Unit`'s code source is the app dex, and whenever it isn't a real `.jar` file.
     */
    fun hostJar(): Path? = runCatching {
        val loc = Unit::class.java.protectionDomain?.codeSource?.location ?: return null
        Path.of(loc.toURI()).takeIf { it.toString().endsWith(".jar") && Files.isRegularFile(it) }
    }.getOrNull()

    /** Best available stdlib jar: the bundled+extracted copy, else the host jar (desktop/tests). */
    fun jar(): Path? = cached() ?: hostJar()
}
