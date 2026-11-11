package dev.ide.core

import dev.ide.testkit.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The report must name the cause of the case it was built for: a library that carries its own copy of a
 * platform class, which the editor then reads in place of the platform's.
 */
class FalseErrorReportTest {

    private fun jar(dir: Path, name: String, vararg classes: String): Path {
        val jar = dir.resolve(name)
        JarOutputStream(Files.newOutputStream(jar)).use { z ->
            for (c in classes) { z.putNextEntry(ZipEntry(c)); z.write(byteArrayOf(1)); z.closeEntry() }
        }
        return jar
    }

    private val source = """
        package demo

        import java.nio.file.Files
        import java.nio.file.Path
        import demo.other.Outer.Inner
        import kotlin.io.path.*

        class T {
            fun write(file: Path, text: String) {
                Files.writeString(file, text)
            }
        }
    """.trimIndent()

    @Test
    fun importsAreReadWithoutAliasesStaticsOrStars() {
        val java = "import static java.util.Map.entry;\nimport java.util.List;\nimport a.b.C as D\n"
        assertEquals(listOf("java.util.Map.entry", "java.util.List", "a.b.C"), FalseErrorReport.importedNames(java))
        assertEquals(
            listOf("java.nio.file.Files", "java.nio.file.Path", "demo.other.Outer.Inner", "kotlin.io.path"),
            FalseErrorReport.importedNames(source),
        )
    }

    @Test
    fun aNestedClassOrAMemberImportIsFoundByItsClassFile() {
        assertTrue("demo/other/Outer\$Inner.class" in FalseErrorReport.classFileCandidates("demo.other.Outer.Inner"))
        assertTrue("java/util/Map.class" in FalseErrorReport.classFileCandidates("java.util.Map.entry"))
    }

    @Test
    fun aClassDeclaredByTwoEntriesIsCalledOut() {
        withTempDir("false-error") { tmp ->
            val platform = jar(tmp, "android.jar", "java/nio/file/Files.class", "java/nio/file/Path.class")
            val testLib = jar(tmp, "old-runtime.jar", "java/nio/file/Files.class")
            val offset = source.indexOf("writeString")
            val report = FalseErrorReport.render(
                FalseErrorReport.Input(
                    appVersion = "1.2.3", host = "test host", severity = "Error",
                    message = "Unresolved reference: writeString",
                    file = tmp.resolve("src/test/kotlin/T.kt"), text = source,
                    startOffset = offset, endOffset = offset + "writeString".length,
                    language = "kotlin", projectRoot = tmp, moduleName = "app", moduleType = "android-app",
                    languageLevel = "21", sourceSet = "test (TEST_IMPLEMENTATION)", platformSdk = "android-36 (ANDROID)",
                    moduleFacts = listOf("compileSdk" to "36"), indexState = "ready",
                    boot = listOf(FalseErrorReport.Entry(platform, "SDK_BOOTCLASSPATH")),
                    classpath = listOf(FalseErrorReport.Entry(testLib, "LIBRARY")),
                    dependencies = listOf(FalseErrorReport.Dependency("TEST_IMPLEMENTATION", "old-runtime", false)),
                    recentLog = listOf("[WARN] x: y"),
                ),
            )
            assertTrue("Unresolved reference: writeString" in report)
            assertTrue("At: line 10, column 15" in report, report)
            assertTrue("Declared by more than one entry" in report, "the shadowed class must be called out")
            val shadowed = report.substringAfter("reading the wrong one):**\n\n").substringBefore("\n\n")
            assertTrue("java.nio.file.Files" in shadowed && "old-runtime.jar" in shadowed && "android.jar" in shadowed, shadowed)
            assertTrue("java.nio.file.Path" !in shadowed, "a class with one declaration is not called out")
            assertTrue("<project>/src/test/kotlin/T.kt" in report, "paths are shown relative to the project")
            assertTrue("compileSdk: 36" in report)
            assertTrue(report.trimEnd().endsWith("```") && "Files.writeString(file, text)" in report, "the flagged file is included")
        }
    }

    /** Through the real services: the module, its platform and its classpath reach the report. */
    @Test
    fun aRealProjectsModuleAndClasspathReachTheReport() {
        withTempDir("false-error-e2e") { dir ->
            IdeServices.bootstrapJavaDemo(dir).use { ide ->
                val app = ide.modules().first { it.name == "app" }
                val probe = ide.sourceRoots(app).first().resolve("com/example/app/Probe.java")
                val text = "package com.example.app;\nimport java.util.List;\npublic class Probe { void m() { List.of().nope(); } }\n"
                val at = text.indexOf("nope")
                val report = ide.falseErrorReport(probe, text, at, at + 4, "The method nope() is undefined", "Error")
                assertTrue("- Module: app" in report, report.take(1500))
                assertTrue("The method nope() is undefined" in report)
                assertTrue("`java.util.List`" in report, "the file's imports are listed")
                assertTrue("## Classpath" in report && "## Platform (boot classpath)" in report)
                assertTrue("public class Probe" in report, "the flagged file is included")
            }
            dir.toFile().deleteRecursively()
        }
    }
}
