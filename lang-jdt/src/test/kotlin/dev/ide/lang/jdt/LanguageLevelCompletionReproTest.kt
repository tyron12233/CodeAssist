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

/**
 * Repro: does JDT completion still work when the module's language level is JAVA_21 (vs 17/8)?
 * Bug report: "code completion is not working when changing java versions, e.g. changing to 21".
 */
class LanguageLevelCompletionReproTest {

    private fun analyzerAt(level: LanguageLevel, sourceDirs: List<Path>): JdtSourceAnalyzer {
        val ctx = object : CompilationContext {
            override val sourceRoots: List<VirtualFile> = sourceDirs.map { StubFile(it.toString()) }
            override val classpath: ClasspathSnapshot = object : ClasspathSnapshot {
                override val entries: List<ClasspathEntry> = emptyList()
                override fun fingerprint() = ContentHash("")
            }
            override val bootClasspath: ClasspathSnapshot = object : ClasspathSnapshot {
                override val entries = listOf(
                    ClasspathEntry(StubFile(System.getProperty("java.home")), ClasspathEntryKind.SDK_BOOTCLASSPATH)
                )
                override fun fingerprint() = ContentHash(System.getProperty("java.home"))
            }
            override val languageLevel = level
            override val outputDir: VirtualFile = StubFile("/out")
            override val processors: List<AnnotationProcessor> = emptyList()
        }
        return JdtSourceAnalyzer(ctx)
    }

    /** Analyzer whose platform is android.jar (+ optional extra boot jars), NO JDK jrt image — the on-device path. */
    private fun androidAnalyzerAt(level: LanguageLevel, sourceDirs: List<Path>, boot: List<Path>): JdtSourceAnalyzer {
        val ctx = object : CompilationContext {
            override val sourceRoots: List<VirtualFile> = sourceDirs.map { StubFile(it.toString()) }
            override val classpath: ClasspathSnapshot = object : ClasspathSnapshot {
                override val entries: List<ClasspathEntry> = emptyList()
                override fun fingerprint() = ContentHash("")
            }
            override val bootClasspath: ClasspathSnapshot = object : ClasspathSnapshot {
                override val entries = boot.map { ClasspathEntry(StubFile(it.toString()), ClasspathEntryKind.SDK_BOOTCLASSPATH) }
                override fun fingerprint() = ContentHash(boot.joinToString())
            }
            override val languageLevel = level
            override val outputDir: VirtualFile = StubFile("/out")
            override val processors: List<AnnotationProcessor> = emptyList()
        }
        return JdtSourceAnalyzer(ctx)
    }

    @Test
    fun completionAcrossLanguageLevels() {
        val dir = Files.createTempDirectory("jdt-lvl-test")
        val f = dir.resolve("com/example/Main.java")
        Files.createDirectories(f.parent)
        val code = """
            package com.example;
            public class Main {
                public static void main(String[] args) {
                    String s = "hello";
                    s.|CARET|
                }
            }
        """.trimIndent()

        println("=== DESKTOP (JDK jrt boot classpath) ===")
        for (level in listOf(LanguageLevel.JAVA_8, LanguageLevel.JAVA_11, LanguageLevel.JAVA_17, LanguageLevel.JAVA_21)) {
            val analyzer = analyzerAt(level, listOf(dir))
            val items = completeLabels(analyzer, f, code)
            println("LEVEL=$level items=${items.size} hasLength=${items.contains("length")} sample=${items.take(6)}")
            analyzer.dispose()
        }
    }

    @Test
    fun completionAcrossLanguageLevelsAgainstAndroidJar() {
        val androidJar = androidJar() ?: run { println("[android-lvl] no Android SDK — skipping"); return }
        val stubs = coreLambdaStubs()
        println("[android-lvl] android.jar=$androidJar stubs=$stubs")
        val dir = Files.createTempDirectory("android-lvl-test")
        val f = dir.resolve("app/T.java")
        Files.createDirectories(f.parent)
        // A realistic Android buffer with a string concatenation (the Java 9+ invokedynamic trigger).
        val code = """
            package app;
            import android.app.Activity;
            public class T extends Activity {
                void f() {
                    String s = "n=" + 1;
                    s.|CARET|
                }
            }
        """.trimIndent()

        println("=== ANDROID (android.jar only, NO stubs) ===")
        for (level in listOf(LanguageLevel.JAVA_8, LanguageLevel.JAVA_11, LanguageLevel.JAVA_17, LanguageLevel.JAVA_21)) {
            val analyzer = androidAnalyzerAt(level, listOf(dir), listOf(androidJar))
            val items = completeLabels(analyzer, f, code)
            println("LEVEL=$level items=${items.size} hasLength=${items.contains("length")} sample=${items.take(6)}")
            analyzer.dispose()
        }

        if (stubs != null) {
            println("=== ANDROID (android.jar + core-lambda-stubs) ===")
            for (level in listOf(LanguageLevel.JAVA_8, LanguageLevel.JAVA_11, LanguageLevel.JAVA_17, LanguageLevel.JAVA_21)) {
                val analyzer = androidAnalyzerAt(level, listOf(dir), listOf(androidJar, stubs))
                val items = completeLabels(analyzer, f, code)
                println("LEVEL=$level items=${items.size} hasLength=${items.contains("length")} sample=${items.take(6)}")
                analyzer.dispose()
            }
        }
        dir.toFile().deleteRecursively()
    }

    private fun sdkRoots() = listOfNotNull(
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
        System.getProperty("user.home") + "/Library/Android/sdk",
    ).map { Path.of(it) }.filter { Files.isDirectory(it) }

    private fun androidJar(): Path? = sdkRoots().map { it.resolve("platforms") }.filter { Files.isDirectory(it) }
        .flatMap { runCatching { Files.list(it).use { s -> s.toList() } }.getOrDefault(emptyList()) }
        .map { it.resolve("android.jar") }.filter { Files.isRegularFile(it) }
        .maxByOrNull { it.parent.fileName.toString() }

    private fun coreLambdaStubs(): Path? = sdkRoots().map { it.resolve("build-tools") }.filter { Files.isDirectory(it) }
        .flatMap { runCatching { Files.list(it).use { s -> s.toList() } }.getOrDefault(emptyList()) }
        .map { it.resolve("core-lambda-stubs.jar") }.filter { Files.isRegularFile(it) }
        .maxByOrNull { it.parent.fileName.toString() }
}
