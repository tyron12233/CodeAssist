package com.tyron.kotlin.completion.core.resolve

import com.tyron.builder.project.api.JavaModule
import com.tyron.builder.project.api.KotlinModule
import com.tyron.kotlin.completion.core.model.KotlinCommonEnvironment
import com.tyron.kotlin.completion.core.model.KotlinEnvironment
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.builtins.jvm.JvmBuiltIns
import org.jetbrains.kotlin.builtins.jvm.JvmBuiltInsPackageFragmentProvider
import org.jetbrains.kotlin.cli.jvm.compiler.CliBindingTrace
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.container.ComponentProvider
import org.jetbrains.kotlin.context.ContextForNewModule
import org.jetbrains.kotlin.context.MutableModuleContext
import org.jetbrains.kotlin.context.ProjectContext
import org.jetbrains.kotlin.descriptors.PackageFragmentProvider
import org.jetbrains.kotlin.descriptors.impl.CompositePackageFragmentProvider
import org.jetbrains.kotlin.frontend.java.di.initJvmBuiltInsForTopDownAnalysis
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.LazyTopDownAnalyzer
import org.jetbrains.kotlin.resolve.TopDownAnalysisMode
import org.jetbrains.kotlin.resolve.jvm.JavaDescriptorResolver
import org.jetbrains.kotlin.resolve.jvm.extensions.PackageFragmentProviderExtension
import org.jetbrains.kotlin.resolve.jvm.modules.JavaModuleResolver
import org.jetbrains.kotlin.resolve.lazy.KotlinCodeAnalyzer
import org.jetbrains.kotlin.resolve.lazy.declarations.DeclarationProviderFactory
import org.jetbrains.kotlin.resolve.lazy.declarations.FileBasedDeclarationProviderFactory
import org.jetbrains.kotlin.util.KotlinFrontEndException

data class AnalysisResultWithProvider(val analysisResult: AnalysisResult, val componentProvider: ComponentProvider?) {
    companion object {
        val EMPTY = AnalysisResultWithProvider(AnalysisResult.EMPTY, null)
    }
}

object CodeAssistAnalyzerFacadeForJVM {
    fun analyzeSources(
        environment: KotlinCoreEnvironment,
        filesToAnalyze: Collection<KtFile>
    ): AnalysisResultWithProvider {
        val allFiles = LinkedHashSet(filesToAnalyze)
        return analyzeSources(environment, allFiles, filesToAnalyze);
    }

    fun analyzeSources(environment: KotlinCoreEnvironment,
                       allFiles: Collection<KtFile>,
                         filesToAnalyze: Collection<KtFile>): AnalysisResultWithProvider {
        val filesSet = filesToAnalyze.toSet()
        if (filesSet.size != filesToAnalyze.size) {
//            KotlinLogger.logWarning("Analyzed files have duplicates")
        }

        val javaProject = KotlinEnvironment.getJavaProject(environment.project)

        return analyzeKotlin(
            filesToAnalyze = filesSet,
            allFiles = allFiles,
            environment = environment,
            javaProject = javaProject,
//            jvmTarget = environment.compilerProperties.jvmTarget
        )
    }

    private fun analyzeKotlin(
        filesToAnalyze: Collection<KtFile>,
        allFiles: Collection<KtFile>,
        environment: KotlinCoreEnvironment,
        javaProject: KotlinModule?,
        jvmTarget: JvmTarget = JvmTarget.DEFAULT
    ): AnalysisResultWithProvider {
        val trace = CliBindingTrace()
        val moduleContext = createModuleContext(environment.project, environment.configuration, true)
        val container = createContainer(trace, moduleContext, filesToAnalyze, allFiles, environment, javaProject, jvmTarget)

        try {
            container.get<LazyTopDownAnalyzer>().analyzeDeclarations(TopDownAnalysisMode.TopLevelDeclarations, filesToAnalyze)
        } catch (e: KotlinFrontEndException) {
//          Editor will break if we do not catch this exception
//          and will not be able to save content without reopening it.
//          In IDEA this exception throws only in CLI
//            KotlinLogger.logError(e)
        }

        return AnalysisResultWithProvider(
            AnalysisResult.success(trace.bindingContext, moduleContext.module),
            container)
    }

    fun createContainer(
        trace: CliBindingTrace,
        moduleContext: MutableModuleContext,
        filesToAnalyze: Collection<KtFile>,
                        allFiles: Collection<KtFile>,
                        environment: KotlinCoreEnvironment,
                        javaProject: KotlinModule?,
                        jvmTarget: JvmTarget = JvmTarget.DEFAULT): ComponentProvider {
        val project = environment.project
        val storageManager = moduleContext.storageManager
        val module = moduleContext.module

        val providerFactory = FileBasedDeclarationProviderFactory(moduleContext.storageManager, allFiles)

        val sourceScope = TopDownAnalyzerFacadeForJVM.newModuleSearchScope(project, filesToAnalyze)
        val moduleClassResolver = TopDownAnalyzerFacadeForJVM.SourceOrBinaryModuleClassResolver(sourceScope)

        val languageVersionSettings = LanguageVersionSettingsImpl(
            LanguageVersionSettingsImpl.DEFAULT.languageVersion,
            LanguageVersionSettingsImpl.DEFAULT.apiVersion)

        val optionalBuiltInsModule = JvmBuiltIns(storageManager, JvmBuiltIns.Kind.FROM_CLASS_LOADER)
            .apply { initialize(module, true) }
            .builtInsModule

        val dependencyModule = run {
            val dependenciesContext = ContextForNewModule(
                moduleContext, Name.special("<dependencies of ${environment.configuration.getNotNull(
                    CommonConfigurationKeys.MODULE_NAME)}>"),
                module.builtIns, null
            )

            val dependencyScope = GlobalSearchScope.notScope(sourceScope)
            val dependenciesContainer = createContainerForTopDownAnalyzerForJvm(
                dependenciesContext,
                trace,
                DeclarationProviderFactory.EMPTY,
                dependencyScope,
                LookupTracker.DO_NOTHING,
                KotlinPackagePartProvider(environment),
                jvmTarget,
                languageVersionSettings,
                moduleClassResolver,
                javaProject,
                environment.project.getService(JavaModuleResolver::class.java))

            moduleClassResolver.compiledCodeResolver = dependenciesContainer.get<JavaDescriptorResolver>()

            dependenciesContext.setDependencies(listOfNotNull(dependenciesContext.module, optionalBuiltInsModule))
            dependenciesContext.initializeModuleContents(
                CompositePackageFragmentProvider(listOf(
                    moduleClassResolver.compiledCodeResolver.packageFragmentProvider,
                    dependenciesContainer.get<JvmBuiltInsPackageFragmentProvider>()
                ), "test")
            )
            dependenciesContext.module
        }

        val container = createContainerForTopDownAnalyzerForJvm(
            moduleContext,
            trace,
            providerFactory,
            sourceScope,
            LookupTracker.DO_NOTHING,
            KotlinPackagePartProvider(environment),
            jvmTarget,
            languageVersionSettings,
            moduleClassResolver,
            javaProject,
            environment.project.getService(JavaModuleResolver::class.java)
        ).apply {
            initJvmBuiltInsForTopDownAnalysis()
        }

        moduleClassResolver.sourceCodeResolver = container.get()

        val additionalProviders = ArrayList<PackageFragmentProvider>()
        additionalProviders.add(container.get<JavaDescriptorResolver>().packageFragmentProvider)

        PackageFragmentProviderExtension.getInstances(project).mapNotNullTo(additionalProviders) { extension ->
            extension.getPackageFragmentProvider(project, module, storageManager, trace, null, LookupTracker.DO_NOTHING)
        }

        module.setDependencies(
            listOfNotNull(module, dependencyModule, optionalBuiltInsModule),
            setOf(dependencyModule)
        )
        module.initialize(CompositePackageFragmentProvider(
            listOf(container.get<KotlinCodeAnalyzer>().packageFragmentProvider) +
                    additionalProviders,
            "test"
        ))

        return container
    }

    private fun getPath(jetFile: KtFile): String? = jetFile.virtualFile?.path

    @JvmStatic
    fun createModuleContext(
        project: Project,
        configuration: CompilerConfiguration,
        createBuiltInsFromModule: Boolean
    ): MutableModuleContext {
        val projectContext = ProjectContext(project, "context for project ${project.name}")
        val builtIns = JvmBuiltIns(projectContext.storageManager,
            if (createBuiltInsFromModule) JvmBuiltIns.Kind.FROM_DEPENDENCIES else JvmBuiltIns.Kind.FROM_CLASS_LOADER)
        return ContextForNewModule(
            projectContext, Name.special("<${configuration.getNotNull(CommonConfigurationKeys.MODULE_NAME)}>"), builtIns, null
        ).apply {
            if (createBuiltInsFromModule) {
                builtIns.builtInsModule = module
            }
        }
    }
}
