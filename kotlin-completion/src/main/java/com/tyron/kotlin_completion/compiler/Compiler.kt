package com.tyron.kotlin_completion.compiler

import org.jetbrains.kotlin.cli.jvm.compiler.CliBindingTrace
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.container.ComponentProvider
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingTraceContext
import org.jetbrains.kotlin.resolve.LazyTopDownAnalyzer
import org.jetbrains.kotlin.resolve.TopDownAnalysisMode
import org.jetbrains.kotlin.resolve.lazy.declarations.FileBasedDeclarationProviderFactory

fun test() {
    val first: ComponentProvider? = null
    val analyzer = first?.resolve(LazyTopDownAnalyzer::class.java)?.getValue() as LazyTopDownAnalyzer
    analyzer.analyzeDeclarations(TopDownAnalysisMode.TopLevelDeclarations, emptyList())
}

fun createContainer(environment: KotlinCoreEnvironment, sourcePath: Collection<KtFile>): Pair<ComponentProvider, BindingTraceContext> {
    val cliBindingTrace = CliBindingTrace()
    val container = TopDownAnalyzerFacadeForJVM.createContainer(
        environment.project,
        sourcePath,
        cliBindingTrace,
        environment.configuration,
        environment::createPackagePartProvider,
        ::FileBasedDeclarationProviderFactory
    )
    return Pair(container, cliBindingTrace)
}