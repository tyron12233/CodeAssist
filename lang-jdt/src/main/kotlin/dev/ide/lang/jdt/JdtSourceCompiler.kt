package dev.ide.lang.jdt

import dev.ide.lang.CompilationContext
import dev.ide.lang.CompileResult
import dev.ide.lang.SourceCompiler
import dev.ide.lang.jdt.compile.JdtBatchCompiler
import dev.ide.model.LanguageLevel
import dev.ide.vfs.VirtualFile
import java.nio.file.Path
import java.nio.file.Paths

/** The [SourceCompiler] for the JDT backend: ecj batch compile of [sources] into the context's output dir. */
class JdtSourceCompiler(private val ctx: CompilationContext) : SourceCompiler {

    override suspend fun compile(sources: List<VirtualFile>): CompileResult {
        val srcPaths = sources.map { Paths.get(it.path) }
        val classpath = ctx.classpath.entries.map { Paths.get(it.root.path) } // module outputs + libraries (not boot)
        val out = Paths.get(ctx.outputDir.path)
        val result = JdtBatchCompiler.compile(srcPaths, classpath, out, levelOf(ctx.languageLevel))
        return CompileResult(success = result.success, diagnostics = emptyList(), outputClasses = emptyList())
    }

    private fun levelOf(level: LanguageLevel): String = when (level) {
        LanguageLevel.JAVA_8 -> "8"
        LanguageLevel.JAVA_11 -> "11"
        LanguageLevel.JAVA_17 -> "17"
        LanguageLevel.JAVA_21 -> "21"
    }
}
