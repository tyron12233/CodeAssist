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

    /** Every `src/main` Kotlin/Java file of a module that ships inside the app. */
    private fun deviceSources(root: File): List<File> {
        val out = mutableListOf<File>()
        for (module in moduleDirs(root)) {
            if (module.name in notOnDevice || module.parentFile?.name in notOnDevice) continue
            val main = File(module, "src/main")
            if (!main.isDirectory) continue
            main.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { out += it }
        }
        return out
    }

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
