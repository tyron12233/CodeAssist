package dev.ide.lang.kotlin.compile

import dev.ide.lang.CompilationContext
import dev.ide.lang.CompileResult
import dev.ide.lang.SourceCompiler
import dev.ide.model.LanguageLevel
import dev.ide.vfs.VirtualFile
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The [SourceCompiler] for the Kotlin backend: K2 codegen of a module's Kotlin sources (with its Java
 * sources as interop resolution inputs) into the context's output dir, via [KotlinJvmCompiler]. The build
 * graph drives Kotlin compilation through the lighter `dev.ide.build.engine.KotlinCompile` port; this SPI
 * implementation makes `LanguageBackend.createCompiler` non-null so a host that compiles purely through the
 * language-api SPI (or a test) gets Kotlin codegen too.
 */
class KotlinSourceCompiler(private val ctx: CompilationContext) : SourceCompiler {

    private val compiler = KotlinJvmCompiler()

    override suspend fun compile(sources: List<VirtualFile>): CompileResult {
        val paths = sources.map { Paths.get(it.path) }
        val kotlin = paths.filter { it.toString().endsWith(".kt") }
        val java = paths.filter { it.toString().endsWith(".java") }
        val classpath = ctx.classpath.entries.map { Paths.get(it.root.path) }
        val boot = ctx.bootClasspath.entries.map { Paths.get(it.root.path) }
        val r = compiler.compile(kotlin, java, classpath, Paths.get(ctx.outputDir.path), levelOf(ctx.languageLevel), boot)
        return CompileResult(success = r.success, diagnostics = emptyList(), outputClasses = emptyList())
    }

    private fun levelOf(level: LanguageLevel): String = when (level) {
        LanguageLevel.JAVA_8 -> "8"
        LanguageLevel.JAVA_11 -> "11"
        LanguageLevel.JAVA_17 -> "17"
        LanguageLevel.JAVA_21 -> "21"
    }
}
