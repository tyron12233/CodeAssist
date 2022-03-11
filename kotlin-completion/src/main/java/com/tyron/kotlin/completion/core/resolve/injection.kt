package com.tyron.kotlin.completion.core.resolve

import com.tyron.builder.project.api.KotlinModule
import com.tyron.kotlin.completion.core.resolve.lang.java.CodeAssistJavaClassFinder
import com.tyron.kotlin.completion.core.resolve.lang.java.resolver.CodeAssistTraceBasedJavaResolverCache
import org.jetbrains.kotlin.builtins.jvm.JvmBuiltIns
import org.jetbrains.kotlin.builtins.jvm.JvmBuiltInsPackageFragmentProvider
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.config.JvmAnalysisFlags
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.container.*
import org.jetbrains.kotlin.context.ModuleContext
import org.jetbrains.kotlin.frontend.di.configureModule
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.load.java.InternalFlexibleTypeTransformer
import org.jetbrains.kotlin.load.java.JavaClassFinderImpl
import org.jetbrains.kotlin.load.java.JavaClassesTracker
import org.jetbrains.kotlin.load.java.components.JavaPropertyInitializerEvaluatorImpl
import org.jetbrains.kotlin.load.java.components.JavaSourceElementFactoryImpl
import org.jetbrains.kotlin.load.java.components.SignaturePropagatorImpl
import org.jetbrains.kotlin.load.java.components.TraceBasedErrorReporter
import org.jetbrains.kotlin.load.java.lazy.JavaModuleAnnotationsProvider
import org.jetbrains.kotlin.load.java.lazy.JavaResolverSettings
import org.jetbrains.kotlin.load.java.lazy.ModuleClassResolver
import org.jetbrains.kotlin.load.kotlin.DeserializationComponentsForJava
import org.jetbrains.kotlin.load.kotlin.PackagePartProvider
import org.jetbrains.kotlin.load.kotlin.VirtualFileFinderFactory
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.resolve.*
import org.jetbrains.kotlin.resolve.jvm.JavaDescriptorResolver
import org.jetbrains.kotlin.resolve.jvm.SyntheticJavaPartsProvider
import org.jetbrains.kotlin.resolve.jvm.platform.JvmPlatformAnalyzerServices
import org.jetbrains.kotlin.resolve.lazy.KotlinCodeAnalyzer
import org.jetbrains.kotlin.resolve.lazy.ResolveSession
import org.jetbrains.kotlin.resolve.lazy.declarations.DeclarationProviderFactory

fun StorageComponentContainer.configureJavaTopDownAnalysis(
    moduleContentScope: GlobalSearchScope,
    project: Project,
    lookupTracker: LookupTracker,
    languageFeatureSettings: LanguageVersionSettings) {
    useInstance(moduleContentScope)
    useInstance(lookupTracker)

    useImpl<ResolveSession>()

    useImpl<LazyTopDownAnalyzer>()
    useImpl<JavaDescriptorResolver>()
    useImpl<DeserializationComponentsForJava>()

    useInstance(VirtualFileFinderFactory.SERVICE.getInstance(project).create(moduleContentScope))

//    useImpl<EclipseJavaPropertyInitializerEvaluator>()
    useImpl<AnnotationResolverImpl>()
    useImpl<SignaturePropagatorImpl>()
    useImpl<TraceBasedErrorReporter>()
    useInstance(InternalFlexibleTypeTransformer)
}

fun createContainerForLazyResolveWithJava(
    moduleContext: ModuleContext,
    bindingTrace: BindingTrace,
    declarationProviderFactory: DeclarationProviderFactory,
    moduleContentScope: GlobalSearchScope,
    moduleClassResolver: ModuleClassResolver,
    targetEnvironment: TargetEnvironment,
    lookupTracker: LookupTracker,
    packagePartProvider: PackagePartProvider,
    jvmTarget: JvmTarget,
    languageVersionSettings: LanguageVersionSettings,
    javaProject: KotlinModule?,
    javaModuleAnnotationsProvider: JavaModuleAnnotationsProvider,
    useBuiltInsProvider: Boolean
): StorageComponentContainer = createContainer("LazyResolveWithJava", JvmPlatformAnalyzerServices) {
    configureModule(
        moduleContext,
        JvmPlatforms.jvmPlatformByTargetVersion(jvmTarget),
        JvmPlatformAnalyzerServices,
        bindingTrace,
        languageVersionSettings
    )
    configureJavaTopDownAnalysis(moduleContentScope, moduleContext.project, lookupTracker, languageVersionSettings)

    useImpl<JavaClassFinderImpl>()
    useImpl<CodeAssistTraceBasedJavaResolverCache>()
    useImpl<JavaSourceElementFactoryImpl>()

    useInstance(SyntheticJavaPartsProvider.EMPTY)
    useInstance(JavaPropertyInitializerEvaluatorImpl)
    useInstance(packagePartProvider)
    useInstance(moduleClassResolver)
    useInstance(javaModuleAnnotationsProvider)
    useInstance(declarationProviderFactory)
    javaProject?.let { useInstance(it) }

    useInstance(languageVersionSettings.getFlag(JvmAnalysisFlags.javaTypeEnhancementState))

    if (useBuiltInsProvider) {
        useInstance((moduleContext.module.builtIns as JvmBuiltIns).customizer)
        useImpl<JvmBuiltInsPackageFragmentProvider>()
    }

    useInstance(JavaClassesTracker.Default)

    targetEnvironment.configure(this)

    useInstance(JavaResolverSettings.create(
        isReleaseCoroutines = languageVersionSettings.supportsFeature(LanguageFeature.ReleaseCoroutines),
        true,
        typeEnhancementImprovementsInStrictMode = true,
        ignoreNullabilityForErasedValueParameters = false
    ))
}.apply {
    get<JavaClassFinderImpl>().initialize(bindingTrace, get(), languageVersionSettings, jvmTarget)
}

fun createContainerForTopDownAnalyzerForJvm(
    moduleContext: ModuleContext,
    bindingTrace: BindingTrace,
    declarationProviderFactory: DeclarationProviderFactory,
    moduleContentScope: GlobalSearchScope,
    lookupTracker: LookupTracker,
    packagePartProvider: PackagePartProvider,
    jvmTarget: JvmTarget,
    languageVersionSettings: LanguageVersionSettings,
    moduleClassResolver: ModuleClassResolver,
    javaProject: KotlinModule?,
    javaModuleAnnotationsProvider: JavaModuleAnnotationsProvider,
): ComponentProvider = createContainerForLazyResolveWithJava(
    moduleContext, bindingTrace, declarationProviderFactory, moduleContentScope, moduleClassResolver,
    CompilerEnvironment, lookupTracker, packagePartProvider, jvmTarget, languageVersionSettings, javaProject,
    javaModuleAnnotationsProvider,
    useBuiltInsProvider = true
)

// Copy functions from Dsl.kt as they were shrinked by proguard
inline fun <reified T : Any> StorageComponentContainer.useImpl() {
    registerSingleton(T::class.java)
}

inline fun <reified T : Any> ComponentProvider.get(): T {
    return getService(T::class.java)
}