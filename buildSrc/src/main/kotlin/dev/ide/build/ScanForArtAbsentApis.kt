package dev.ide.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Verification task that runs [ArtAbsentApiScanner] over a classpath (the app's dexed dependency jars) and
 * **fails the build** when any jar references an ART-absent `java.*` type in a load-bearing position
 * (a supertype or a `static` field), which on-device would be an uncatchable [NoClassDefFoundError] that
 * silently disables a feature — the `StackWalker` / `Runtime$Version` class of bug. Lazily-reached
 * references (instance fields, method bodies) are written to the report as advisory notes but do not fail
 * the build (failing on them would false-positive on working cold-path code, e.g. ecj's `condy` codegen).
 *
 * Wire it over `:ide-android`'s `debugRuntimeClasspath` (which is exactly the set that gets dexed and
 * EXCLUDES the compile-only android.jar — so the platform stubs, which DO carry these types on recent API
 * levels, are correctly not scanned). Make it `dependsOn` the [RelocateTypesInJar] outputs so it sees the
 * relocated (patched) jars, and have `check`/`assemble` depend on it.
 */
@CacheableTask
abstract class ScanForArtAbsentApis : DefaultTask() {

    /** The jars to scan — typically `configurations.named("debugRuntimeClasspath")`. */
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    /** Internal names (or package prefixes ending `/`) to flag. Defaults to [ArtAbsentApiScanner.DEFAULT_DENYLIST]. */
    @get:Input
    abstract val denylist: SetProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun scan() {
        val deny = denylist.get().ifEmpty { ArtAbsentApiScanner.DEFAULT_DENYLIST }
        val loadBearing = StringBuilder()
        val advisory = StringBuilder()
        var loadBearingCount = 0
        var advisoryCount = 0

        for (file in classpath.files.sortedBy { it.name }) {
            if (!file.name.endsWith(".jar") || !file.isFile) continue
            val findings = try {
                ArtAbsentApiScanner.scanJar(file, deny)
            } catch (e: Exception) {
                logger.warn("ScanForArtAbsentApis: could not read ${file.name}: ${e.message}")
                continue
            }
            for (f in findings.sortedWith(compareBy({ it.className }, { it.absentType }))) {
                val where = f.detail?.let { "${f.position} '$it'" } ?: f.position.toString()
                val line = "  ${file.name} :: ${f.className} -> ${f.absentType}  [$where]\n"
                if (f.isLoadBearing) { loadBearing.append(line); loadBearingCount++ }
                else { advisory.append(line); advisoryCount++ }
            }
        }

        val out = report.get().asFile
        out.parentFile?.mkdirs()
        out.writeText(buildString {
            appendLine("ART-absent java.* API scan")
            appendLine("denylist: ${deny.sorted().joinToString(", ")}")
            appendLine()
            appendLine("LOAD-BEARING (uncatchable on-device — build fails): $loadBearingCount")
            append(if (loadBearingCount == 0) "  (none)\n" else loadBearing.toString())
            appendLine()
            appendLine("ADVISORY (lazily reached — review, does not fail): $advisoryCount")
            append(if (advisoryCount == 0) "  (none)\n" else advisory.toString())
        })

        logger.lifecycle(
            "ScanForArtAbsentApis: $loadBearingCount load-bearing, $advisoryCount advisory finding(s); report → ${out.name}"
        )
        if (loadBearingCount > 0) throw GradleException(
            "On-device (ART) blocker: $loadBearingCount load-bearing reference(s) to java.* APIs absent on " +
                "Android at minSdk. These fail at class load as an uncatchable NoClassDefFoundError and disable " +
                "the feature. Relocate them onto an ART-safe shim (see buildSrc RelocateTypesInJar + " +
                "dev.ide.lang.jdt.compat). Details:\n$loadBearing\nFull report: ${out.absolutePath}"
        )
    }
}
