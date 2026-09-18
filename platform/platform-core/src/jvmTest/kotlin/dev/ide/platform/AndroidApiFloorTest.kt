// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The app is minSdk 26, and most of what ships inside it is plain JVM modules that no Android tool checks.
 *
 * Those modules compile against a desktop JDK, so lint's `NewApi` never sees them; D8 does not fail either,
 * because it OUTLINES a too-new call into a synthetic method (`$$ExternalSyntheticApiModelOutline*`) that
 * dexes cleanly and throws `NoSuchMethodError` the first time the line runs on a device below the API that
 * added it. When the call sits inside a `runCatching`, even that is swallowed and the bug presents as a wrong
 * result instead: an empty icon list, a cache marker that never matches, a source attachment that returns null.
 *
 * Nothing in the build enforced the floor, and the same handful of methods reached users five times in three
 * months (`Path.of` three times, `Stream.toList()`, then JGit's `readNBytes`). This is that guard: a text scan
 * of every module that ships inside the APK, for the exact methods that have shipped broken before.
 *
 * Deliberately textual and deliberately narrow. A guard that fails a clean build is worse than none, so each
 * rule matches only spellings that cannot be anything else — `Files.list(...)…toList()` is a
 * `java.util.stream.Stream`, while `Files.newDirectoryStream(...)…toList()` is Kotlin's extension on
 * `Iterable` and is not flagged. The authoritative check remains the dex scan against `api-versions.xml`;
 * this one runs in a second, on every commit, and catches the way these have actually slipped in.
 */
class AndroidApiFloorTest {

    /** Modules that never reach a device: desktop-only, build-time generators, and test/bench support. */
    private val notOnDevice = setOf(
        "ide-desktop",          // the desktop IDE shell
        "build-cli",            // the headless launcher: a desktop/CI shell, like ide-desktop
        "android-sdk-metadata", // a build-time generator; its output ships, it does not
        "bench-support",        // testImplementation only
        "test-support",         // testImplementation only
        "build-logic",          // Gradle plugins for THIS build
        "buildSrc",
        "samples",
    )

    /**
     * A file that may name a banned method because it IS the compatibility shim for it — it reflects or
     * re-implements the method rather than calling the platform's.
     */
    private val shims = setOf("InputStreamCompat.java")

    private data class Rule(val name: String, val since: String, val use: String, val match: (String) -> Boolean)

    private val rules = listOf(
        Rule("Path.of", "API 34", "Paths.get(...)") { "Path.of(" in it },
        Rule("Files.readString", "no Android release at all", "path.readText() / Files.readAllBytes") {
            "Files.readString(" in it
        },
        Rule("Files.writeString", "no Android release at all", "path.writeText() / Files.write") {
            "Files.writeString(" in it
        },
        Rule("InputStream.readNBytes", "API 33", "read into a ByteArray in a loop") { ".readNBytes(" in it },
        Rule("InputStream.transferTo", "API 33", "copyTo(...)") { ".transferTo(" in it },
        Rule("InputStream.nullInputStream", "API 33", "ByteArrayInputStream(ByteArray(0))") {
            "nullInputStream()" in it
        },
        Rule("OutputStream.nullOutputStream", "API 33", "a no-op OutputStream") { "nullOutputStream()" in it },
        // Stream.toList() is JDK 16. Only the spellings whose receiver is unambiguously a Stream are matched,
        // and the fix itself (`collect(Collectors.toList())`) contains the same characters, so it is removed
        // from the line first.
        Rule("Stream.toList", "API 34", "collect(Collectors.toList())") { line ->
            val bare = line.replace("Collectors.toList()", "")
            ".toList()" in bare && listOf("Files.list(", "Files.walk(", "Files.find(").any { it in bare }
        },
    )

    @Test
    fun `no post-API-26 JDK method reaches device-shipped source`() {
        val root = repoRoot()
        val violations = mutableListOf<String>()
        var scanned = 0
        for (file in deviceSources(root)) {
            if (file.name in shims) continue
            scanned++
            file.readLines().forEachIndexed { i, line ->
                val code = line.trim()
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) return@forEachIndexed
                for (rule in rules) {
                    if (rule.match(line)) {
                        violations += "${file.relativeTo(root)}:${i + 1}: ${rule.name} is ${rule.since} — " +
                            "use ${rule.use}\n      $code"
                    }
                }
            }
        }
        assertTrue(scanned > 500, "scanned only $scanned files under $root; has the layout changed?")
        assertTrue(
            violations.isEmpty(),
            "Methods above the app's API-26 floor in source that ships inside the APK. D8 dexes these and " +
                "they throw NoSuchMethodError on the device (or, inside a runCatching, silently return the " +
                "wrong answer):\n\n" + violations.joinToString("\n"),
        )
    }

    /**
     * The production source-set directories a module can ship from.
     *
     * `src/main` is the Kotlin/JVM and Android layout; a multiplatform module has no `main` at all and puts
     * the same code in `commonMain` plus a per-target set. What is deliberately absent is `desktopMain`,
     * the one production set that cannot reach a device, and the Kotlin/Native sets, which have no JDK to
     * call in the first place.
     */
    private val productionSourceSets = listOf(
        "src/main",        // Kotlin/JVM and Android
        "src/commonMain",  // multiplatform: the code every target shares
        "src/jvmMain",     // ...and its JVM half, which on this project means the JVM AND ART
        "src/jvmShared",   // the Compose modules' desktop+android set (it feeds `androidMain`)
        "src/androidMain", // the Compose modules' Android-only set
        "src/debug",       // an Android build variant ships with the app it is built into
        "src/artShims",    // :art-compat's shims, which exist precisely because ART lacks something
        "src/sqliteStub",  // bundled into the Room processor closure, and that runs on the device
    )

    /**
     * Every production Kotlin/Java file of a module that ships inside the app.
     *
     * **This is where the guard went blind once already.** It looked at `src/main` alone and `continue`d
     * past a module without one, so converting a module to multiplatform silently removed it from the scan:
     * after the 2026-09 port, 663 device-shipped files -- every line of `:lang-kotlin` among them -- were
     * unguarded while the test still passed. [assertEverySourceSetIsCovered] is the second half of the fix:
     * a module that ships code from a directory this list does not name now FAILS instead of being skipped.
     */
    private fun deviceSources(root: File): List<File> {
        val out = mutableListOf<File>()
        for (module in deviceModules(root)) {
            for (name in productionSourceSets) {
                val dir = File(module, name)
                if (!dir.isDirectory) continue
                dir.walkTopDown()
                    .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                    .forEach { out += it }
            }
        }
        return out
    }

    /**
     * Fail when a module ships source from a directory [productionSourceSets] does not name.
     *
     * The scan above cannot tell "this module has no production code" from "this module keeps it somewhere
     * I do not look at", and the second is what a layout change looks like. Anything under `src/` that is
     * not a test or a known non-production target has to be either scanned or named here, so the next
     * migration breaks this test rather than the guard.
     */
    @Test
    fun `every shipped source set is covered by the scan`() {
        val ignored = setOf(
            "test", "commonTest", "jvmTest", "androidTest", "desktopTest", "iosTest", "iosSimulatorArm64Test",
            "iosArm64Test", "androidUnitTest", "androidInstrumentedTest", "jvmSharedTest",
            "desktopMain",             // the desktop shell's half of a Compose target: never on a device
            "iosMain", "iosArm64Main", "iosSimulatorArm64Main", // no JDK to call
            "nativeMain", "nativeTest",
        )
        val covered = productionSourceSets.map { it.removePrefix("src/") }.toSet()
        val unknown = mutableListOf<String>()
        for (module in deviceModules(repoRoot())) {
            val src = File(module, "src")
            if (!src.isDirectory) continue
            for (set in src.listFiles().orEmpty().filter { it.isDirectory }) {
                if (set.name in covered || set.name in ignored) continue
                // Only complain about a set that actually holds code.
                val hasCode = set.walkTopDown().any { it.isFile && (it.extension == "kt" || it.extension == "java") }
                if (hasCode) unknown += "${module.name}/src/${set.name}"
            }
        }
        assertTrue(
            unknown.isEmpty(),
            "These source sets ship code that the API-26 scan does not look at. Add each to " +
                "`productionSourceSets` (it reaches a device) or to `ignored` (it does not):\n" +
                unknown.sorted().joinToString("\n"),
        )
    }

    /** The modules that ship inside the APK. */
    private fun deviceModules(root: File): List<File> =
        moduleDirs(root).filter { it.name !in notOnDevice && it.parentFile?.name !in notOnDevice }

    /** Every Gradle module in the checkout, found rather than listed (mirrors PluginBomTest). */
    private fun moduleDirs(root: File): List<File> {
        val depthOne = root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }
        val depthTwo = depthOne.flatMap { it.listFiles().orEmpty().filter(File::isDirectory) }
        return (depthOne + depthTwo).filter { File(it, "build.gradle.kts").isFile }
    }

    /** The checkout root, from whichever directory the test worker was started in. */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("no settings.gradle.kts above ${System.getProperty("user.dir")}")
    }
}
