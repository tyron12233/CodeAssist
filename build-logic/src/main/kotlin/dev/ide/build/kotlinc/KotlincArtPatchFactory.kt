package dev.ide.build.kotlinc

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor

/**
 * AGP bytecode-instrumentation entry point for the Kotlin-compiler-on-ART rewrites.
 *
 * AGP instantiates this factory and calls [isInstrumentable] for every class on the variant's runtime
 * classpath (scope = ALL — project classes *and* dependency jars, including `kotlin-compiler-embeddable`).
 * For the classes a pass claims, [createClassVisitor] returns the chained rewriting visitor; everything else
 * is skipped at no cost. The actual rewrites live in [ArtPatchPasses]; this class is only the AGP glue.
 *
 * Takes no parameters ([InstrumentationParameters.None]) — the pass set is compiled in.
 */
abstract class KotlincArtPatchFactory : AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun isInstrumentable(classData: ClassData): Boolean =
        ArtPatchPasses.handles(classData.className)

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor {
        val fqn = classContext.currentClassData.className
        // isInstrumentable already gated this; visitorFor is non-null in practice, but fall back to the
        // identity chain defensively.
        return ArtPatchPasses.visitorFor(fqn, nextClassVisitor) ?: nextClassVisitor
    }
}
