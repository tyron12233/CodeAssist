package dev.ide.lang.jdt

import dev.ide.lang.AnnotationProcessor
import dev.ide.lang.CompilationContext
import dev.ide.model.ClasspathEntry
import dev.ide.model.ClasspathEntryKind
import dev.ide.model.ClasspathSnapshot
import dev.ide.model.LanguageLevel
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Repro for the reported bug: on an Android project (platform = `android.jar`), `Locale.getDefault()` (any
 * `java.util.*` reference) errored with "the package java.util is accessible from more than one module:
 * <unnamed> and java.base". android.jar ships its own `java.util.*` (377 classes), so when the DOM analyzer
 * ALSO put the running VM's `java.base` module on the path, the package was visible from two modules — a
 * JPMS split. Skips when no Android SDK is installed.
 */
class LocaleSplitPackageReproTest {

    private fun androidJar(): Path? {
        val roots = listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            System.getProperty("user.home") + "/Library/Android/sdk",
        ).map { Path.of(it).resolve("platforms") }
        return roots.filter { Files.isDirectory(it) }
            .flatMap { runCatching { Files.list(it).use { s -> s.toList() } }.getOrDefault(emptyList()) }
            .map { it.resolve("android.jar") }
            .filter { Files.isRegularFile(it) }
            .maxByOrNull { it.parent.fileName.toString() }
    }

    private fun androidAnalyzer(dir: Path, androidJar: Path): JdtSourceAnalyzer {
        val ctx = object : CompilationContext {
            override val sourceRoots: List<VirtualFile> = listOf(StubFile(dir.toString()))
            override val classpath: ClasspathSnapshot = object : ClasspathSnapshot {
                override val entries: List<ClasspathEntry> = emptyList()
                override fun fingerprint() = ContentHash("")
            }
            // the platform is android.jar (a jar), exactly like the Android sample's boot classpath
            override val bootClasspath: ClasspathSnapshot = object : ClasspathSnapshot {
                override val entries = listOf(ClasspathEntry(StubFile(androidJar.toString()), ClasspathEntryKind.SDK_BOOTCLASSPATH))
                override fun fingerprint() = ContentHash(androidJar.toString())
            }
            override val languageLevel = LanguageLevel.JAVA_17
            override val outputDir: VirtualFile = StubFile("/out")
            override val processors: List<AnnotationProcessor> = emptyList()
        }
        return JdtSourceAnalyzer(ctx)
    }

    private val code =
        "package app; import java.util.Locale; class T { String m() { return \"x\".toUpperCase(Locale.getDefault()); } }"

    @Test
    fun localeIsResolvableUnderTheAndroidPlatform() {
        val jar = androidJar() ?: run { println("[LocaleSplitPackageRepro] no Android SDK — skipping"); return }
        val dir = Files.createTempDirectory("locale-repro")
        try {
            val analyzer = androidAnalyzer(dir, jar)
            val file = StubFile(dir.resolve("app/T.java").toString(), code)

            // The editor's diagnostics flow through diagnose() (the low-level compiler over the custom name
            // environment), which resolves java.* from android.jar alone — no VM java.base module, no split.
            val editorMsgs = analyzer.diagnose(file, code).map { it.message }
            println("[LocaleSplitPackageRepro] editor diagnose(): $editorMsgs")

            assertFalse(editorMsgs.any { "more than one module" in it || "accessible from more than one" in it },
                "Locale must not trip the java.base/<unnamed> split on the editor path: $editorMsgs")
            assertTrue(editorMsgs.isEmpty(), "well-formed Locale code should produce no editor diagnostics: $editorMsgs")
            // The well-formed code also resolves: a clean diagnose() means Locale.getDefault() bound fine.
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
