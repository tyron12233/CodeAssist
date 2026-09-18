package dev.ide.model.impl.jdk

import dev.ide.model.PlatformKind
import dev.ide.model.impl.SdkData
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Derives a "core-Java" platform library from the bundled `android.jar` by stripping the Android
 * framework namespaces (`android.*`/`androidx.*`/`com.android.*`/`dalvik.*`) and keeping the standard
 * surface (`java.*`, `javax.*`, `org.w3c`/`org.xml`/`org.json`, …).
 *
 * This is the platform a Java/Kotlin CONSOLE app compiles, completes, and is analyzed against on-device.
 * The IDE never ships a JDK (running cheap on ART is the whole point), so there is no `rt.jar` to use; but
 * a console app runs on ART, whose `java.*` IS android.jar's `java.*`. So the correct core-Java surface is
 * exactly android.jar minus the `android.*` classes that must never appear in a non-Android module. The
 * result mirrors what Gradle gives a `java-library`: the JDK, not the Android SDK.
 *
 * The filtered jar is content-addressed under a cache dir and generated once; core-lambda-stubs
 * (`StringConcatFactory`/`LambdaMetafactory`, which android.jar omits) join the boot classpath separately.
 */
object CorePlatformProvider {

    /** The canonical name of the core-Java SDK in the workspace SDK table. */
    const val SDK_NAME = "core-java"

    /** Bumped when [EXCLUDED_PREFIXES] changes, so a stale cached jar is regenerated. */
    private const val FILTER_VERSION = 1

    /** Fixed zip-entry time (1980-01-01) — reproducible output, and above the DOS-time floor. */
    private const val FIXED_TIME = 315_532_800_000L

    /** Class-path prefixes dropped from the core platform: the Android framework namespaces. */
    private val EXCLUDED_PREFIXES = listOf(
        "android/", "androidx/", "com/android/", "com/google/android/", "dalvik/",
    )

    /**
     * Build (or reuse) the core-Java [SdkData] derived from [androidJar]. [extraStubs] (e.g.
     * core-lambda-stubs) join the boot classpath as-is, after the filtered jar. The filtered jar is cached
     * under [cacheDir]. Returns null if [androidJar] can't be read.
     */
    fun coreJavaSdk(androidJar: Path, extraStubs: List<Path> = emptyList(), cacheDir: Path): SdkData? {
        val filtered = ensureFiltered(androidJar, cacheDir) ?: return null
        val boot = listOf(filtered.toAbsolutePath().normalize().toString()) +
            extraStubs.map { it.toAbsolutePath().normalize().toString() }
        return SdkData(SDK_NAME, boot, buildToolsPath = null, kind = PlatformKind.JVM)
    }

    /** Filter [androidJar] to a core-Java jar under [cacheDir], reusing a prior result. Null on failure. */
    fun ensureFiltered(androidJar: Path, cacheDir: Path): Path? {
        if (!Files.isRegularFile(androidJar)) return null
        val size = Files.size(androidJar)
        val out = cacheDir.resolve("$SDK_NAME-$size-v$FILTER_VERSION.jar")
        if (Files.isRegularFile(out) && Files.size(out) > 0) return out

        Files.createDirectories(cacheDir)
        val tmp = Files.createTempFile(cacheDir, "$SDK_NAME-", ".jar.tmp")
        try {
            ZipFile(androidJar.toFile()).use { zip ->
                ZipOutputStream(Files.newOutputStream(tmp)).use { zos ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (e.isDirectory) continue
                        val name = e.name
                        if (name.startsWith("META-INF/")) continue          // no manifest/signatures on a platform stub
                        if (EXCLUDED_PREFIXES.any { name.startsWith(it) }) continue
                        zos.putNextEntry(ZipEntry(name).apply { time = FIXED_TIME })
                        zip.getInputStream(e).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING)
            return out
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            return null
        }
    }
}
