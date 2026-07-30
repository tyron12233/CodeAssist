package dev.ide.ksp.spike

import com.google.devtools.ksp.impl.KotlinSymbolProcessing
import com.google.devtools.ksp.processing.KSPJvmConfig
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode
import dev.ide.testkit.withTempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The de-risk spike (docs/kotlin-compiler-plugins-and-codegen.md, roadmap step 3): confirm KSP2's standalone
 * `KotlinSymbolProcessing(...).execute()` runs against this repo's Kotlin 2.4.0 world and generates source,
 * BEFORE wiring the production `KspSourceGenerator` or adding KSP/Room as shipped deps.
 *
 * KSP 2.3.10 explicitly supports Kotlin 2.4.0 (its `-aa-embeddable` runner carries its own relocated Analysis
 * API, so it is decoupled from the host compiler version). This drives it directly — no processor jar, the
 * provider is passed in-process — so it isolates the ENGINE from the ServiceLoader classpath path (which the
 * Room spike exercises separately).
 */
class KspEngineSpikeTest {

    /** Collects KSP's own diagnostics so a failure surfaces them in the assertion message. */
    private class RecordingLogger : KSPLogger {
        val messages = mutableListOf<String>()
        override fun logging(message: String, symbol: KSNode?) { messages += "LOG:  $message" }
        override fun info(message: String, symbol: KSNode?) { messages += "INFO: $message" }
        override fun warn(message: String, symbol: KSNode?) { messages += "WARN: $message" }
        override fun error(message: String, symbol: KSNode?) { messages += "ERR:  $message" }
        override fun exception(e: Throwable) { messages += "EXC:  ${e.stackTraceToString()}" }
    }

    @Test
    fun runsKsp2StandaloneAndGeneratesSource() {
        withTempDir("ksp-engine-spike") { dir ->
            val root = dir.toFile()
            val src = File(root, "src").apply { mkdirs() }
            File(src, "Model.kt").writeText(
                """
                package demo

                class Foo
                data class Bar(val x: Int, val y: String)
                """.trimIndent(),
            )

            val out = File(root, "out")
            val kotlinOut = File(out, "kotlin")
            val config = KSPJvmConfig.Builder().apply {
                moduleName = "spike"
                sourceRoots = listOf(src)
                javaSourceRoots = emptyList()
                libraries = emptyList()
                projectBaseDir = root
                outputBaseDir = out
                cachesDir = File(root, "caches")
                kotlinOutputDir = kotlinOut
                javaOutputDir = File(out, "java")
                classOutputDir = File(out, "classes")
                resourceOutputDir = File(out, "resources")
                languageVersion = "2.4"
                apiVersion = "2.4"
                jvmTarget = "17"
            }.build()

            val logger = RecordingLogger()
            val exit = KotlinSymbolProcessing(config, listOf(ListClassesProcessorProvider()), logger).execute()

            assertEquals(
                KotlinSymbolProcessing.ExitCode.OK, exit,
                "KSP2 did not finish OK.\n${logger.messages.joinToString("\n")}",
            )
            val generated = File(kotlinOut, "com/gen/GeneratedClasses.kt")
            assertTrue(
                generated.exists(),
                "generated file missing. output tree:\n" +
                    out.walkTopDown().filter { it.isFile }.joinToString("\n") { it.relativeTo(out).path },
            )
            val text = generated.readText()
            assertTrue("Foo" in text && "Bar" in text, "generated content did not resolve both classes:\n$text")
        }
    }
}
