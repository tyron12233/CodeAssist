package dev.ide.lang

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Derives the library/SDK SOURCE roots a JVM backend publishes as [JvmIndexScopeProvider.librarySourceArchives]:
 * the `-sources.jar`s the project model DECLARES ([CompilationContext.sourceAttachments]) plus the two roots a
 * backend must DERIVE from the boot classpath instead — the JDK `src.zip` and the Android platform
 * `sources/android-NN` dir.
 *
 * Shared, because every `.java` backend has to publish the same set: that one list is what feeds both the
 * `java.sourceDoc` index and the on-demand parse fallback, and those are the only places real parameter names
 * and javadoc for a library can come from (Java bytecode carries neither unless it was built with
 * `-parameters`). A backend that omits a root doesn't fail visibly — every parameter of every library method
 * just renders `p0`, in `.java` and, through the Kotlin backend's binary-symbol enrichment, in `.kt` as well.
 */
object JvmSourceAttachments {

    /** The declared attachments that are source ARCHIVES (`-sources.jar`, a `.zip`). */
    fun attachmentJars(ctx: CompilationContext): List<Path> =
        attachments(ctx).filter {
            val s = it.toString()
            (s.endsWith(".jar") || s.endsWith(".zip")) && Files.isRegularFile(it)
        }

    /** The declared attachments that are exploded source DIRS. */
    fun attachmentDirs(ctx: CompilationContext): List<Path> =
        attachments(ctx).filter { Files.isDirectory(it) }

    /** `<jdkHome>/lib/src.zip` — the JDK's own sources — when the boot JDK image ships them. */
    fun jdkSrcZip(jdkHome: Path?): Path? =
        jdkHome?.resolve("lib")?.resolve("src.zip")?.takeIf { Files.isRegularFile(it) }

    /** The Android framework sources for [ctx]'s `android.jar`, when it sits in a real SDK layout. */
    fun androidPlatformSources(ctx: CompilationContext): Path? =
        classpathPaths(ctx).firstOrNull { it.fileName?.toString() == "android.jar" }
            ?.let { androidPlatformSources(it) }

    /**
     * The Android platform `sources/android-NN` dir for a `platforms/android-NN/android.jar`, so framework APIs
     * complete with real parameter names + javadoc. Prefers the exact platform-dir name, but falls back to any
     * installed `sources/android-NN…` with the same MAJOR API level: the SDK ships framework sources keyed by
     * base level (`android-36`) while the platform jar may be a minor/extension revision (`android-36.1`) — an
     * exact-name-only match would silently miss the sources whenever the two don't line up.
     *
     * Null for a bundled FLAT `android.jar` (the on-device asset has no `platforms/android-NN/` parent); the
     * host attaches the SDK-Manager-installed sources dir explicitly in that case.
     */
    fun androidPlatformSources(androidJar: Path): Path? {
        val platformDir = androidJar.parent ?: return null
        val sourcesRoot = platformDir.parent?.parent?.resolve("sources") ?: return null
        val exact = sourcesRoot.resolve(platformDir.fileName.toString())
        if (Files.isDirectory(exact)) return exact
        if (!Files.isDirectory(sourcesRoot)) return null
        val major = androidMajor(platformDir.fileName.toString()) ?: return null
        return Files.list(sourcesRoot).use { stream ->
            stream.filter { Files.isDirectory(it) && androidMajor(it.fileName.toString()) == major }
                .sorted(compareByDescending { it.fileName.toString() })
                .findFirst().orElse(null)
        }
    }

    /**
     * Every source root for [ctx], with [jdkHome] the resolved JDK image (null on an Android platform, whose
     * `java.*` comes from `android.jar`). Project source roots are deliberately absent: this is the IMMUTABLE
     * library/SDK side, which is what the source-doc index accepts.
     */
    fun librarySourceArchives(ctx: CompilationContext, jdkHome: Path?): List<Path> =
        (attachmentJars(ctx) + attachmentDirs(ctx) +
            listOfNotNull(jdkSrcZip(jdkHome), androidPlatformSources(ctx))).distinct()

    private fun attachments(ctx: CompilationContext): List<Path> =
        ctx.sourceAttachments.mapNotNull { pathOf(it.path) }

    private fun classpathPaths(ctx: CompilationContext): List<Path> =
        (ctx.classpath.entries + ctx.bootClasspath.entries).mapNotNull { pathOf(it.root.path) }

    private fun pathOf(s: String): Path? = runCatching { Paths.get(s) }.getOrNull()

    /** `android-36` / `android-36.1` → 36 (the base API level, ignoring the minor/extension revision). */
    private fun androidMajor(dirName: String): Int? =
        dirName.removePrefix("android-").substringBefore('.').toIntOrNull()
}
