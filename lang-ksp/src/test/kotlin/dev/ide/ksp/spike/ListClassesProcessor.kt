package dev.ide.ksp.spike

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration

/**
 * A trivial KSP [SymbolProcessor] for the de-risk spike: it resolves every top-level class declaration in the
 * module's sources (proving KSP's frontend parsed and bound the symbols) and emits one Kotlin file listing
 * their names into the generated Kotlin output dir. No annotations required — it runs against the whole file
 * set — so the spike measures the KSP engine itself, not any processor's own logic.
 */
class ListClassesProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    // KSP runs multiple rounds (a processor can react to symbols generated in a prior round). Generate once
    // on the first round, then no-op — otherwise the second round re-creates the same file and KSP throws
    // FileAlreadyExistsException. Real processors instead track per-symbol Dependencies; a flag suffices here.
    private var generated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()
        val names = resolver.getAllFiles()
            .flatMap { it.declarations }
            .filterIsInstance<KSClassDeclaration>()
            .map { it.simpleName.asString() }
            .toList()
        if (names.isEmpty()) return emptyList()
        generated = true

        codeGenerator.createNewFile(Dependencies(aggregating = false), "com.gen", "GeneratedClasses", "kt")
            .bufferedWriter().use { w ->
                w.appendLine("package com.gen")
                w.appendLine()
                w.appendLine("object GeneratedClasses {")
                w.appendLine("    val names: List<String> = listOf(${names.joinToString(", ") { "\"$it\"" }})")
                w.appendLine("}")
            }
        logger.warn("ListClassesProcessor generated com.gen.GeneratedClasses for ${names.size} class(es): $names")
        return emptyList()
    }
}

/** The provider KSP instantiates (also the class named in a `META-INF/services` descriptor for ServiceLoader). */
class ListClassesProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        ListClassesProcessor(environment.codeGenerator, environment.logger)
}
